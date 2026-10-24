$ErrorActionPreference='Stop'
. "$PSScriptRoot\comparison-common.ps1"
$context=Read-ComparisonContext
if($context.session -notmatch '^enrollment-compare-[0-9]{14}-[0-9a-f]{8}$'){throw 'Invalid comparison session'}
foreach($p in $context.processes){
 $process=Get-CimInstance Win32_Process -Filter "ProcessId = $($p.id)" -ErrorAction SilentlyContinue
 if($process){
  if($process.CommandLine -notmatch [regex]::Escape("--experiment.session=$($context.session)")){throw "PID $($p.id) is not owned by this session. Refusing to stop it."}
  Stop-Process -Id $p.id
 }
}
& docker compose -p $context.session -f "$experimentRoot\compose.compare.yml" --profile single --profile split --profile redis stop
if($LASTEXITCODE -ne 0){throw 'Container stop failed'}
Write-Host 'Servers and comparison containers stopped. Volumes and result files are preserved.'
