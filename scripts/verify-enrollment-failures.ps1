$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path $PSScriptRoot -Parent
Push-Location (Join-Path $repoRoot 'backend')
try {
    & docker info --format '{{.ServerVersion}}'
    if ($LASTEXITCODE -ne 0) { throw 'Docker Desktop을 실행한 뒤 다시 실행해 주세요. 테스트는 별도 MySQL/Redis를 생성합니다.' }
    $tests = 'EnrollmentQueryControllerTest,EnrollmentServiceTest,EnrollmentWriteSafetyTest,EnrollmentPessimisticFacadeTest,EnrollmentRedissonFacadeTest,DatabaseOnlyFacadeTest,GatedCapacityPrecheckTest,EnrollmentPessimisticIntegrationTest#rollbackUndoesInsertAndCounterAndReleasesRowLock+retryAfterCommittedResponseIsDiscardedDoesNotInsertTwice+expiredStudentLeaseCannotCommitStaleValidation+killedJvmRollsBackOnlyBeforeCommitAndReleasesStudentLease+retainedStudentLockPreventsConcurrentCreditOverflow+independentClientsWithLocalGatePreserveCapacity+realDatabaseTimeoutReturns503AndNextHttpRequestCanSucceed'
    & .\mvnw.cmd "-Dtest=$tests" test
    if ($LASTEXITCODE -ne 0) { throw '검증 실패: backend/target/surefire-reports와 backend/target/failure-probe-logs를 확인해 주세요.' }
    Write-Host '검증 통과. 실제 결과는 backend/target/surefire-reports에 저장됐습니다.'
} finally {
    Pop-Location
}
