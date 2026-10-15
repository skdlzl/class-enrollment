param(
    [ValidateSet('Baseline','Mixed')][string]$Scenario = 'Baseline',
    [ValidateSet('REDIS','PESSIMISTIC')][string]$Strategy = 'PESSIMISTIC',
    [bool]$Gate = $false,
    [ValidateRange(1,50)][int]$ReadRpsPerServer = 10,
    [switch]$Warmup,
    [string]$JMeter = 'C:\tools\apache-jmeter-5.6.3\bin\jmeter.bat'
)
$ErrorActionPreference='Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
if (!(Test-Path $JMeter)) { throw "Missing JMeter: $JMeter" }
$states = @(8080,8081 | ForEach-Object {
    $state = Invoke-RestMethod "http://localhost:$_/api/experiment/pool"
    if ($state.strategy -ne $Strategy -or $state.gate -ne $Gate -or !$state.precheck) { throw "Wrong server mode on $_" }
    $state
})
if ($states[0].max -ne $states[1].max) { throw 'Different pool sizes' }
foreach ($sql in @('setup-load-test.sql','setup-catalog-test.sql')) {
    Get-Content ".\jmeter\data\$sql" -Raw | docker exec -i class-enrollment-mysql mysql -uclass_user -pclass_password class_enrollment
    if ($LASTEXITCODE -ne 0) { throw "Fixture failed: $sql" }
}
foreach ($port in @(8080,8081)) {
    $page = Invoke-RestMethod "http://localhost:$port/api/courses?year=2026&semester=2&department=CS&afterId=100000&size=20"
    if ($page.items.Count -ne 20 -or !$page.hasNext) { throw "Catalog fixture/API unavailable on $port" }
}
$phase = if ($Warmup) {'warmup'} else {'measured'}
$name = "catalog-$Scenario-$Strategy-gate$Gate-rps$ReadRpsPerServer-$phase-$(Get-Date -Format 'yyyyMMdd-HHmmss-fff')"
$dir = New-Item -ItemType Directory -Path ".\jmeter\results\$name"
$csv = Join-Path $dir.FullName 'requests.csv'
$lines = @('studentId,courseId,port') + @(0..499 | ForEach-Object { "$(10000+$_),1,$(8080+($_%2))" })
[IO.File]::WriteAllLines($csv,$lines,[Text.UTF8Encoding]::new($false))
[xml]$plan = Get-Content .\jmeter\catalog-mixed-load.jmx -Raw
if ($Scenario -eq 'Baseline') { $plan.SelectSingleNode("//ThreadGroup[@testname='Enrollment']").SetAttribute('enabled','false') }
$jmx = Join-Path $dir.FullName 'plan.jmx'
$plan.Save($jmx)
$revision = git rev-parse HEAD
@{scenario=$Scenario;strategy=$Strategy;gate=$Gate;readRpsPerServer=$ReadRpsPerServer;readSeconds=40;drainSeconds=20;
    measurementFromSecond=10;measurementToSecond=40;enrollmentDelaySeconds=10;users=500;ramp=5;
    warmup=[bool]$Warmup;revision=$revision;servers=$states} | ConvertTo-Json -Depth 5 |
    Set-Content (Join-Path $dir.FullName 'conditions.json') -Encoding UTF8
$jtl = Join-Path $dir.FullName 'samples.jtl'
& $JMeter -n -t $jmx -l $jtl -j (Join-Path $dir.FullName 'jmeter.log') "-Jcsv=$csv" "-JreadRps=$ReadRpsPerServer" '-Jusers=500' '-Jramp=5' '-JreadSeconds=40' '-JenrollmentDelay=10' '-Jjmeter.save.saveservice.output_format=csv' '-Jjmeter.save.saveservice.print_field_names=true'
if ($LASTEXITCODE -ne 0) { throw 'JMeter failed; inspect saved log' }
$rows = @(Import-Csv $jtl)
$anchor = @($rows | Where-Object label -eq 'run-anchor')
if ($anchor.Count -ne 1) { throw 'Missing unique time anchor' }
$begin = [long]$anchor[0].timeStamp + 10000
$end = [long]$anchor[0].timeStamp + 40000
$reads = @($rows | Where-Object { $_.label -like 'catalog-*' -and [long]$_.timeStamp -ge $begin -and [long]$_.timeStamp -lt $end })
$enroll = @($rows | Where-Object label -eq 'enrollment')
if ($Scenario -eq 'Mixed' -and $enroll.Count -ne 500) { throw "Expected 500 enrollment samples, found $($enroll.Count)" }
if ($Scenario -eq 'Baseline' -and $enroll.Count -ne 0) { throw 'Baseline contains enrollment' }
$summary = @($reads + $enroll | Group-Object label,responseCode | ForEach-Object {
    $values = @($_.Group | ForEach-Object { [double]$_.elapsed } | Sort-Object)
    [pscustomobject]@{group=$_.Name;count=$values.Count;meanMs=[math]::Round(($values | Measure-Object -Average).Average,2);
        p95Ms=$values[[int][math]::Ceiling($values.Count*0.95)-1]}
})
$summary | Export-Csv (Join-Path $dir.FullName 'summary.csv') -NoTypeInformation
$summary | Format-Table -AutoSize
$rates = @(8080,8081 | ForEach-Object {
    $label = "catalog-$_"
    $samples = @($reads | Where-Object label -eq $label)
    [pscustomobject]@{server=$_;targetRps=$ReadRpsPerServer;actualStartRps=[math]::Round($samples.Count/30.0,2);
        samples=$samples.Count;failed=@($samples | Where-Object responseCode -ne '200').Count}
})
$rates | Export-Csv (Join-Path $dir.FullName 'read-rates.csv') -NoTypeInformation
$rates | Format-Table -AutoSize
if (@($rates | Where-Object { $_.actualStartRps -lt 0.8*$_.targetRps -or $_.actualStartRps -gt 1.2*$_.targetRps }).Count) {
    Write-Warning 'Read arrival rate differs >20% from target. Check generator/schedule before comparing latency.'
}
$reads | Group-Object { "$($_.label),$([math]::Floor(([long]$_.timeStamp-$begin)/1000))" } |
    Select-Object Name,Count | Export-Csv (Join-Path $dir.FullName 'read-starts-per-second.csv') -NoTypeInformation
