$ErrorActionPreference='Stop'
. "$PSScriptRoot\comparison-common.ps1"
$context=Read-ComparisonContext
if($context.session -notmatch '^enrollment-compare-[0-9]{14}-[0-9a-f]{8}$'){throw 'Invalid comparison session'}
# Start-Process can report a launcher PID rather than the JVM that owns the port.
# Identify the JVM by both the exact experiment jar path and session argument.
$jar=Join-Path $experimentRoot 'backend\target\db-split-experiment-0.0.1-SNAPSHOT.jar'
$sessionPattern='--experiment\.session='+[regex]::Escape($context.session)+'(?=["\s]|$)'
$owned=@(Get-CimInstance Win32_Process -ErrorAction Stop | Where-Object {
 $_.Name -match '^javaw?\.exe$' -and
 $_.CommandLine -match [regex]::Escape($jar) -and $_.CommandLine -match $sessionPattern
})
foreach($process in $owned){
 Stop-Process -Id $process.ProcessId -ErrorAction SilentlyContinue
 try {
  Wait-Process -Id $process.ProcessId -Timeout 30 -ErrorAction Stop
 } catch {
  # The JVM may exit between Stop-Process and Wait-Process resolving its PID.
  # Only an already absent process is success; preserve timeout/access errors.
  if($_.FullyQualifiedErrorId -notlike 'NoProcessFoundForGivenId,*'){throw}
 }
}
& docker compose -p $context.session -f "$experimentRoot\compose.compare.yml" --profile single --profile split --profile redis stop
if($LASTEXITCODE -ne 0){throw 'Container stop failed'}
# Process termination and Docker port release can finish asynchronously.
$deadline=(Get-Date).AddSeconds(30)
do {
 $occupied=@(28080,28081,53326,53327,53328,56379 | Where-Object {
  Get-NetTCPConnection -LocalPort $_ -State Listen -ErrorAction SilentlyContinue
 })
 if($occupied.Count -eq 0){break}
 if((Get-Date) -ge $deadline){throw "Ports still occupied: $($occupied -join ', '). No unrelated processes were stopped."}
 Start-Sleep -Milliseconds 500
} while($true)
Write-Host 'Servers and comparison containers stopped. Volumes and result files are preserved.'
