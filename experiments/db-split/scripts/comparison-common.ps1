$experimentRoot=Split-Path $PSScriptRoot -Parent
$experimentRepo=Resolve-Path (Join-Path $experimentRoot '..\..')
$contextPath=Join-Path $experimentRoot 'results\current-comparison.json'
function Read-ComparisonContext {
 if(!(Test-Path $contextPath)){throw 'Run start-comparison.ps1 first'}
 Get-Content -Encoding UTF8 -Raw $contextPath | ConvertFrom-Json
}
function Write-ComparisonJson($path,$value){
 $value|ConvertTo-Json -Depth 40|Set-Content -Encoding UTF8 $path
}
function Get-ComparisonStates {
 @(28080,28081)|ForEach-Object {Invoke-RestMethod "http://127.0.0.1:$_/experiment/state" -TimeoutSec 15}
}
function Assert-ComparisonStates($context,$states){
 foreach($s in $states){
  if($s.version -ne 'db-split-v2' -or $s.mode -ne $context.mode -or $s.session -ne $context.session -or $s.revision -ne $context.revision -or $s.datasetPolicy -ne 'fixed' -or $s.strategy -ne 'DB_ONLY' -or $s.isolation -ne 'TRANSACTION_READ_COMMITTED'){throw 'Server configuration mismatch'}
  $expected=if($context.mode -eq 'A'){@('sharedPool')}else{@('academicPool','enrollmentPool')}
  if((($s.pools.name|Sort-Object) -join ',') -ne (($expected|Sort-Object) -join ',')){throw 'Pool layout mismatch'}
  foreach($p in $s.pools){if($p.max -ne 10 -or $p.connectionTimeoutMs -ne 10000){throw 'Pool budget mismatch'}}
  $same=$s.databases.academic.serverUuid -eq $s.databases.enrollment.serverUuid
  if(($context.mode -eq 'B' -and $same) -or ($context.mode -ne 'B' -and !$same)){throw 'Physical database layout mismatch'}
 }
 if($states.Count -ne 2 -or $states[0].metrics.enabled -ne $states[1].metrics.enabled){throw 'Both servers must use the same metrics setting'}
 foreach($domain in @('academic','enrollment')){
  if($states[0].databases.$domain.serverUuid -ne $states[1].databases.$domain.serverUuid){throw 'Servers are connected to different datasets'}
 }
}
function Wait-ComparisonIdle($context){
 $consecutive=0
 for($i=0;$i -lt 30;$i++){
  $states=@(Get-ComparisonStates);Assert-ComparisonStates $context $states
  if(@($states.pools|Where-Object {$_.active -gt 0 -or $_.waiting -gt 0}).Count -eq 0){$consecutive++}else{$consecutive=0}
  if($consecutive -ge 3){return}
  Start-Sleep -Seconds 1
 }
 throw 'Servers are still busy. Preserve the results and wait before resetting the comparison dataset.'
}
