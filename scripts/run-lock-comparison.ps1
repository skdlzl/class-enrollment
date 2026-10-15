param(
    [ValidateSet('REDIS','PESSIMISTIC')][string]$Strategy = 'REDIS',
    [ValidateRange(1,10000)][int]$Users = 500,
    [ValidateRange(1,10000)][int]$Capacity = 100,
    [ValidateRange(0,120)][int]$Ramp = 5,
    [ValidateRange(1,50)][int]$Courses = 1,
    [switch]$Simultaneous,
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
docker exec class-enrollment-mysql mysql -uclass_user -pclass_password class_enrollment -e "UPDATE courses SET capacity=$Capacity WHERE id BETWEEN 1 AND $Courses;"
if ($LASTEXITCODE -ne 0) { throw 'Capacity update failed' }
$phase = if ($Warmup) { 'warmup' } else { 'measured' }
$runName = "$Strategy-gate$Gate-u$Users-courses$Courses-c$Capacity-sync$Simultaneous-mixed$Mixed-$phase-$(Get-Date -Format 'yyyyMMdd-HHmmss-fff')"
$dir = New-Item -ItemType Directory -Path ".\jmeter\results\$runName"
$csvPath = Join-Path $dir.FullName 'requests.csv'
# Alternate the server after each complete course cycle: every course uses BOTH servers.
$lines = @('studentId,courseId,port') + @(0..($Users-1) | ForEach-Object {
    $course = 1 + ($_ % $Courses)
    $port = 8080 + ([int][math]::Floor($_ / $Courses) % 2)
    "$(10000+$_),$course,$port"
})
[IO.File]::WriteAllLines($csvPath, $lines, [Text.UTF8Encoding]::new($false))
$syncUsers = if ($Simultaneous) { $Users } else { 1 }
$readers = if ($Mixed) { 1 } else { 0 }
$revision = git rev-parse HEAD
@{ strategy=$Strategy;gate=$Gate;users=$Users;capacity=$Capacity;courses=$Courses;simultaneous=[bool]$Simultaneous;ramp=$Ramp;mixed=[bool]$Mixed;
   warmup=[bool]$Warmup;probeSeconds=$ProbeSeconds;revision=$revision;servers=$states } |
    ConvertTo-Json -Depth 5 | Set-Content (Join-Path $dir.FullName 'conditions.json') -Encoding UTF8
$jtl = Join-Path $dir.FullName 'samples.jtl'
& $JMeter -n -t .\jmeter\lock-comparison.jmx -l $jtl "-Jusers=$Users" "-JsyncUsers=$syncUsers" "-Jramp=$Ramp" "-Jcsv=$csvPath" "-Jreaders=$readers" "-JprobeSeconds=$ProbeSeconds" -j (Join-Path $dir.FullName 'jmeter.log') '-Jsample_variables=active,idle,waiting,total' '-Jjmeter.save.saveservice.output_format=csv' '-Jjmeter.save.saveservice.print_field_names=true'
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
$startTimes = @($enroll | ForEach-Object { [long]$_.timeStamp } | Sort-Object)
$spread = $startTimes[-1] - $startTimes[0]
"Enrollment start spread: $spread ms (client-side sampler start, not server arrival)" |
    Tee-Object -FilePath (Join-Path $dir.FullName 'start-spread.txt')
if ($Simultaneous -and $spread -gt 1000) {
    Write-Warning 'Starts spread over >1 second; do not describe this run as 10000 requests arriving simultaneously. Check generator CPU/memory and JMeter log.'
}
$poolSummary = @($overlap | Where-Object { $_.label -like 'pool-*' -and $_.responseCode -eq '200' -and $_.waiting -ne '' } |
    Group-Object label | ForEach-Object {
        [pscustomobject]@{ server=$_.Name; samples=$_.Count;
            maxActive=($_.Group | ForEach-Object { [int]$_.active } | Measure-Object -Maximum).Maximum;
            maxWaiting=($_.Group | ForEach-Object { [int]$_.waiting } | Measure-Object -Maximum).Maximum;
            waitingSamples=@($_.Group | Where-Object { [int]$_.waiting -gt 0 }).Count }
    })
$poolSummary | Export-Csv (Join-Path $dir.FullName 'pool-summary.csv') -NoTypeInformation
$poolSummary | Format-Table -AutoSize
$overlap | Where-Object label -Like 'pool-*' | Export-Csv (Join-Path $dir.FullName 'pool-overlap.csv') -NoTypeInformation
$rows | Where-Object label -Like 'pool-*' | Group-Object label | ForEach-Object {
    $last = ($_.Group | ForEach-Object { [long]$_.timeStamp } | Measure-Object -Maximum).Maximum
    if ($last -lt ($finish-1000)) { Write-Warning "Probe ended before enrollment completed: $($_.Name). Increase ProbeSeconds for BOTH modes." }
}
docker exec class-enrollment-mysql mysql -uclass_user -pclass_password class_enrollment -e "SELECT c.id,c.capacity,c.enrolled_count,(SELECT COUNT(*) FROM enrollments e WHERE e.course_id=c.id) AS actual_count FROM courses c WHERE c.id BETWEEN 1 AND $Courses ORDER BY c.id;" |
    Tee-Object -FilePath (Join-Path $dir.FullName 'database.txt')
if ($LASTEXITCODE -ne 0) { throw 'Database verification failed' }
Write-Host "Saved: $($dir.FullName)"
