param(
    [string]$JMeter = 'C:\tools\apache-jmeter-5.6.3\bin\jmeter.bat',
    [ValidateRange(1,5)][int]$Rounds = 3,
    [switch]$SkipBuild
)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
Set-Location $root
$utf8 = New-Object System.Text.UTF8Encoding($false)
$out = Join-Path $root ('jmeter/results/admission-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Force $out | Out-Null
$servers = @()
$restore = $null
$summary = @()
$restoreFailure = $null
function Sql([string]$query) {
    $result = @($query | & docker exec -i class-enrollment-mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot --batch --skip-column-names class_enrollment' 2>&1)
    if ($LASTEXITCODE -ne 0) { throw "DB 명령 실패: $($result -join [Environment]::NewLine)" }
    return @($result | ForEach-Object { "$_" })
}
function Reset-Course {
    Sql 'START TRANSACTION; DELETE FROM enrollments WHERE course_id=1; UPDATE courses SET capacity=100,enrolled_count=0 WHERE id=1; COMMIT;' | Out-Null
}
function Stop-Servers {
    foreach ($server in $script:servers) {
        if (-not $server.HasExited) { Stop-Process -Id $server.Id -Force; $server.WaitForExit() }
    }
    $script:servers = @()
}
function Check-Ready([int]$port) {
    $deadline = (Get-Date).AddSeconds(90)
    while ((Get-Date) -lt $deadline) {
        foreach ($server in $script:servers) {
            if ($server.HasExited) { throw "서버 시작 실패. $out 폴더의 stderr 로그를 확인하세요." }
        }
        try {
            $health = Invoke-RestMethod -Uri "http://localhost:$port/api/health" -TimeoutSec 2
            if ($health.status -eq 'UP') { return }
        } catch { }
        Start-Sleep -Milliseconds 500
    }
    throw "${port} 서버 시작 시간 초과"
}
function Stats($rows) {
    $times = @($rows | ForEach-Object { [double]$_.elapsed } | Sort-Object)
    return [ordered]@{
        Count = $times.Count
        AvgMs = [math]::Round(($times | Measure-Object -Average).Average, 1)
        P95Ms = $times[[math]::Ceiling($times.Count * 0.95) - 1]
        MaxMs = $times[-1]
    }
}
try {
    if (-not (Test-Path $JMeter)) { throw "JMeter 경로가 없습니다: $JMeter" }
    & docker info --format '{{.ServerVersion}}' | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Docker Desktop을 먼저 실행하세요.' }
    foreach ($port in 8080,8081) {
        $client = New-Object Net.Sockets.TcpClient
        try {
            $connected = $client.ConnectAsync('localhost',$port)
            try { $null = $connected.Wait(500) } catch { }
            if ($client.Connected) { throw "${port} 서버를 먼저 종료하세요. 기존 프로세스는 자동 종료하지 않습니다." }
        } finally { $client.Dispose() }
    }
    & docker compose up -d --wait
    if ($LASTEXITCODE -ne 0) { throw 'MySQL/Redis 시작 실패' }
    if (-not $SkipBuild) {
        Push-Location (Join-Path $root 'backend')
        try {
            if ($env:OS -eq 'Windows_NT') { & .\mvnw.cmd clean package }
            else { & bash ./mvnw clean package }
            if ($LASTEXITCODE -ne 0) { throw '빌드 또는 테스트 실패' }
        } finally { Pop-Location }
    }
    $jar = Join-Path $root 'backend/target/class-enrollment-backend-0.0.1-SNAPSHOT.jar'
    if (-not (Test-Path $jar)) { throw "빌드된 JAR가 없습니다: $jar" }
    $state = @(Sql 'SELECT capacity,enrolled_count FROM courses WHERE id=1;')
    if ($state.Count -ne 1) { throw '과목 1이 없습니다.' }
    $values = $state[0].Split("`t")
    $originalRows = @(Sql "SELECT id,student_id,course_id,DATE_FORMAT(enrolled_at,'%Y-%m-%d %H:%i:%s.%f') FROM enrollments WHERE course_id=1;")
    $insert = @($originalRows | ForEach-Object {
        $v = $_.Split("`t")
        "INSERT INTO enrollments(id,student_id,course_id,enrolled_at) VALUES($([long]$v[0]),$([long]$v[1]),$([long]$v[2]),'$($v[3])');"
    }) -join "`n"
    $restore = "START TRANSACTION; DELETE FROM enrollments WHERE course_id=1; UPDATE courses SET capacity=$([int]$values[0]),enrolled_count=0 WHERE id=1;`n$insert`nUPDATE courses SET enrolled_count=$([int]$values[1]) WHERE id=1; COMMIT;"
    [IO.File]::WriteAllText((Join-Path $out 'restore-course-1.sql'), $restore, $utf8)
    $students = @(Sql "SELECT s.id FROM students s WHERE s.status='ACTIVE' AND s.max_credits >= (SELECT credits FROM courses WHERE id=1) AND NOT EXISTS (SELECT 1 FROM enrollments e WHERE e.student_id=s.id AND e.course_id<>1) ORDER BY s.id LIMIT 500;")
    if ($students.Count -ne 500) { throw '다른 과목을 신청하지 않은 재학생 500명이 필요합니다.' }
    $csv = "studentId,courseId,port`n" + ((0..499 | ForEach-Object { "$([long]$students[$_]),1,$(8080 + ($_ % 2))" }) -join "`n") + "`n"
    $data = Join-Path $out 'requests.csv'
    [IO.File]::WriteAllText($data, $csv, $utf8)
    [IO.File]::WriteAllText((Join-Path $out 'conditions.json'), (@{
        Rounds=$Rounds; Threads=500; RampSeconds=5; Loops=1; Capacity=100
        Ports=@(8080,8081); TimingLogInterval=1; WarmupRequests=20
        Java=(& java -version 2>&1 | Out-String); GitCommit=(& git rev-parse HEAD | Out-String).Trim()
    } | ConvertTo-Json), $utf8)
    for ($round=1; $round -le $Rounds; $round++) {
        $modes = if ($round % 2 -eq 1) { @('false','true') } else { @('true','false') }
        foreach ($mode in $modes) {
            $name = if ($mode -eq 'true') { "admission-r$round" } else { "baseline-r$round" }
            Write-Host "측정 준비: $name"
            Reset-Course
            foreach ($port in 8080,8081) {
                $javaArguments = @('-jar', $jar, "--server.port=$port", "--enrollment.local-course-gate.enabled=$mode", '--enrollment.timing-log-interval=1')
                # Start-Process joins arguments; quote the JAR path explicitly for paths with spaces.
                $javaArguments[1] = '"' + $jar + '"'
                $server = Start-Process java -ArgumentList $javaArguments -PassThru -RedirectStandardOutput (Join-Path $out "$name-$port.log") -RedirectStandardError (Join-Path $out "$name-$port.stderr.log")
                $script:servers += $server
            }
            Check-Ready 8080
            Check-Ready 8081
            for ($i=0; $i -lt 20; $i++) {
                $body = @{studentId=[long]$students[$i];courseId=1} | ConvertTo-Json -Compress
                $null = Invoke-RestMethod -Method Post -Uri "http://localhost:$(8080+($i%2))/api/enrollments" -ContentType 'application/json' -Body $body -TimeoutSec 20
            }
            Reset-Course
            $jtl = Join-Path $out "$name.jtl"
            $errors = Join-Path $out "$name-errors.xml"
            $started = Get-Date
            & $JMeter -n -t (Join-Path $root 'jmeter/course-admission-500.jmx') -l $jtl -j (Join-Path $out "$name-jmeter.log") "-JdataFile=$($data.Replace('\','/'))" "-JerrorFile=$($errors.Replace('\','/'))"
            if ($LASTEXITCODE -ne 0) { throw "JMeter 실패: $name" }
            $rows = @(Import-Csv $jtl | Where-Object { $_.label -eq 'Enrollment' })
            if ($rows.Count -ne 500) { throw "$name 요청 수 오류: $($rows.Count)" }
            $db = @(Sql 'SELECT c.capacity,c.enrolled_count,(SELECT COUNT(*) FROM enrollments WHERE course_id=1) FROM courses c WHERE c.id=1;')[0].Split("`t")
            $counts = @{}
            foreach ($g in ($rows | Group-Object responseCode)) { $counts[$g.Name] = $g.Count }
            $successes = if ($counts.ContainsKey('201')) { $counts['201'] } else { 0 }
            $consistent = ([int]$db[1] -eq [int]$db[2] -and [int]$db[2] -eq $successes -and $successes -le 100)
            $stats = Stats $rows
            $record = [pscustomobject]@{Run=$name; Enabled=$mode; Count=$stats['Count']; AvgMs=$stats.AvgMs; P95Ms=$stats.P95Ms; MaxMs=$stats.MaxMs; Responses=$counts; DbCount=[int]$db[2]; EnrolledCount=[int]$db[1]; Consistent=$consistent; Started=$started.ToString('o')}
            $summary += $record
            Write-Host ($record | ConvertTo-Json -Compress)
            [IO.File]::WriteAllText((Join-Path $out 'summary.json'), (ConvertTo-Json -InputObject @($summary) -Depth 6), $utf8)
            Stop-Servers
            if (-not $consistent) { throw '정합성 검증 실패. 측정을 중단합니다.' }
        }
    }
    Write-Host '측정 완료. 평균·P95와 503을 함께 비교하세요.'
} finally {
    Stop-Servers
    if ($null -ne $restore) {
        try { Sql $restore | Out-Null; Write-Host '과목 1의 기존 신청 내역과 정원을 복원했습니다.' }
        catch { $restoreFailure = $_; Write-Warning "DB 복원 실패. $out/restore-course-1.sql을 사용하세요. $($_.Exception.Message)" }
    }
    if (Test-Path $out) {
        Compress-Archive -Path (Join-Path $out '*') -DestinationPath ($out + '.zip') -Force
        Write-Host "결과와 오류 응답, 서버 로그: $out.zip"
    }
}

if ($null -ne $restoreFailure) { throw $restoreFailure }
