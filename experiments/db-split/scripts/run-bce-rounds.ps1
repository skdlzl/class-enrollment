param(
 [Parameter(Mandatory=$true)][string]$Jmeter,
 [string]$Bundle='',
 [switch]$Resume,
 [switch]$SkipBuild,
 [ValidateRange(0,300)][int]$CooldownSeconds=30
)
$ErrorActionPreference='Stop'
. "$PSScriptRoot\comparison-common.ps1"
if(!(Test-Path $Jmeter -PathType Leaf)){throw "JMeter not found: $Jmeter"}
$Jmeter=(Resolve-Path $Jmeter).Path
$revision=(git -C $experimentRepo rev-parse HEAD).Trim()
if($LASTEXITCODE -ne 0){throw 'Cannot read revision'}
if(!$Bundle){
 if($Resume){throw 'Resume requires -Bundle with the previous output path'}
 $Bundle=Join-Path $experimentRoot ('results\BCE-3rounds-'+(Get-Date -Format 'yyyyMMdd-HHmmss')+'-'+[guid]::NewGuid().ToString('N').Substring(0,6))
}
if((Test-Path $Bundle) -and !$Resume){throw 'Output exists. Use -Resume explicitly or choose a new bundle.'}
if($Resume -and !(Test-Path "$Bundle\suite.json")){throw 'Resume manifest missing'}
New-Item -ItemType Directory -Force -Path $Bundle|Out-Null
$Bundle=(Resolve-Path $Bundle).Path
$mutex=[System.IO.File]::Open((Join-Path $experimentRoot 'results\bce-runner.lock'),[System.IO.FileMode]::OpenOrCreate,[System.IO.FileAccess]::ReadWrite,[System.IO.FileShare]::None)
$transcript=$false;$powerEnabled=$false
function Read-SuiteJson($path){Get-Content -Raw -Encoding UTF8 $path|ConvertFrom-Json}
function Assert-SuiteResult($path,$mode,$round,$warmup){
 foreach($file in @('summary.json','conditions.json','database.json')){
  if(!(Test-Path "$path\$file")){throw "Incomplete result preserved: $path"}
 }
 $c=Read-SuiteJson "$path\conditions.json"
 if($c.mode -ne $mode -or $c.round -ne $round -or [bool]$c.warmup -ne $warmup -or $c.revision -ne $revision){throw "Result identity/revision mismatch: $path"}
 $s=Read-SuiteJson "$path\summary.json"
 if(!$s.integrityValid -or !$s.fixedDatasetValid -or !$s.outcomeCountValid){throw "Integrity/dataset/outcome error: $path"}
 if(!$s.valid){Write-Warning "Timeouts/unknown responses/target shortfall preserved: $path"}
}
try{
 if($Resume){
  $manifest=Read-SuiteJson "$Bundle\suite.json"
  if($manifest.revision -ne $revision -or $manifest.plan -ne 'BCE-CEB-EBC-v1'){throw 'Suite revision or plan mismatch'}
 }else{Write-ComparisonJson "$Bundle\suite.json" @{revision=$revision;plan='BCE-CEB-EBC-v1';rounds=3;modes=@('B','C','E')}}
 Start-Transcript -Path "$Bundle\run-$(Get-Date -Format 'yyyyMMdd-HHmmss').log"|Out-Null
 $transcript=$true
 if(!('Experiment.Power' -as [type])){Add-Type -Namespace Experiment -Name Power -MemberDefinition '[DllImport("kernel32.dll")] public static extern uint SetThreadExecutionState(uint flags);'}
 if([Experiment.Power]::SetThreadExecutionState([uint32]2147483651) -eq 0){throw 'Could not prevent automatic sleep'}
 $powerEnabled=$true
 if(Test-Path $contextPath){& "$PSScriptRoot\stop-comparison.ps1"}
 if(!$SkipBuild){& "$PSScriptRoot\build-a.ps1"}
 & "$PSScriptRoot\check-preflight.ps1"
 $modes=@('B','C','E')
 for($round=1;$round -le 3;$round++){
  for($offset=0;$offset -lt 3;$offset++){
   $mode=$modes[($round-1+$offset)%3]
   $saved="$Bundle\$round-$mode-measured"
   if(Test-Path $saved){
    Assert-SuiteResult $saved $mode $round $false
    Write-Host "Already complete: round $round / $mode";continue
   }
   $marker="$Bundle\$round-$mode-started.json"
   if(Test-Path $marker){throw "Interrupted session preserved. Inspect $marker before rerunning; no automatic replacement."}
   Write-ComparisonJson $marker @{mode=$mode;round=$round;startedAt=(Get-Date).ToString('o')}
   Write-Host "===== Round $round / $mode =====" -ForegroundColor Cyan
   try{
    & "$PSScriptRoot\start-comparison.ps1" -Mode $mode -Round $round
    $context=Read-ComparisonContext
    Write-ComparisonJson $marker $context
    foreach($phase in @('warmup','measured')){
     if($phase -eq 'warmup'){& "$PSScriptRoot\run-comparison.ps1" -Jmeter $Jmeter -Warmup}
     else{& "$PSScriptRoot\run-comparison.ps1" -Jmeter $Jmeter}
     $result=@(Get-ChildItem $context.directory -Directory|Where-Object Name -Like "$mode-$phase-*")
     if($result.Count -ne 1){throw "Result directory mismatch: $($context.directory)"}
     $dest="$Bundle\$round-$mode-$phase"
     New-Item -ItemType Directory -Path $dest|Out-Null
     foreach($file in @('summary.json','conditions.json','database.json')){Copy-Item (Join-Path $result[0].FullName $file) $dest}
     $result[0].FullName|Set-Content "$dest\source-path.txt" -Encoding UTF8
     Assert-SuiteResult $dest $mode $round ($phase -eq 'warmup')
    }
   }finally{
    if(Test-Path $contextPath){& "$PSScriptRoot\stop-comparison.ps1"}
   }
   Start-Sleep -Seconds $CooldownSeconds
  }
 }
 $rows=@(Get-ChildItem $Bundle -Directory -Filter '*-measured'|Sort-Object Name|ForEach-Object {
  $s=Read-SuiteJson "$($_.FullName)\summary.json";$c=Read-SuiteJson "$($_.FullName)\conditions.json"
  [pscustomobject]@{Run=$_.Name;Mode=$c.mode;Valid=$s.valid;Confirmed=$s.outcomes.CONFIRMED;Unknown=$s.outcomes.SUBMIT_UNKNOWN;JourneyP95=$s.successfulJourney.p95Ms}
 })
 $rows|Format-Table -AutoSize
 $rows|Export-Csv "$Bundle\runs.csv" -NoTypeInformation -Encoding UTF8
 $averages=@($rows|Group-Object Mode|ForEach-Object {
  [pscustomobject]@{Mode=$_.Name;Runs=$_.Count;ConfirmedTotal=($_.Group|Measure-Object Confirmed -Sum).Sum;MeanRunP95Ms=($_.Group|Measure-Object JourneyP95 -Average).Average}
 })
 $averages|Export-Csv "$Bundle\averages.csv" -NoTypeInformation -Encoding UTF8
 Write-Host 'MeanRunP95Ms is the mean of per-run P95 values, not a pooled P95.'
}finally{
 if($powerEnabled){[Experiment.Power]::SetThreadExecutionState([uint32]2147483648)|Out-Null}
 if($transcript){Stop-Transcript|Out-Null}
 try{
  Compress-Archive -Path "$Bundle\*" -DestinationPath "$Bundle.zip" -Force
  Write-Host "Results preserved: $Bundle.zip" -ForegroundColor Green
 }finally{$mutex.Dispose()}
}
