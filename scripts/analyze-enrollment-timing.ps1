param(
    [string]$LogPath = ".\backend\logs\server-*.log",
    [string]$CsvPath = ".\jmeter\results\enrollment-timing-summary.csv"
)

$timingPattern = 'ENROLLMENT_TIMING outcome=(?<outcome>\S+) studentId=(?<studentId>\d+) courseId=(?<courseId>\d+) studentLockWaitMs=(?<studentWait>\d+) courseLockWaitMs=(?<courseWait>\d+) serviceMs=(?<service>\d+) facadeTotalMs=(?<total>\d+)'

$rows = Get-Content -Path $LogPath |
    ForEach-Object {
        if ($_ -match $timingPattern) {
            [PSCustomObject]@{
                Outcome           = $Matches.outcome
                StudentId         = [long]$Matches.studentId
                CourseId          = [long]$Matches.courseId
                StudentLockWaitMs = [long]$Matches.studentWait
                CourseLockWaitMs  = [long]$Matches.courseWait
                ServiceMs         = [long]$Matches.service
                FacadeTotalMs     = [long]$Matches.total
            }
        }
    }

if (-not $rows) {
    throw "ENROLLMENT_TIMING 로그를 찾지 못했습니다. 서버 로그 경로와 실행 버전을 확인해 주세요."
}

function Get-Percentile95 {
    param([long[]]$Values)

    $sorted = $Values | Sort-Object
    $index = [Math]::Ceiling($sorted.Count * 0.95) - 1
    return $sorted[[Math]::Max(0, $index)]
}

function New-SummaryRow {
    param(
        [string]$Outcome,
        [object[]]$Items
    )

    $studentWait = @($Items.StudentLockWaitMs)
    $courseWait = @($Items.CourseLockWaitMs)
    $service = @($Items.ServiceMs)
    $total = @($Items.FacadeTotalMs)

    [PSCustomObject]@{
        Outcome              = $Outcome
        Count                = $Items.Count
        StudentWaitAvgMs     = [Math]::Round(($studentWait | Measure-Object -Average).Average, 2)
        StudentWaitP95Ms     = Get-Percentile95 $studentWait
        StudentWaitMaxMs     = ($studentWait | Measure-Object -Maximum).Maximum
        CourseWaitAvgMs      = [Math]::Round(($courseWait | Measure-Object -Average).Average, 2)
        CourseWaitP95Ms      = Get-Percentile95 $courseWait
        CourseWaitMaxMs      = ($courseWait | Measure-Object -Maximum).Maximum
        ServiceAvgMs         = [Math]::Round(($service | Measure-Object -Average).Average, 2)
        ServiceP95Ms         = Get-Percentile95 $service
        ServiceMaxMs         = ($service | Measure-Object -Maximum).Maximum
        FacadeTotalAvgMs     = [Math]::Round(($total | Measure-Object -Average).Average, 2)
        FacadeTotalP95Ms     = Get-Percentile95 $total
        FacadeTotalMaxMs     = ($total | Measure-Object -Maximum).Maximum
    }
}

$summary = @(
    New-SummaryRow -Outcome "TOTAL" -Items @($rows)

    $rows |
        Group-Object Outcome |
        Sort-Object Name |
        ForEach-Object {
            New-SummaryRow -Outcome $_.Name -Items @($_.Group)
        }
)

$parentDirectory = Split-Path -Parent $CsvPath

if ($parentDirectory -and -not (Test-Path $parentDirectory)) {
    New-Item -ItemType Directory -Path $parentDirectory | Out-Null
}

$summary | Export-Csv -Path $CsvPath -NoTypeInformation -Encoding utf8
$summary | Format-Table -AutoSize

Write-Host ""
Write-Host "요약 CSV 저장 위치: $CsvPath"
