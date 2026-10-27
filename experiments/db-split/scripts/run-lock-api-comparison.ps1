param(
 [string]$Jmeter='C:\tools\apache-jmeter-5.6.3\bin\jmeter.bat',
 [ValidateRange(0,1000)][int]$ApiDelayMs=100,
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
 $Bundle=Join-Path $experimentRoot ('results\LOCK-API-3rounds-'+(Get-Date -Format 'yyyyMMdd-HHmmss')+'-'+[guid]::NewGuid().ToString('N').Substring(0,6))
}
if((Test-Path $Bundle) -and !$Resume){throw 'Output exists. Use -Resume explicitly or choose a new bundle.'}
if($Resume -and !(Test-Path "$Bundle\suite.json")){throw 'Resume manifest missing'}
New-Item -ItemType Directory -Force -Path $Bundle|Out-Null
$Bundle=(Resolve-Path $Bundle).Path
$mutex=[System.IO.File]::Open((Join-Path $experimentRoot 'results\lock-api-runner.lock'),[System.IO.FileMode]::OpenOrCreate,[System.IO.FileAccess]::ReadWrite,[System.IO.FileShare]::None)
$transcript=$false;$powerEnabled=$false;$mock=$null
function Read-SuiteJson($path){Get-Content -Raw -Encoding UTF8 $path|ConvertFrom-Json}
function Sum-Metric($summary,$phase,$field){
 [double](($summary.metrics|Where-Object {$_.phase -eq $phase}|Measure-Object -Property $field -Sum).Sum)
}
function Assert-SuiteResult($path,$mode,$round,$warmup){
 foreach($file in @('summary.json','conditions.json','database.json')){
  if(!(Test-Path "$path\$file")){throw "Incomplete result preserved: $path"}
 }
 $c=Read-SuiteJson "$path\conditions.json"
 if($c.mode -ne 'B' -or $c.lockStrategy -ne $mode -or $c.round -ne $round -or [bool]$c.warmup -ne $warmup -or $c.revision -ne $revision){throw "Result identity/revision mismatch: $path"}
 $s=Read-SuiteJson "$path\summary.json"
 if(!$s.integrityValid -or !$s.fixedDatasetValid -or !$s.outcomeCountValid){throw "Integrity/dataset/outcome error: $path"}
 if(!$s.valid){Write-Warning "Timeouts/unknown responses/target shortfall preserved: $path"}
}
try{
 if($Resume){
  $manifest=Read-SuiteJson "$Bundle\suite.json"
  if($manifest.revision -ne $revision -or $manifest.plan -ne 'LOCK-API-v1' -or $manifest.apiDelayMs -ne $ApiDelayMs){throw 'Suite revision or plan mismatch'}
 }else{Write-ComparisonJson "$Bundle\suite.json" @{revision=$revision;plan='LOCK-API-v1';rounds=3;strategies=@('PESSIMISTIC','REDIS','OPTIMISTIC');apiDelayMs=$ApiDelayMs}}
 Start-Transcript -Path "$Bundle\run-$(Get-Date -Format 'yyyyMMdd-HHmmss').log"|Out-Null
 $transcript=$true
 if(!('Experiment.Power' -as [type])){Add-Type -Namespace Experiment -Name Power -MemberDefinition '[DllImport("kernel32.dll")] public static extern uint SetThreadExecutionState(uint flags);'}
 if([Experiment.Power]::SetThreadExecutionState([uint32]2147483651) -eq 0){throw 'Could not prevent automatic sleep'}
 $powerEnabled=$true
 if(Test-Path $contextPath){& "$PSScriptRoot\stop-comparison.ps1"}
 if(!$SkipBuild){& "$PSScriptRoot\build-a.ps1"}
 & "$PSScriptRoot\check-preflight.ps1"
 $readyFile=Join-Path $Bundle ('mock-ready-'+[guid]::NewGuid().ToString('N')+'.json')
 $mockArgs=@(('"'+(Join-Path $PSScriptRoot 'mock-eligibility.mjs')+'"'),('"'+$readyFile+'"'),[string]$ApiDelayMs)
 $mock=Start-Process node -ArgumentList $mockArgs -PassThru -RedirectStandardOutput "$Bundle\mock.log" -RedirectStandardError "$Bundle\mock.err.log"
 $ready=$null
 for($i=0;$i -lt 60;$i++){
  if(Test-Path $readyFile){$ready=Read-SuiteJson $readyFile;break}
  if($mock.HasExited){throw 'Eligibility mock exited. Inspect mock.err.log'}
  Start-Sleep -Milliseconds 500
 }
 if(!$ready -or $ready.pid -ne $mock.Id -or $ready.delayMs -ne $ApiDelayMs){throw 'Eligibility mock identity mismatch'}
 $mockBase="http://127.0.0.1:$($ready.port)"
 Write-ComparisonJson "$Bundle\mock-start.json" (Invoke-RestMethod "$mockBase/state")
 $modes=@('PESSIMISTIC','REDIS','OPTIMISTIC')
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
    & "$PSScriptRoot\start-comparison.ps1" -Mode B -Round $round -LockStrategy $mode -EligibilityUrl "$mockBase/eligibility"
    $context=Read-ComparisonContext
    Write-ComparisonJson $marker $context
    foreach($phase in @('warmup','measured')){
     $mockBefore=Invoke-RestMethod "$mockBase/state"
     if($phase -eq 'warmup'){& "$PSScriptRoot\run-comparison.ps1" -Jmeter $Jmeter -Warmup}
     else{& "$PSScriptRoot\run-comparison.ps1" -Jmeter $Jmeter}
     $result=@(Get-ChildItem $context.directory -Directory|Where-Object Name -Like "B-$phase-*")
     if($result.Count -ne 1){throw "Result directory mismatch: $($context.directory)"}
     $dest="$Bundle\$round-$mode-$phase"
     New-Item -ItemType Directory -Path $dest|Out-Null
     foreach($file in @('summary.json','conditions.json','database.json','baseline-before.json','baseline-after.json','mixed-before.json','mixed-after.json','mixed-pool.json','journeys.json')){Copy-Item (Join-Path $result[0].FullName $file) $dest}
     $result[0].FullName|Set-Content "$dest\source-path.txt" -Encoding UTF8
     Write-ComparisonJson "$dest\eligibility-before.json" $mockBefore
     Write-ComparisonJson "$dest\eligibility-after.json" (Invoke-RestMethod "$mockBase/state")
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
  [pscustomobject]@{Run=$_.Name;Mode=$c.lockStrategy;Valid=$s.valid;Confirmed=$s.outcomes.CONFIRMED;Unknown=$s.outcomes.SUBMIT_UNKNOWN;JourneyP95=$s.successfulJourney.p95Ms;ApiCalls=(Sum-Metric $s 'eligibility.calls' 'count');OptimisticConflicts=(Sum-Metric $s 'optimistic.conflict' 'count');OptimisticRetries=(Sum-Metric $s 'optimistic.retry' 'count');DbConnectionUsageMs=(Sum-Metric $s 'enrollmentPool.usage' 'totalMs');DbConnectionAcquireMs=(Sum-Metric $s 'enrollmentPool.acquire' 'totalMs')}
 })
 $rows|Format-Table -AutoSize
 $rows|Export-Csv "$Bundle\runs.csv" -NoTypeInformation -Encoding UTF8
 $averages=@($rows|Group-Object Mode|ForEach-Object {
  [pscustomobject]@{Mode=$_.Name;Runs=$_.Count;ConfirmedTotal=($_.Group|Measure-Object Confirmed -Sum).Sum;MeanRunP95Ms=($_.Group|Measure-Object JourneyP95 -Average).Average}
 })
 $averages|Export-Csv "$Bundle\averages.csv" -NoTypeInformation -Encoding UTF8
 Write-Host 'MeanRunP95Ms is the mean of per-run P95 values, not a pooled P95.'
}finally{
 if($mock){
  try{if($mockBase){Write-ComparisonJson "$Bundle\mock-end.json" (Invoke-RestMethod "$mockBase/state")}}catch{Write-Warning $_}
  if(!$mock.HasExited){Stop-Process -Id $mock.Id -ErrorAction SilentlyContinue;$mock.WaitForExit(10000)|Out-Null}
 }
 if($powerEnabled){[Experiment.Power]::SetThreadExecutionState([uint32]2147483648)|Out-Null}
 if($transcript){Stop-Transcript|Out-Null}
 try{
  Compress-Archive -Path "$Bundle\*" -DestinationPath "$Bundle.zip" -Force
  Write-Host "Results preserved: $Bundle.zip" -ForegroundColor Green
 }finally{$mutex.Dispose()}
}
