param([ValidateRange(1,10)][int]$Rounds=5,[string]$Jmeter='jmeter.bat')
$ErrorActionPreference='Stop'
. "$PSScriptRoot\comparison-common.ps1"
$suiteDir=Join-Path $experimentRoot ('results\suite-'+(Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Path $suiteDir -Force|Out-Null
$results=@();$modes=@('A','B0','B')
for($round=1;$round -le $Rounds;$round++){
 for($offset=0;$offset -lt 3;$offset++){
  $mode=$modes[($offset+$round-1)%3]
  Write-Host "Round $round : $mode"
  & "$PSScriptRoot\start-comparison.ps1" -Mode $mode -Round $round
  & "$PSScriptRoot\run-comparison.ps1" -Jmeter $Jmeter -Warmup
  & "$PSScriptRoot\run-comparison.ps1" -Jmeter $Jmeter
  $context=Read-ComparisonContext
  $measured=Get-ChildItem $context.directory -Directory|Where-Object Name -Like '*-measured-*'|Select-Object -First 1
  $summary=Get-Content -Encoding UTF8 -Raw (Join-Path $measured.FullName 'summary.json')|ConvertFrom-Json
  $conditions=Get-Content -Encoding UTF8 -Raw (Join-Path $measured.FullName 'conditions.json')|ConvertFrom-Json
  $results+=@{mode=$mode;round=$round;directory=$measured.FullName;summary=$summary;conditions=$conditions}
  Write-ComparisonJson "$suiteDir\comparison.json" @{results=$results;plannedRounds=$Rounds;orderPolicy='rotate A/B0/B; include every run, including target shortfall'}
  if(!$summary.integrityValid -or !$summary.responseCountMatchesDatabase -or !$summary.outcomeCountValid -or $summary.executionErrors -gt 0 -or !$summary.fixedDatasetValid){throw "Execution/integrity problem. Results preserved at $suiteDir. Inspect before continuing; this session remains running."}
  & "$PSScriptRoot\stop-comparison.ps1"
 }
}
Write-Host "Comparison complete: $suiteDir/comparison.json"
