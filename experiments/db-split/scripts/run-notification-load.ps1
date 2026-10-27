param(
 [ValidateRange(2,10000)][int]$Messages=1000,
 [ValidateRange(1,5)][int]$Rounds=3,
 [ValidateRange(1,64)][int]$WorkersPerServer=8,
 [ValidateRange(1,32)][int]$PoolSizePerServer=10,
 [ValidateRange(1,1000)][int]$ProviderDelayMs=100,
 [ValidateRange(1,2000)][int]$UniqueRps=200,
 [ValidateRange(2,4)][int]$DuplicateCopies=2,
 [string]$OutputDirectory=''
)
$ErrorActionPreference='Stop'
. "$PSScriptRoot\comparison-common.ps1"
if(!$OutputDirectory){$OutputDirectory=Join-Path $experimentRoot ('results\notification-load-'+(Get-Date -Format 'yyyyMMdd-HHmmss')+'-'+[guid]::NewGuid().ToString('N').Substring(0,6))}
if(Test-Path $OutputDirectory){throw 'Output exists; choose a new directory'}
$revision=(git -C $experimentRepo rev-parse HEAD).Trim()
if($LASTEXITCODE -ne 0){throw 'Cannot read revision'}
if(git -C $experimentRepo status --porcelain -- experiments/db-split){throw 'Commit experiment changes before comparison'}
New-Item -ItemType Directory -Path $OutputDirectory|Out-Null
$OutputDirectory=(Resolve-Path $OutputDirectory).Path
$powerEnabled=$false
try{
 if(!('NotificationLoad.Power' -as [type])){Add-Type -Namespace NotificationLoad -Name Power -MemberDefinition '[DllImport("kernel32.dll")] public static extern uint SetThreadExecutionState(uint flags);'}
 if([NotificationLoad.Power]::SetThreadExecutionState([uint32]2147483651) -eq 0){throw 'Could not prevent automatic sleep'}
 $powerEnabled=$true
 # Avoid old app JVMs competing with the new isolated Testcontainers test.
 if(Test-Path $contextPath){& "$PSScriptRoot\stop-comparison.ps1"}
 Write-ComparisonJson "$OutputDirectory\conditions.json" @{revision=$revision;messages=$Messages;rounds=$Rounds;servers=2;workersPerServer=$WorkersPerServer;poolSizePerServer=$PoolSizePerServer;totalWorkers=($WorkersPerServer*2);totalPoolSize=($PoolSizePerServer*2);providerDelayMs=$ProviderDelayMs;uniqueArrivalRps=$UniqueRps;duplicateCopies=$DuplicateCopies;strategies=@('DB_CLAIM','REDIS_OUTBOX');scenarios=@('UNIQUE','DUPLICATE');scope='Two independent notification HTTP server JVMs on one host, shared MySQL and Redis, local mock HTTP provider. Duplicate scenario is controlled, not measured production traffic.'}
 $mavenArgs=@('-f',"$experimentRoot\backend\pom.xml",'clean','test','-Dtest=NotificationSafetyTest#compareNotificationLoad','-Dnotification.load=true',"-Dnotification.load.messages=$Messages","-Dnotification.load.rounds=$Rounds","-Dnotification.load.workers=$WorkersPerServer","-Dnotification.load.pool=$PoolSizePerServer","-Dnotification.load.delay-ms=$ProviderDelayMs","-Dnotification.load.unique-rps=$UniqueRps","-Dnotification.load.duplicate-copies=$DuplicateCopies")
 & "$experimentRepo\backend\mvnw.cmd" @mavenArgs | Tee-Object "$OutputDirectory\console.log"
 $code=$LASTEXITCODE
 if($code -ne 0){throw 'Load test failed or incomplete. Results preserved; do not automatically increase or rerun.'}
}finally{
 if($powerEnabled){[NotificationLoad.Power]::SetThreadExecutionState([uint32]2147483648)|Out-Null}
 $reports=Join-Path $experimentRoot 'backend\target\surefire-reports'
 if(Test-Path $reports){Copy-Item $reports "$OutputDirectory\surefire-reports" -Recurse}
 $result=Join-Path $experimentRoot 'backend\target\notification-load.json'
 if(Test-Path $result){Copy-Item $result $OutputDirectory}
 $serverLogs=Join-Path $experimentRoot 'backend\target\notification-servers'
 if(Test-Path $serverLogs){Copy-Item $serverLogs "$OutputDirectory\notification-servers" -Recurse}
 Compress-Archive -Path "$OutputDirectory\*" -DestinationPath "$OutputDirectory.zip" -Force
 Write-Host "Saved: $OutputDirectory.zip"
}
