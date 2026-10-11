function Get-AnalysisStats($Values) {
    $numbers = @($Values | ForEach-Object { [double]$_ } | Sort-Object)
    if ($numbers.Count -eq 0) { return [ordered]@{Count=0;AvgMs=0;P95Ms=0;MaxMs=0} }
    return [ordered]@{
        Count=$numbers.Count
        AvgMs=[Math]::Round(($numbers | Measure-Object -Average).Average,3)
        P95Ms=$numbers[[Math]::Ceiling($numbers.Count*0.95)-1]
        MaxMs=$numbers[-1]
    }
}
function Get-AnalysisNumber([string]$Value) {
    return [double]::Parse($Value,[Globalization.CultureInfo]::InvariantCulture)
}
function Get-AtomicReport([string]$Folder, [string]$Variant, [int]$Pool, [int]$Round, [int]$Ramp) {
    $rows = @(Import-Csv (Join-Path $Folder 'http.jtl') | Where-Object { $_.label -eq 'Enrollment' })
    if ($rows.Count -ne 500) { throw "Expected 500 HTTP results, found $($rows.Count): $Folder" }
    $first = ($rows | ForEach-Object { [long]$_.timeStamp } | Measure-Object -Minimum).Minimum
    $last = ($rows | ForEach-Object { [long]$_.timeStamp + [long]$_.elapsed } | Measure-Object -Maximum).Maximum
    $events=@(); $pools=@()
    foreach ($port in 18080,18081) {
        $events += @(Import-Csv (Join-Path $Folder "$port/jdbc.csv") | Where-Object {
            [long]$_.epochMs -ge $first -and [long]$_.epochMs -le $last
        })
        $pools += @(Import-Csv (Join-Path $Folder "$port/pool.csv") | Where-Object {
            [long]$_.epochMs -ge $first -and [long]$_.epochMs -le $last
        })
    }
    $codes=@{}; $byCode=@{}; $jdbc=@{}
    foreach ($group in ($rows | Group-Object responseCode)) {
        $codes[$group.Name]=$group.Count
        $byCode[$group.Name]=Get-AnalysisStats @($group.Group | ForEach-Object { Get-AnalysisNumber $_.elapsed })
    }
    foreach ($group in ($events | Group-Object { if ($_.phase -eq 'sql') { $_.sqlKind } else { $_.phase } })) {
        $jdbc[$group.Name]=Get-AnalysisStats @($group.Group | ForEach-Object { Get-AnalysisNumber $_.durationMs })
    }
    $pending=($pools | ForEach-Object { [int]$_.pending } | Measure-Object -Maximum).Maximum
    $active=($pools | ForEach-Object { [int]$_.active } | Measure-Object -Maximum).Maximum
    $snapshots=@(Import-Csv (Join-Path $Folder 'mysql-pool-waits.tsv') -Delimiter "`t" | Where-Object {
        (Get-AnalysisNumber $_.epochMs) -ge $first -and (Get-AnalysisNumber $_.epochMs) -le $last
    })
    $waiting=($snapshots | ForEach-Object { [int]$_.waitingTransactions } | Measure-Object -Maximum).Maximum
    $state=Get-Content (Join-Path $Folder 'final-state.json') -Raw | ConvertFrom-Json
    $success=0; if($codes.ContainsKey('201')) { $success=$codes['201'] }
    $consistent=($state.enrolled_count -eq $state.enrollment_count -and $state.enrollment_count -eq $success -and $success -le 100)
    $validCodes=@($codes.Keys | Where-Object { $_ -notin @('201','409','503') }).Count -eq 0
    return [pscustomobject]@{
        Variant=$Variant;PoolPerJvm=$Pool;Round=$Round;RampSeconds=$Ramp
        Http=(Get-AnalysisStats @($rows | ForEach-Object { Get-AnalysisNumber $_.elapsed }))
        Responses=$codes;ByCode=$byCode;Jdbc=$jdbc
        MaxPoolPendingPerJvm=$pending;MaxPoolActivePerJvm=$active
        MaxMySqlWaitingTransactions=$waiting
        FinalState=$state;Consistent=$consistent;ValidCodes=$validCodes
        ObservationComplete=($pools.Count -gt 0 -and $snapshots.Count -gt 0 -and $jdbc.ContainsKey('acquire'))
    }
}
