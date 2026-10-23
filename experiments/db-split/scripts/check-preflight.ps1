# CI-only checks of configuration validation. No servers are started or stopped.
$ErrorActionPreference='Stop'
. "$PSScriptRoot\comparison-common.ps1"
foreach($mode in @('A','B0','B')){
 $context=@{mode=$mode;revision='test';session='test'}
 $names=if($mode -eq 'A'){@('sharedPool')}else{@('academicPool','enrollmentPool')}
 $academicUuid=if($mode -eq 'B'){'second'}else{'first'}
 $state=@{version='db-split-v2';mode=$mode;session='test';revision='test';datasetPolicy='fixed';strategy='DB_ONLY';isolation='TRANSACTION_READ_COMMITTED';metrics=@{enabled=$true};pools=@($names|ForEach-Object {@{name=$_;max=10;connectionTimeoutMs=10000;active=0;waiting=0}});databases=@{enrollment=@{serverUuid='first'};academic=@{serverUuid=$academicUuid}}}|ConvertTo-Json -Depth 10|ConvertFrom-Json
 Assert-ComparisonStates $context @($state,$state)
 $state.pools[0].max=11
 $rejected=$false
 try{Assert-ComparisonStates $context @($state,$state)}catch{$rejected=$true}
 if(!$rejected){throw 'Preflight accepted an incorrect pool budget'}
}
Write-Host 'Preflight accepted A/B0/B and rejected invalid budgets.'
