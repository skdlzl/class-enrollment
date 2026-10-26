param([string]$OutputDirectory='')
$ErrorActionPreference='Stop'
. "$PSScriptRoot\comparison-common.ps1"
if(!$OutputDirectory){$OutputDirectory=Join-Path $experimentRoot ('results\notifications-'+(Get-Date -Format 'yyyyMMdd-HHmmss')+'-'+[guid]::NewGuid().ToString('N').Substring(0,6))}
if(Test-Path $OutputDirectory){throw 'Choose a new output directory; previous evidence is preserved'}
New-Item -ItemType Directory -Path $OutputDirectory|Out-Null
$OutputDirectory=(Resolve-Path $OutputDirectory).Path
$revision=(git -C $experimentRepo rev-parse HEAD).Trim()
if($LASTEXITCODE -ne 0){throw 'Cannot read revision'}
$dirty=git -C $experimentRepo status --porcelain -- experiments/db-split
if($dirty){throw 'Commit experiment changes before comparison'}
# Testcontainers starts two MySQL instances, Redis and a local fake HTTP provider.
# No real notification account is used and no manual application startup is needed.
$log="$OutputDirectory\console.log"
try{
 & "$experimentRepo\backend\mvnw.cmd" -f "$experimentRoot\backend\pom.xml" clean test '-Dtest=NotificationSafetyTest' |Tee-Object -FilePath $log
 $code=$LASTEXITCODE
 Write-ComparisonJson "$OutputDirectory\conditions.json" @{revision=$revision;exitCode=$code;scope='notification-only controlled test';strategies=@('DB_INLINE','DB_CLAIM','REDIS_OUTBOX');rounds=3;messagesPerRun=12;workerThreads=6;poolSize=2;providerLatencyMs=100;provider='local idempotent mock, no real messages';limitations='No process kill, Redis failover, production resource budgets or real provider exactly-once guarantee tested'}
 if($code -ne 0){throw 'Notification tests failed. Inspect preserved reports before using results.'}
}finally{
 $reports=Join-Path $experimentRoot 'backend\target\surefire-reports'
 if(Test-Path $reports){Copy-Item $reports "$OutputDirectory\surefire-reports" -Recurse}
 $summary=Join-Path $experimentRoot 'backend\target\notification-comparison.json'
 if(Test-Path $summary){Copy-Item $summary $OutputDirectory}
 Compress-Archive -Path "$OutputDirectory\*" -DestinationPath "$OutputDirectory.zip" -Force
 Write-Host "Saved: $OutputDirectory.zip"
}
