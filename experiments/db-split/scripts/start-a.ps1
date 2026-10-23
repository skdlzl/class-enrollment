param([switch]$MetricsOff)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$repo=Resolve-Path (Join-Path $root '..\..')
$jar=Join-Path $root 'backend\target\db-split-experiment-0.0.1-SNAPSHOT.jar'
if(!(Test-Path $jar)){throw 'Build experiment backend first'}
foreach($serverPort in @(28080,28081)){
 if(Get-NetTCPConnection -LocalPort $serverPort -State Listen -ErrorAction SilentlyContinue){throw "Port $serverPort is already in use"}
}
$revision=(git -C $repo rev-parse HEAD).Trim()
if($LASTEXITCODE -ne 0){throw 'Cannot read Git revision'}
$build=Get-Content -Raw "$root\backend\target\build.json"|ConvertFrom-Json
if($build.revision -ne $revision -or $build.sha256 -ne (Get-FileHash $jar -Algorithm SHA256).Hash){throw 'Stale JAR. Run build-a.ps1 again.'}
if(git -C $repo status --porcelain -- experiments/db-split){throw 'Uncommitted experiment changes'}

foreach($serverPort in @(28080,28081)){
 $javaArgs=@('-Xms512m','-Xmx512m','-jar',('"'+$jar+'"'),"--server.port=$serverPort","--experiment.revision=$revision","--experiment.metrics-enabled=$((!$MetricsOff.IsPresent).ToString().ToLowerInvariant())")
 Start-Process java -ArgumentList $javaArgs -WorkingDirectory $root
}
Write-Host 'A: SINGLE / DB_ONLY, ports 28080 and 28081, shared pool 10 per server. Wait for Started on both.'
