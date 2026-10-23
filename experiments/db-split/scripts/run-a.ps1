param([switch]$Warmup,[string]$Jmeter='jmeter.bat')
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$repo=Resolve-Path (Join-Path $root '..\..')
$revision=(git -C $repo rev-parse HEAD).Trim()
if($LASTEXITCODE -ne 0){throw 'Cannot read Git revision'}
if(!(Get-Command $Jmeter -ErrorAction SilentlyContinue)){throw 'Pass -Jmeter with the full path to jmeter.bat (JMeter 5.6.3)'}
$states=@(28080,28081)|ForEach-Object {Invoke-RestMethod "http://127.0.0.1:$_/experiment/state"}
foreach($s in $states){if($s.version -ne 'db-split-stage-a-v1' -or $s.layout -ne 'SINGLE' -or $s.strategy -ne 'DB_ONLY' -or $s.revision -ne $revision -or $s.pool.max -ne 10){throw 'Server version/configuration mismatch. Rebuild and restart both.'}}
if($states[0].metrics.enabled -ne $states[1].metrics.enabled){throw 'Metrics settings differ'}
$container=(docker compose -f "$root\compose.yml" ps -q single).Trim()
if(!$container){throw 'Experiment MySQL container is not running'}
$inspect=(docker inspect $container | ConvertFrom-Json)[0]
if($inspect.HostConfig.NanoCpus -ne 2000000000 -or $inspect.HostConfig.Memory -ne 2147483648){throw 'MySQL resource budget mismatch'}
$jmeterVersion=(& $Jmeter -v | Out-String)
if($jmeterVersion -notmatch '5\.6\.3'){throw 'Use JMeter 5.6.3 for this experiment'}
$fixture=Invoke-RestMethod -Method Post 'http://127.0.0.1:28080/experiment/fixtures' -TimeoutSec 180
foreach($serverPort in @(28080,28081)){
 $keyword=[uri]::EscapeDataString([string]([char]0xC218)+[char]0xAC15+[char]0xC2E4+[char]0xC2B5)
 $courses=Invoke-RestMethod "http://127.0.0.1:$serverPort/api/offerings?runId=$($fixture.runId)&department=D0&grade=1&keyword=$keyword"
 if(($courses.id -join ',') -ne '1,2,3,4,5'){throw "Search precheck failed on $serverPort"}
}
$phase=if($Warmup){'warmup'}else{'measured'}
$dir=Join-Path $root ("results\A-$phase-"+(Get-Date -Format 'yyyyMMdd-HHmmss')+'-'+$fixture.runId)
New-Item -ItemType Directory -Path $dir -Force | Out-Null
function Save($name,$value){$value | ConvertTo-Json -Depth 30 | Set-Content -Encoding UTF8 (Join-Path $dir $name)}
Save 'conditions.json' @{revision=$revision;warmup=$Warmup.IsPresent;states=$states;fixture=$fixture;durationSeconds=90;readRpsPerTypePerServer=25;users=500;rampSeconds=5;readWorkersPerTypePerServer=25;resourceBudget=@{mysqlCpus=2;mysqlMemoryBytes=$inspect.HostConfig.Memory;image=$inspect.Image;cpuShares=$inspect.HostConfig.CpuShares;javaHeapPerServer='512m';hostCpus=[Environment]::ProcessorCount};workload='JMeter closed-loop paced background reads; actual delivered rates must be reported; two read types, 100 total target RPS';jmeterVersion=$jmeterVersion}
foreach($part in @('baseline','mixed')){
 Write-Host "$part : 90 seconds, results $dir"
 & node "$PSScriptRoot\create-plan.mjs" "$dir\$part.jmx" $part
 if($LASTEXITCODE -ne 0){throw 'Plan generation failed'}
 Save "$part-before.json" (@(28080,28081)|ForEach-Object {Invoke-RestMethod "http://127.0.0.1:$_/experiment/state"})
 $jmeterArgs=@('-n','-t',"$dir\$part.jmx",'-l',"$dir\$part.jtl",'-j',"$dir\$part.log","-JrunId=$($fixture.runId)",'-Jjmeter.save.saveservice.output_format=xml','-Jjmeter.save.saveservice.response_data=true','-Jjmeter.save.saveservice.subresults=true','-Jjmeter.save.saveservice.assertion_results_failure_message=true')
 & $Jmeter @jmeterArgs
 if($LASTEXITCODE -ne 0){throw "JMeter failed: see $part.log"}
 Save "$part-after.json" (@(28080,28081)|ForEach-Object {Invoke-RestMethod "http://127.0.0.1:$_/experiment/state"})
}
Save 'database.json' (Invoke-RestMethod "http://127.0.0.1:28080/experiment/integrity?runId=$($fixture.runId)")
& "$PSScriptRoot\summarize.ps1" -Directory $dir
Write-Host "Saved: $dir"
