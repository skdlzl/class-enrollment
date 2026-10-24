param([switch]$Warmup,[string]$Jmeter='jmeter.bat')
$ErrorActionPreference='Stop'
. "$PSScriptRoot\comparison-common.ps1"
$context=Read-ComparisonContext
$revision=(git -C $experimentRepo rev-parse HEAD).Trim()
if($LASTEXITCODE -ne 0 -or $revision -ne $context.revision){throw 'Revision changed. Build and start a new comparison session'}
if(git -C $experimentRepo status --porcelain -- experiments/db-split){throw 'Uncommitted experiment changes'}
if($context.measured){throw 'This session has already been measured. Preserve it and start a new session.'}
if(!$Warmup -and !$context.warmedUp){throw 'Run with -Warmup first'}
if($Warmup -and $context.warmedUp){throw 'Warmup already completed. Start a new session to repeat it.'}
if(!(Get-Command $Jmeter -ErrorAction SilentlyContinue)){throw 'Pass -Jmeter with the path to JMeter 5.6.3 jmeter.bat'}
$jmeterVersion=(& $Jmeter -v | Out-String)
if($jmeterVersion -notmatch '5\.6\.3'){throw 'Use JMeter 5.6.3'}
$states=@(Get-ComparisonStates);Assert-ComparisonStates $context $states
$services=if($context.mode -in @('B','C','D')){@('academic','enrollment')}else{@('single')}
$resources=@()
foreach($service in $services){
 $container=(& docker compose -p $context.session -f "$experimentRoot\compose.compare.yml" ps -q $service | Out-String).Trim()
 if($LASTEXITCODE -ne 0 -or !$container){throw "Missing container: $service"}
 $inspect=(& docker inspect $container | ConvertFrom-Json)[0]
 $cpu=if($context.mode -in @('B','C','D')){1000000000}else{2000000000};$memory=if($context.mode -in @('B','C','D')){1073741824}else{2147483648}
 if($inspect.HostConfig.NanoCpus -ne $cpu -or $inspect.HostConfig.Memory -ne $memory){throw 'MySQL resource budget mismatch'}
 $resources+=@{service=$service;image=$inspect.Image;nanoCpus=$inspect.HostConfig.NanoCpus;memoryBytes=$inspect.HostConfig.Memory;command=$inspect.Config.Cmd;mounts=@($inspect.Mounts|Select-Object Name,Destination)}
}
if(@($resources.image|Select-Object -Unique).Count -ne 1){throw 'MySQL image versions differ'}
$redisResource=$null
if($context.mode -in @('C','D')){
 $redisId=(& docker compose -p $context.session -f "$experimentRoot/compose.compare.yml" ps -q redis | Out-String).Trim()
 if(!$redisId){throw 'Missing Redis container'}
 $ri=(& docker inspect $redisId | ConvertFrom-Json)[0]
 if($ri.HostConfig.NanoCpus -ne 500000000 -or $ri.HostConfig.Memory -ne 268435456){throw 'Redis resource budget mismatch'}
 $redisResource=@{image=$ri.Image;nanoCpus=$ri.HostConfig.NanoCpus;memoryBytes=$ri.HostConfig.Memory;command=$ri.Config.Cmd}
}
$fixture=Invoke-RestMethod -Method Post 'http://127.0.0.1:28080/experiment/fixtures' -TimeoutSec 180
Wait-ComparisonIdle $context
# Only this session's fixed dataset is reset. Old experiment DBs are never targeted.
$null=Invoke-RestMethod -Method Post "http://127.0.0.1:28080/experiment/fixtures/$($fixture.runId)/reset" -TimeoutSec 60
$databaseBefore=Invoke-RestMethod "http://127.0.0.1:28080/experiment/integrity?runId=$($fixture.runId)" -TimeoutSec 30
if($databaseBefore.totalCatalogRows -ne 10000 -or $databaseBefore.totalSubjectRows -ne 10000 -or $databaseBefore.totalStudentRows -ne 500 -or $databaseBefore.confirmed -ne 0 -or $databaseBefore.counterMismatches -ne 0 -or $databaseBefore.creditViolations -ne 0){throw 'Fixed dataset precheck failed'}
foreach($serverPort in @(28080,28081)){
 $keyword=[uri]::EscapeDataString([string]([char]0xC218)+[char]0xAC15+[char]0xC2E4+[char]0xC2B5)
 $courses=Invoke-RestMethod "http://127.0.0.1:$serverPort/api/offerings?runId=$($fixture.runId)&department=D0&grade=1&keyword=$keyword"
 if(($courses.id -join ',') -ne '1,2,3,4,5'){throw "Search precheck failed on $serverPort"}
}
$phase=if($Warmup){'warmup'}else{'measured'}
$dir=Join-Path $context.directory ("$($context.mode)-$phase-"+(Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Path $dir -Force|Out-Null
function Save($name,$value){Write-ComparisonJson (Join-Path $dir $name) $value}
Save 'database-before.json' $databaseBefore
Save 'conditions.json' @{version='db-split-v2';mode=$context.mode;round=$context.round;session=$context.session;revision=$revision;warmup=$Warmup.IsPresent;states=$states;fixture=$fixture;durationSeconds=90;readRpsPerTypePerServer=25;users=500;rampSeconds=5;readWorkersPerTypePerServer=25;resourceBudget=@{containers=$resources;redis=$redisResource;totalMySqlCpus=2;totalMySqlMemoryBytes=2147483648;totalBufferPoolBytes=536870912;javaHeapPerServer='512m';connectionsPerApp=$(if($context.mode -eq 'A'){10}else{20});hostCpus=[Environment]::ProcessorCount};jmeterVersion=$jmeterVersion;workload='Same JMeter closed-loop plan for A/B0/B/C/D. Total background target 100 RPS. Report observed RPS and target shortfall; not scheduled skipped requests.'}
# Consume the measurement slot BEFORE load: failed runs must not be silently overwritten by reruns.
if(!$Warmup){$context.measured=$true;Write-ComparisonJson $contextPath $context;Write-ComparisonJson "$($context.directory)\context.json" $context}
foreach($part in @('baseline','mixed')){
 Write-Host "$($context.mode) $part : 90 seconds. Results: $dir"
 & node "$PSScriptRoot\create-plan.mjs" "$dir\$part.jmx" $part
 if($LASTEXITCODE -ne 0){throw 'Plan generation failed'}
 Save "$part-before.json" (@(Get-ComparisonStates))
 $phaseStarted=[DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
 $jmeterArgs=@('-n','-t',"$dir\$part.jmx",'-l',"$dir\$part.jtl",'-j',"$dir\$part.log","-JrunId=$($fixture.runId)",'-Jjmeter.save.saveservice.output_format=xml','-Jjmeter.save.saveservice.response_data=true','-Jjmeter.save.saveservice.subresults=true')
 & $Jmeter @jmeterArgs
 if($LASTEXITCODE -ne 0){throw "JMeter failed. Inspect $part.log"}
 Save "$part-pool.json" (@(28080,28081)|ForEach-Object {
  $serverPort=$_
  @{server=$serverPort;samples=@(Invoke-RestMethod "http://127.0.0.1:$serverPort/experiment/pool-samples"|ForEach-Object {$_}|Where-Object {$_.timestamp -ge $phaseStarted})}
 })
 Save "$part-after.json" (@(Get-ComparisonStates))
}
Wait-ComparisonIdle $context
Save 'database.json' (Invoke-RestMethod "http://127.0.0.1:28080/experiment/integrity?runId=$($fixture.runId)" -TimeoutSec 30)
& "$PSScriptRoot\summarize.ps1" -Directory $dir
if(!(Test-Path "$dir\summary.json")){throw 'Summary generation failed. Do not rerun load; preserve JTL files.'}
if($Warmup){$context.warmedUp=$true;Write-ComparisonJson $contextPath $context;Write-ComparisonJson "$($context.directory)\context.json" $context}
Write-Host "Saved: $dir"
