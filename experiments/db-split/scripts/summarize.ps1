param([Parameter(Mandatory=$true)][string]$Directory)
$ErrorActionPreference='Stop'
function Stats($values){$v=@($values|Sort-Object);if(!$v.Count){return @{count=0;p95Ms=$null;meanMs=$null;maxMs=$null}};return @{count=$v.Count;meanMs=[math]::Round(($v|Measure-Object -Average).Average,2);p95Ms=$v[[math]::Ceiling(.95*$v.Count)-1];maxMs=$v[-1]}}
$readRows=@();$journeys=@();$outcomes=@{};$steps=@()
foreach($part in @('baseline','mixed')){
 [xml]$xml=Get-Content -Encoding UTF8 -Raw "$Directory\$part.jtl"
 $samples=@($xml.SelectNodes('/testResults/*'))
 foreach($label in @('academic-28080','academic-28081','offering-28080','offering-28081')){
  $rows=@($samples|Where-Object {$_.GetAttribute('lb') -eq $label})
  $readRows+=@{phase=$part;label=$label;timing=(Stats @($rows|ForEach-Object {[double]$_.GetAttribute('t')}));failed=@($rows|Where-Object {$_.GetAttribute('s') -ne 'true'}).Count;actualRps=[math]::Round($rows.Count/90,2);targetRps=25;targetCount=2250;targetShortfallCount=[math]::Max(0,2250-$rows.Count)}
 }
 if($part -eq 'mixed'){
  foreach($sample in @($samples|Where-Object {$_.GetAttribute('lb') -eq 'journey'})){
   $data=$sample.responseData.'#text'
   if(!$data){$data=[string]$sample.responseData}
   try{$r=$data|ConvertFrom-Json}catch{throw 'Missing/invalid journey response data; preserve JTL and check JMeter log'}
   $outcomes[$r.outcome]++;$steps+=@($r.requests)
   $journeys+=@{studentId=$r.studentId;outcome=$r.outcome;elapsedMs=[double]$sample.GetAttribute('t');requests=$r.requests}
  }
 }
}
$db=Get-Content -Raw "$Directory\database.json"|ConvertFrom-Json
$before=Get-Content -Raw "$Directory\mixed-before.json"|ConvertFrom-Json
$after=Get-Content -Raw "$Directory\mixed-after.json"|ConvertFrom-Json
$pool=@()
if(Test-Path "$Directory\mixed-pool.json"){
 foreach($p in (Get-Content -Raw "$Directory\mixed-pool.json"|ConvertFrom-Json)){
  foreach($group in @($p.samples|Group-Object pool)){
   $pool+=@{server=$p.server;name=$(if($group.Name){$group.Name}else{'sharedPool'});samples=@($group.Group).Count;maxWaiting=($group.Group.waiting|Measure-Object -Maximum).Maximum;maxActive=($group.Group.active|Measure-Object -Maximum).Maximum}
  }
 }
}
$metrics=@()
for($i=0;$i -lt 2;$i++){
 foreach($property in $after[$i].metrics.phases.PSObject.Properties){
  $b=$before[$i].metrics.phases.($property.Name);$a=$property.Value
  $count=$a.count-$b.count;$total=$a.totalMs-$b.totalMs
  $metrics+=@{server=28080+$i;phase=$property.Name;count=$count;failures=$a.failures-$b.failures;totalMs=$total;meanMs=$(if($count){$total/$count}else{$null});lifetimeMaxMs=$a.lifetimeMaxMs}
 }
}
$integrityValid=$db.counterMismatches -eq 0 -and $db.creditViolations -eq 0 -and $db.scheduleViolations -eq 0 -and $db.studentsWithMultipleEnrollments -eq 0 -and $db.confirmed -eq $outcomes.CONFIRMED
$outcomeCountValid=$journeys.Count -eq 500 -and @($journeys.studentId|Sort-Object -Unique).Count -eq 500
$executionErrors=@($journeys|Where-Object {$_.outcome -in @('READ_FAILED','SUBMIT_UNKNOWN','SUBMIT_FAILED','DEADLINE')}).Count+($readRows.failed|Measure-Object -Sum).Sum
$offeredLoadMet=!@($readRows|Where-Object {$_.actualRps -lt 24}).Count
$conditions=$null
if(Test-Path "$Directory\conditions.json"){$conditions=Get-Content -Encoding UTF8 -Raw "$Directory\conditions.json"|ConvertFrom-Json}
$fixedDatasetValid=$null
if($conditions.version -eq 'db-split-v2'){$fixedDatasetValid=$db.totalCatalogRows -eq 10000 -and $db.totalSubjectRows -eq 10000 -and $db.totalStudentRows -eq 500}
$valid=$integrityValid -and $outcomeCountValid -and $executionErrors -eq 0 -and $offeredLoadMet -and $fixedDatasetValid -ne $false
$result=@{version='db-split-v2';mode=$conditions.mode;round=$conditions.round;session=$conditions.session;revision=$conditions.revision;warmup=$conditions.warmup;valid=$valid;integrityValid=$integrityValid;outcomeCountValid=$outcomeCountValid;executionErrors=$executionErrors;offeredLoadMet=$offeredLoadMet;fixedDatasetValid=$fixedDatasetValid;outcomes=$outcomes;successRate=$outcomes.CONFIRMED/500;successfulJourney=(Stats @($journeys|Where-Object {$_.outcome -eq 'CONFIRMED'}|ForEach-Object {$_.elapsedMs}));allJourney=(Stats @($journeys|ForEach-Object {$_.elapsedMs}));journeyRead=(Stats @($steps|Where-Object {$_.kind -eq 'journeyRead'}|ForEach-Object {$_.elapsedMs}));submit=(Stats @($steps|Where-Object {$_.kind -eq 'submit'}|ForEach-Object {$_.elapsedMs}));reads=$readRows;pool=$pool;metrics=$metrics;database=$db;measurementNote='P95: observed samples only. Background threads are rate-paced closed loop, not guaranteed offered RPS. Lifetime maxima include warmup. No instrumentation-overhead equivalence assumed.'}
$result|ConvertTo-Json -Depth 25|Set-Content -Encoding UTF8 "$Directory\summary.json"
$journeys|ConvertTo-Json -Depth 20|Set-Content -Encoding UTF8 "$Directory\journeys.json"
$outcomes|Format-Table
$readRows|ForEach-Object {[pscustomobject]@{phase=$_.phase;label=$_.label;p95Ms=$_.timing.p95Ms;failed=$_.failed;actualRps=$_.actualRps;targetShortfall=$_.targetShortfallCount}}|Format-Table
Write-Host ('Successful journey: '+($result.successfulJourney|ConvertTo-Json -Compress))
if(!$valid){Write-Warning 'Errors, undelivered load or inconsistent outcome. Preserve results; do not increase load.'}
