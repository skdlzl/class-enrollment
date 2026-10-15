param(
    [ValidateSet('REDIS','PESSIMISTIC')][string]$Strategy = 'REDIS',
    [ValidateRange(1,10000)][int]$Users = 500,
    [ValidateRange(1,10000)][int]$Capacity = 100,
    [ValidateRange(1,120)][int]$Ramp = 5,
    [bool]$Gate = $false,
    [switch]$Mixed,
    [switch]$Warmup,
    [ValidateRange(10,300)][int]$ProbeSeconds = 30,
    [string]$JMeter = 'C:\tools\apache-jmeter-5.6.3\bin\jmeter.bat'
)
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
if (!(Test-Path $JMeter)) { throw "JMeter not found: $JMeter" }
$states = @(8080,8081 | ForEach-Object {
    $s = Invoke-RestMethod "http://localhost:$_/api/experiment/pool"
    if ($s.strategy -ne $Strategy -or $s.gate -ne $Gate -or !$s.precheck) {
        throw "Port $_ does not match strategy=$Strategy gate=$Gate precheck=true"
    }
    $s
})
if ($states[0].max -ne $states[1].max) { throw 'Different DB pool sizes' }
# This command deliberately resets the repository's load-test data only.
Get-Content .\jmeter\data\setup-load-test.sql -Raw |
    docker exec -i class-enrollment-mysql mysql -uclass_user -pclass_password class_enrollment
if ($LASTEXITCODE -ne 0) { throw 'Database reset failed' }
docker exec class-enrollment-mysql mysql -uclass_user -pclass_password class_enrollment -e "UPDATE courses SET capacity=$Capacity WHERE id=1;"
if ($LASTEXITCODE -ne 0) { throw 'Capacity update failed' }
$phase = if ($Warmup) { 'warmup' } else { 'measured' }
$runName = "$Strategy-gate$Gate-u$Users-c$Capacity-mixed$Mixed-$phase-$(Get-Date -Format 'yyyyMMdd-HHmmss-fff')"
$dir = New-Item -ItemType Directory -Path ".\jmeter\results\$runName"
$csvPath = Join-Path $dir.FullName 'requests.csv'
$lines = @('studentId,port') + @(0..($Users-1) | ForEach-Object { "$(10000+$_),$(8080+($_ % 2))" })
[IO.File]::WriteAllLines($csvPath, $lines, [Text.UTF8Encoding]::new($false))
$readers = if ($Mixed) { 1 } else { 0 }
$revision = git rev-parse HEAD
@{ strategy=$Strategy;gate=$Gate;users=$Users;capacity=$Capacity;ramp=$Ramp;mixed=[bool]$Mixed;
   warmup=[bool]$Warmup;probeSeconds=$ProbeSeconds;revision=$revision;servers=$states } |
    ConvertTo-Json -Depth 5 | Set-Content (Join-Path $dir.FullName 'conditions.json') -Encoding UTF8
$jtl = Join-Path $dir.FullName 'samples.jtl'
& $JMeter -n -t .\jmeter\lock-comparison.jmx -l $jtl "-Jusers=$Users" "-Jramp=$Ramp" "-Jcsv=$csvPath" "-Jreaders=$readers" "-JprobeSeconds=$ProbeSeconds" '-Jsample_variables=active,idle,waiting,total' '-Jjmeter.save.saveservice.output_format=csv' '-Jjmeter.save.saveservice.print_field_names=true'
if ($LASTEXITCODE -ne 0) { throw 'JMeter failed; retain partial results for inspection' }
$rows = @(Import-Csv $jtl)
$enroll = @($rows | Where-Object label -eq 'enrollment')
if ($enroll.Count -ne $Users) { throw "Expected $Users enrollments, found $($enroll.Count)" }
$begin = ($enroll | ForEach-Object { [long]$_.timeStamp } | Measure-Object -Minimum).Minimum
$finish = ($enroll | ForEach-Object { [long]$_.timeStamp + [long]$_.elapsed } | Measure-Object -Maximum).Maximum
$overlap = @($rows | Where-Object { [long]$_.timeStamp -ge $begin -and [long]$_.timeStamp -le $finish })
$summary = @($enroll + @($overlap | Where-Object label -Like 'read-*') | Group-Object label,responseCode | ForEach-Object {
    $times = @($_.Group | ForEach-Object { [double]$_.elapsed } | Sort-Object)
    [pscustomobject]@{ group=$_.Name;count=$times.Count;meanMs=[math]::Round(($times | Measure-Object -Average).Average,2);p95Ms=$times[[int][math]::Ceiling($times.Count*0.95)-1] }
})
$summary | Export-Csv (Join-Path $dir.FullName 'summary.csv') -NoTypeInformation
$summary | Format-Table -AutoSize
$overlap | Where-Object label -Like 'pool-*' | Export-Csv (Join-Path $dir.FullName 'pool-overlap.csv') -NoTypeInformation
$rows | Where-Object label -Like 'pool-*' | Group-Object label | ForEach-Object {
    $last = ($_.Group | ForEach-Object { [long]$_.timeStamp } | Measure-Object -Maximum).Maximum
    if ($last -lt ($finish-1000)) { Write-Warning "Probe ended before enrollment completed: $($_.Name). Increase ProbeSeconds for BOTH modes." }
}
docker exec class-enrollment-mysql mysql -uclass_user -pclass_password class_enrollment -e "SELECT capacity,enrolled_count,(SELECT COUNT(*) FROM enrollments WHERE course_id=1) AS actual_count FROM courses WHERE id=1;" |
    Tee-Object -FilePath (Join-Path $dir.FullName 'database.txt')
if ($LASTEXITCODE -ne 0) { throw 'Database verification failed' }
Write-Host "Saved: $($dir.FullName)"