if (@($enroll | Where-Object { [long]$_.timeStamp+[long]$_.elapsed -gt $end }).Count) {
    Write-Warning 'Enrollment continued beyond fixed read window. Retain this limit; do not compare a different window for only one mode.'
}
# Pool metrics are emitted by the server scheduler, not by an HTTP sampler.
$poolRows = @()
foreach ($port in @(8080,8081)) {
    $file = ".\backend\logs\catalog-$Strategy-$port.log"
    if (!(Test-Path $file)) { Write-Warning "Missing server telemetry log: $file"; continue }
    $selected = @(Get-Content $file | Where-Object {
        if ($_ -match '^(\S+).*POOL_SAMPLE ') {
            $ms = [DateTimeOffset]::Parse($Matches[1]).ToUnixTimeMilliseconds()
            $ms -ge $begin -and $ms -lt $end
        }
    })
    $selected | Set-Content (Join-Path $dir.FullName "pool-$port.log") -Encoding UTF8
    foreach ($line in $selected) {
        $fields = @{}
        [regex]::Matches($line,'(max|active|idle|waiting|total|acquireCount|acquireTotalMs|acquireMaxMs|timeouts)=([0-9.]+)') |
            ForEach-Object { $fields[$_.Groups[1].Value]=[double]::Parse($_.Groups[2].Value,[Globalization.CultureInfo]::InvariantCulture) }
        $poolRows += [pscustomobject]@{port=$port;active=$fields.active;waiting=$fields.waiting;max=$fields.max;
            acquireCount=$fields.acquireCount;acquireTotalMs=$fields.acquireTotalMs;acquireMaxMs=$fields.acquireMaxMs;timeouts=$fields.timeouts}
    }
    if ($selected.Count -lt 24) { Write-Warning "Sparse pool telemetry on $port ($($selected.Count)/~30); inspect CPU/GC/logging." }
}
$poolRows | Export-Csv (Join-Path $dir.FullName 'pool-samples.csv') -NoTypeInformation
$poolSummary = @($poolRows | Group-Object port | ForEach-Object {
    $count = ($_.Group | Measure-Object acquireCount -Sum).Sum
    $average = if ($count -gt 0) { [math]::Round(($_.Group | Measure-Object acquireTotalMs -Sum).Sum/$count,3) } else { $null }
    [pscustomobject]@{port=$_.Name;samples=$_.Count;maxActive=($_.Group | Measure-Object active -Maximum).Maximum;
        maxWaiting=($_.Group | Measure-Object waiting -Maximum).Maximum;acquireMeanMs=$average;
        acquireMaxMs=($_.Group | Measure-Object acquireMaxMs -Maximum).Maximum;timeouts=($_.Group | Measure-Object timeouts -Sum).Sum}
})
$poolSummary | Export-Csv (Join-Path $dir.FullName 'pool-summary.csv') -NoTypeInformation
$poolSummary | Format-Table -AutoSize
docker exec class-enrollment-mysql mysql -uclass_user -pclass_password class_enrollment -e 'SELECT capacity,enrolled_count,(SELECT COUNT(*) FROM enrollments WHERE course_id=1) AS actual_count FROM courses WHERE id=1;' |
    Tee-Object -FilePath (Join-Path $dir.FullName 'database.txt')
if ($LASTEXITCODE -ne 0) { throw 'DB verification failed' }
Write-Host "Saved: $($dir.FullName)"
