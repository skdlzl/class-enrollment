$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$repo=Resolve-Path (Join-Path $root '..\..')
$revision=(git -C $repo rev-parse HEAD).Trim()
if($LASTEXITCODE -ne 0){throw 'Cannot read revision'}
$changes=git -C $repo status --porcelain -- experiments/db-split
if($changes){throw 'Commit experiment changes before building a measured revision'}
& "$repo\backend\mvnw.cmd" -f "$root\backend\pom.xml" clean verify
if($LASTEXITCODE -ne 0){throw 'Build/tests failed. Docker Desktop must be running for MySQL integration tests.'}
$jar=Join-Path $root 'backend\target\db-split-experiment-0.0.1-SNAPSHOT.jar'
@{revision=$revision;sha256=(Get-FileHash $jar -Algorithm SHA256).Hash}|ConvertTo-Json|Set-Content -Encoding UTF8 "$root\backend\target\build.json"
Write-Host 'Build and safety tests passed.'
