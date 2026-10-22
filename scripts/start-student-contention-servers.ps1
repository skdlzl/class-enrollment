param(
    [Parameter(Mandatory=$true)]
    [ValidateSet('PESSIMISTIC','DB_ONLY')]
    [string]$Strategy
)
$ErrorActionPreference = 'Stop'
$backend = Join-Path (Split-Path $PSScriptRoot -Parent) 'backend'
$jar = Join-Path $backend 'target\class-enrollment-backend-0.0.1-SNAPSHOT.jar'
if (-not (Test-Path $jar)) { throw 'Build backend first: .\mvnw.cmd -DskipTests clean package' }
foreach ($serverPort in @(8080,8081)) {
    if (Get-NetTCPConnection -LocalPort $serverPort -State Listen -ErrorAction SilentlyContinue) {
        throw "Port $serverPort is in use. Stop the previous Java server first."
    }
}
foreach ($serverPort in @(8080,8081)) {
    $serverArgs = @(
        '-jar', ('"' + $jar + '"'),
        '--spring.profiles.active=queue',
        "--server.port=$serverPort",
        '--experiment.enabled=true',
        '--spring.datasource.hikari.maximum-pool-size=10',
        '--enrollment.queue.enabled=false',
        "--enrollment.course-lock-strategy=$Strategy",
        '--enrollment.local-gate-enabled=false',
        '--enrollment.capacity-precheck=true',
        '--enrollment.db-admission.limit=0',
        '--enrollment.query-pool.enabled=false'
    )
    Start-Process -FilePath 'java' -ArgumentList $serverArgs -WorkingDirectory $backend
}
Write-Host "Started two Java processes: $Strategy, shared pool 10. Wait for Started ClassEnrollmentApplication in both windows."
