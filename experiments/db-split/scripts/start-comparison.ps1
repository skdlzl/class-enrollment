param([Parameter(Mandatory=$true)][ValidateSet('A','B0','B')][string]$Mode,
      [ValidateRange(1,99)][int]$Round=1,[switch]$MetricsOff)
$ErrorActionPreference='Stop'
$Mode=$Mode.ToUpperInvariant()
. "$PSScriptRoot\comparison-common.ps1"
$jar=Join-Path $experimentRoot 'backend\target\db-split-experiment-0.0.1-SNAPSHOT.jar'
$revision=(git -C $experimentRepo rev-parse HEAD).Trim()
if($LASTEXITCODE -ne 0){throw 'Cannot read Git revision'}
$build=Get-Content -Raw "$experimentRoot\backend\target\build.json"|ConvertFrom-Json
if($build.revision -ne $revision -or $build.sha256 -ne (Get-FileHash $jar -Algorithm SHA256).Hash){throw 'Rebuild with scripts/build.ps1 first'}
if(git -C $experimentRepo status --porcelain -- experiments/db-split){throw 'Commit experiment changes before measuring'}
foreach($serverPort in @(28080,28081,53326,53327,53328)){
 if(Get-NetTCPConnection -LocalPort $serverPort -State Listen -ErrorAction SilentlyContinue){throw "Port $serverPort is occupied. Stop the previous comparison/server first."}
}
$session='enrollment-compare-'+(Get-Date -Format 'yyyyMMddHHmmss')+'-'+([guid]::NewGuid().ToString('N').Substring(0,8))
$dir=Join-Path $experimentRoot "results\$session"
New-Item -ItemType Directory -Path $dir -Force|Out-Null
$context=@{session=$session;mode=$Mode;round=$Round;revision=$revision;directory=$dir;processes=@();warmedUp=$false;measured=$false;metricsEnabled=(!$MetricsOff.IsPresent)}
Write-ComparisonJson $contextPath $context
Write-ComparisonJson "$dir\context.json" $context
$profile=if($Mode -eq 'B'){'split'}else{'single'}
& docker compose -p $session -f "$experimentRoot\compose.compare.yml" --profile $profile up -d --wait
if($LASTEXITCODE -ne 0){throw "MySQL startup failed. Session retained: $session"}
$enrollmentPort=if($Mode -eq 'B'){53328}else{53326}
foreach($serverPort in @(28080,28081)){
 $javaArgs=@('-Xms512m','-Xmx512m','-jar',('"'+$jar+'"'),"--server.port=$serverPort","--experiment.revision=$revision","--experiment.mode=$Mode","--experiment.session=$session",'--experiment.dataset-policy=fixed',"--experiment.metrics-enabled=$((!$MetricsOff.IsPresent).ToString().ToLowerInvariant())","--spring.datasource.url=jdbc:mysql://127.0.0.1:$enrollmentPort/enrollment_experiment?characterEncoding=UTF-8&serverTimezone=UTC",'--spring.datasource.username=experiment','--spring.datasource.password=experiment-local')
 if($Mode -eq 'B'){$javaArgs+=@('--experiment.academic.url=jdbc:mysql://127.0.0.1:53327/enrollment_experiment?characterEncoding=UTF-8&serverTimezone=UTC','--experiment.academic.username=experiment','--experiment.academic.password=experiment-local')}
 $process=Start-Process java -ArgumentList $javaArgs -WorkingDirectory $experimentRoot -RedirectStandardOutput "$dir\server-$serverPort.log" -RedirectStandardError "$dir\server-$serverPort.err.log" -PassThru
 $context.processes+=@{id=$process.Id;port=$serverPort}
 Write-ComparisonJson $contextPath $context
 Write-ComparisonJson "$dir\context.json" $context
}
$ready=$false
for($i=0;$i -lt 120;$i++){
 try{$states=@(Get-ComparisonStates);Assert-ComparisonStates $context $states;$ready=$true;break}catch{
  foreach($p in $context.processes){if(!(Get-Process -Id $p.id -ErrorAction SilentlyContinue)){throw "Server exited. Check $dir/server-$($p.port).log"}}
  Start-Sleep -Seconds 1
 }
}
if(!$ready){throw "Server startup timed out. Inspect $dir and stop with stop-comparison.ps1"}
Write-Host "Ready: $Mode, round $Round. New dataset volumes: $session. Run run-comparison.ps1 -Warmup next."
