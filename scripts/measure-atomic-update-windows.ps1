param(
    [string]$JMeter='C:\tools\apache-jmeter-5.6.3\bin\jmeter.bat',
    [ValidateRange(1,3)][int]$Rounds=3,
    [ValidateRange(0,30)][int]$RampSeconds=5
)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$utf8=New-Object System.Text.UTF8Encoding($false)
. (Join-Path $PSScriptRoot 'atomic-diagnostics/report.ps1')
$stamp=Get-Date -Format 'yyyyMMdd-HHmmss'
$out=Join-Path $root "jmeter/results/windows-atomic-$stamp"
$scratch=Join-Path ([IO.Path]::GetTempPath()) "ce-atomic-$stamp"
$project="ce-atomic-$stamp"
$compose=Join-Path $PSScriptRoot 'atomic-diagnostics/compose.yml'
$worktrees=@();$servers=@();$monitor=$null;$containersCreated=$false;$summary=@()
$savedEnv=@{}
foreach($name in 'DB_URL','DB_USERNAME','DB_PASSWORD','REDIS_ADDRESS') {
    $savedEnv[$name]=[Environment]::GetEnvironmentVariable($name,'Process')
}
function Check-Exit([string]$Operation) {
    if($LASTEXITCODE -ne 0) { throw "$Operation failed, exit $LASTEXITCODE" }
}
function Sql([string]$Query) {
    $text=@($Query | & docker exec -i $script:mysqlContainer sh -c 'MYSQL_PWD=analysis-test-password mysql -uroot --batch --skip-column-names atomic_analysis' 2>&1)
    Check-Exit 'MySQL query'
    return @($text | ForEach-Object { "$_" })
}
function Reset-Course {
    $database=@(Sql 'SELECT DATABASE();')[0]
    if($database -ne 'atomic_analysis') { throw 'Unexpected database; refusing reset' }
    Sql 'START TRANSACTION; DELETE FROM enrollments; UPDATE courses SET capacity=100,enrolled_count=0 WHERE id=1; COMMIT;' | Out-Null
}
function Stop-Servers {
    foreach($server in $script:servers) {
        if(-not $server.Process.HasExited) {
            Stop-Process -Id $server.Process.Id -Force
            $server.Process.WaitForExit()
        }
    }
    $script:servers=@()
}
function Flush-Evidence {
    # Windows Stop-Process does not run Java shutdown hooks. Flush before terminating our JVMs.
    foreach($server in $script:servers) {
        if($server.Process.HasExited) { throw "Server stopped: port $($server.Port)" }
        Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:$($server.Port)/internal/analysis/flush" -TimeoutSec 5 | Out-Null
    }
}
function Wait-Ready([int]$Port) {
    $deadline=(Get-Date).AddSeconds(100)
    while((Get-Date) -lt $deadline) {
        foreach($server in $script:servers) {
            if($server.Process.HasExited) { throw "Server startup failed; inspect $out" }
        }
        try {
            $health=Invoke-RestMethod -Uri "http://127.0.0.1:$Port/api/health" -TimeoutSec 2
            if($health.status -eq 'UP') { return }
        } catch { }
        Start-Sleep -Milliseconds 400
    }
    throw "Server startup timed out: $Port"
}
function Stop-Monitor {
    if($null -ne $script:monitor) {
        [IO.File]::WriteAllText($script:stopFile,'stop',$utf8)
        $finished=Wait-Job $script:monitor -Timeout 15
        if($null -eq $finished) { Stop-Job $script:monitor; throw 'MySQL observer did not finish' }
        $state=$script:monitor.State
        Receive-Job $script:monitor -ErrorAction Stop | Out-Null
        Remove-Job $script:monitor
        $script:monitor=$null
        if($state -ne 'Completed') { throw "MySQL observer failed: $state" }
    }
}
try {
    foreach($command in 'git','docker','java') { Get-Command $command -ErrorAction Stop | Out-Null }
    if(-not (Test-Path $JMeter)) { throw "JMeter not found: $JMeter" }
    & docker info --format '{{json .}}' | Out-Null
    Check-Exit 'Docker Desktop engine'
    $javaText=(& java --version | Out-String)
    Check-Exit 'Java version'
    if($javaText -notmatch '(openjdk |java |version "?)17\.') { throw 'Use Java 17 for this matched comparison' }
    foreach($port in 13306,16379,18080,18081) {
        if(Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue) { throw "Experiment port is occupied: $port" }
    }
    New-Item -ItemType Directory -Force $out,$scratch | Out-Null
    $computer=Get-CimInstance Win32_ComputerSystem
    $cpu=Get-CimInstance Win32_Processor
    $dockerInfo=(& docker info --format '{{json .}}' | Out-String | ConvertFrom-Json)
    $environment=[ordered]@{
        Windows=(Get-CimInstance Win32_OperatingSystem).Caption
        Cpu=@($cpu | Select-Object Name,NumberOfCores,NumberOfLogicalProcessors)
        RamBytes=$computer.TotalPhysicalMemory
        DockerCpu=$dockerInfo.NCPU;DockerMemoryBytes=$dockerInfo.MemTotal
        Java=$javaText;JMeter=$JMeter;Rounds=$Rounds;RampSeconds=$RampSeconds
        RequestsPerRun=500;JvmPorts=@(18080,18081)
        JvmArguments='-Xms256m -Xmx512m, root WARN, GC file log'
        AtomicCommit='d2c9aec979ec1aca7e23b4e935dcd2a0edd32007'
        RedissonCommit='6ebba4d65808e947bc67a0e39afacfec66e51443'
        Database='disposable atomic_analysis, MySQL 8.4, host port 13306'
        Redis='disposable Redis 7.4, host port 16379'
        PoolSampleIntervalMs=50;MySqlObserverDelayMs=250
    }
    [IO.File]::WriteAllText((Join-Path $out 'environment.json'),($environment | ConvertTo-Json -Depth 6),$utf8)
    $variants=@(
        @{Name='redisson';Sha='6ebba4d65808e947bc67a0e39afacfec66e51443'},
        @{Name='atomic';Sha='d2c9aec979ec1aca7e23b4e935dcd2a0edd32007'}
    )
    foreach($variant in $variants) {
        $source=Join-Path $scratch $variant.Name
        & git -C $root worktree add --detach $source $variant.Sha
        Check-Exit 'Create isolated source worktree'
        $worktrees+=@($source)
        $config=Join-Path $source 'backend/src/main/java/com/jiyun/classenrollment/common/config'
        Copy-Item (Join-Path $root 'analysis/LatencyDiagnostics.java') (Join-Path $config 'LatencyDiagnostics.java')
        Copy-Item (Join-Path $PSScriptRoot 'atomic-diagnostics/LatencyFlushController.java') (Join-Path $config 'LatencyFlushController.java')
        Write-Host "빌드: $($variant.Name)"
        Push-Location (Join-Path $source 'backend')
        try {
            & .\mvnw.cmd -B -DskipTests package 2>&1 | Tee-Object -FilePath (Join-Path $out "$($variant.Name)-build.log")
            Check-Exit 'Maven package'
        } finally { Pop-Location }
    }
    $containersCreated=$true
    & docker compose -p $project -f $compose up -d --wait --wait-timeout 180
    Check-Exit 'Create diagnostic MySQL and Redis'
    $script:mysqlContainer=(& docker compose -p $project -f $compose ps -q mysql | Out-String).Trim()
    if([string]::IsNullOrWhiteSpace($script:mysqlContainer)) { throw 'Missing diagnostic MySQL container' }
    $env:DB_URL='jdbc:mysql://localhost:13306/atomic_analysis?serverTimezone=UTC&characterEncoding=UTF-8'
    $env:DB_USERNAME='root';$env:DB_PASSWORD='analysis-test-password';$env:REDIS_ADDRESS='redis://localhost:16379'
    $data=Join-Path $out 'students.csv'
    $lines=@('studentId,courseId,port')
    for($i=0;$i -lt 500;$i++) { $lines+=('{0},1,{1}' -f (10000+$i),(18080+($i%2))) }
    [IO.File]::WriteAllLines($data,$lines,$utf8)
    [xml]$plan=Get-Content (Join-Path $PSScriptRoot 'atomic-diagnostics/500.jmx') -Raw -Encoding UTF8
    $plan.SelectSingleNode("//ThreadGroup/intProp[@name='ThreadGroup.ramp_time']").InnerText="$RampSeconds"
    $testPlan=Join-Path $out 'plan.jmx';$plan.Save($testPlan)
    for($round=1;$round -le $Rounds;$round++) {
        $cases=@(@{Variant='redisson';Pool=10},@{Variant='atomic';Pool=10},@{Variant='atomic';Pool=50})
        if($round%2 -eq 0) { [Array]::Reverse($cases) }
        foreach($case in $cases) {
            $name="$($case.Variant)-pool$($case.Pool)-r$round"
            $folder=Join-Path $out $name;New-Item -ItemType Directory $folder | Out-Null
            Write-Host "측정 준비: $name"
            foreach($port in 18080,18081) {
                $portFolder=Join-Path $folder "$port";New-Item -ItemType Directory $portFolder | Out-Null
                $jar=Join-Path $scratch "$($case.Variant)/backend/target/class-enrollment-backend-0.0.1-SNAPSHOT.jar"
                $javaArguments=@('-Xms256m','-Xmx512m','-Xlog:gc*:file=gc.log:time,uptime,level,tags','-jar',('"'+$jar+'"'),"--server.port=$port",'--server.address=127.0.0.1',"--spring.datasource.hikari.maximum-pool-size=$($case.Pool)","--spring.datasource.hikari.minimum-idle=$($case.Pool)",'--logging.level.root=WARN','--analysis.enabled=true',('--analysis.folder="'+$portFolder+'"'))
                $process=Start-Process java -ArgumentList $javaArguments -WorkingDirectory $portFolder -PassThru -RedirectStandardOutput (Join-Path $portFolder 'server.log') -RedirectStandardError (Join-Path $portFolder 'stderr.log')
                $script:servers+=@([pscustomobject]@{Process=$process;Port=$port})
            }
            Wait-Ready 18080;Wait-Ready 18081
            $values=@();for($i=0;$i -lt 500;$i++) { $values+=('({0},''ANA{1}'',''Analysis {1}'',''ACTIVE'',18)' -f (10000+$i),$i) }
            Sql ("INSERT INTO students(id,student_number,name,status,max_credits) VALUES "+($values -join ',')+" ON DUPLICATE KEY UPDATE status='ACTIVE',max_credits=18;") | Out-Null
            Reset-Course
            for($i=0;$i -lt 20;$i++) {
                $body='{"studentId":'+(10000+$i)+',"courseId":1}'
                Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:$(18080+($i%2))/api/enrollments" -ContentType 'application/json' -Body $body -TimeoutSec 30 | Out-Null
            }
            Reset-Course
            $mysqlEnvironment=Sql 'SELECT VERSION(),@@transaction_isolation,@@innodb_flush_log_at_trx_commit,@@sync_binlog;'
            [IO.File]::WriteAllLines((Join-Path $folder 'mysql-environment.tsv'),$mysqlEnvironment,$utf8)
            $script:stopFile=Join-Path $folder 'observer.stop'
            $script:monitor=Start-Job -ArgumentList $script:mysqlContainer,$folder,$script:stopFile -ScriptBlock {
                param($Container,$Folder,$StopFile)
                $ErrorActionPreference='Stop'
                $encoding=New-Object System.Text.UTF8Encoding($false)
                $path=Join-Path $Folder 'mysql-pool-waits.tsv'
                [IO.File]::WriteAllText($path,"epochMs`twaitingTransactions`twaitEdges`n",$encoding)
                while(-not (Test-Path $StopFile)) {
                    $query="SELECT UNIX_TIMESTAMP(NOW(3))*1000,COUNT(DISTINCT w.REQUESTING_ENGINE_TRANSACTION_ID),COUNT(*) FROM performance_schema.data_lock_waits w JOIN performance_schema.data_locks l ON l.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID WHERE l.OBJECT_SCHEMA='atomic_analysis';"
                    $answer=@($query | & docker exec -i $Container sh -c 'MYSQL_PWD=analysis-test-password mysql -uroot --batch --skip-column-names atomic_analysis' 2>&1)
                    if($LASTEXITCODE -ne 0) { throw "MySQL observer failed: $answer" }
                    [IO.File]::AppendAllText($path,($answer -join "`n")+"`n",$encoding)
                    $fields=("$($answer[0])").Split("`t")
                    if([int]$fields[1] -gt 0 -and -not (Test-Path (Join-Path $Folder 'first-lock-waits.tsv'))) {
                        $details="SELECT w.REQUESTING_ENGINE_TRANSACTION_ID,w.BLOCKING_ENGINE_TRANSACTION_ID,l.OBJECT_NAME,l.INDEX_NAME,l.LOCK_MODE,l.LOCK_DATA FROM performance_schema.data_lock_waits w JOIN performance_schema.data_locks l ON l.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID WHERE l.OBJECT_SCHEMA='atomic_analysis' LIMIT 20;"
                        $detail=@($details | & docker exec -i $Container sh -c 'MYSQL_PWD=analysis-test-password mysql -uroot --batch atomic_analysis' 2>&1)
                        if($LASTEXITCODE -ne 0) { throw 'MySQL wait details failed' }
                        [IO.File]::WriteAllLines((Join-Path $Folder 'first-lock-waits.tsv'),[string[]]$detail,$encoding)
                    }
                    Start-Sleep -Milliseconds 250
                }
            }
            & $JMeter -n -t $testPlan -l (Join-Path $folder 'http.jtl') -j (Join-Path $folder 'jmeter.log') "-JdataFile=$($data.Replace('\','/'))" "-JerrorFile=$((Join-Path $folder 'errors.xml').Replace('\','/'))" '-Jjmeter.save.saveservice.output_format=csv' '-Jjmeter.save.saveservice.print_field_names=true' '-Jjmeter.save.saveservice.timestamp_format=ms'
            Check-Exit 'JMeter load run'
            Stop-Monitor
            $state=@(Sql 'SELECT capacity,enrolled_count,(SELECT COUNT(*) FROM enrollments WHERE course_id=1) FROM courses WHERE id=1;')[0].Split("`t")
            $final=[ordered]@{capacity=[int]$state[0];enrolled_count=[int]$state[1];enrollment_count=[int]$state[2]}
            [IO.File]::WriteAllText((Join-Path $folder 'final-state.json'),($final | ConvertTo-Json),$utf8)
            $usage=@($script:servers | ForEach-Object { $_.Process.Refresh();[pscustomobject]@{Port=$_.Port;CpuMs=$_.Process.TotalProcessorTime.TotalMilliseconds;PeakWorkingSetBytes=$_.Process.PeakWorkingSet64} })
            [IO.File]::WriteAllText((Join-Path $folder 'jvm-resource-use.json'),(ConvertTo-Json -InputObject $usage),$utf8)
            Flush-Evidence
            Stop-Servers
            $record=Get-AtomicReport $folder $case.Variant $case.Pool $round $RampSeconds
            $summary+=@($record)
            [IO.File]::WriteAllText((Join-Path $out 'summary.json'),(ConvertTo-Json -InputObject $summary -Depth 10),$utf8)
            $record | Select-Object Variant,PoolPerJvm,Round,@{Name='AvgSeconds';Expression={[Math]::Round($_.Http.AvgMs/1000,3)}},@{Name='P95Seconds';Expression={[Math]::Round($_.Http.P95Ms/1000,3)}},@{Name='ConnectionWaitSeconds';Expression={[Math]::Round($_.Jdbc['acquire'].AvgMs/1000,3)}},@{Name='UpdateSeconds';Expression={[Math]::Round($_.Jdbc['COURSE_UPDATE'].AvgMs/1000,3)}},MaxPoolPendingPerJvm,MaxMySqlWaitingTransactions,Consistent | Format-List
            # Unexpected HTTP errors are diagnostic results; retain bodies and continue comparisons.
            if(-not $record.Consistent -or -not $record.ObservationComplete) { throw "Incomplete measurement; evidence kept: $folder" }
        }
    }
    Write-Host 'Windows 측정 완료. summary.json과 연결 대기 및 UPDATE 시간을 함께 확인하세요.'
} finally {
    if($null -ne $script:monitor) {
        try { Stop-Monitor } catch { Write-Warning $_.Exception.Message;Stop-Job $script:monitor -ErrorAction SilentlyContinue }
    }
    try { if($script:servers.Count -gt 0) { Flush-Evidence } } catch { Write-Warning $_.Exception.Message }
    Stop-Servers
    if($containersCreated) {
        & docker compose -p $project -f $compose logs --no-color 2>&1 | Out-File (Join-Path $out 'containers.log') -Encoding utf8
        & docker compose -p $project -f $compose down -v
    }
    foreach($source in $worktrees) { & git -C $root worktree remove --force $source }
    foreach($name in $savedEnv.Keys) { [Environment]::SetEnvironmentVariable($name,$savedEnv[$name],'Process') }
    if(Test-Path $out) {
        Compress-Archive -Path (Join-Path $out '*') -DestinationPath ($out+'.zip') -Force
        Write-Host "결과 파일: $out.zip"
    }
}
