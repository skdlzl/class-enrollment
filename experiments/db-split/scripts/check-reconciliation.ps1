param([Parameter(Mandatory=$true)][string]$Directory)
$ErrorActionPreference='Stop'
$original=Get-Content -Raw -Encoding UTF8 "$Directory/mixed.jtl"
try{
 [xml]$xml=$original
 $sample=@($xml.SelectNodes('/testResults/*')|Where-Object {$_.GetAttribute('lb') -eq 'journey'})[0]
 $node=$sample.SelectSingleNode('responseData')
 $data=$node.InnerText|ConvertFrom-Json
 $data.outcome='SUBMIT_UNKNOWN'
 $submit=@($data.requests|Where-Object {$_.kind -eq 'submit'})[-1]
 $submit.status=0
 $node.InnerText=($data|ConvertTo-Json -Depth 20 -Compress)
 $xml.Save("$Directory/mixed.jtl")
 $record=@{request_id=$submit.requestId;student_id=$data.studentId;course_id=1;status='CONFIRMED'}
 ConvertTo-Json -InputObject @($record) | Set-Content -Encoding UTF8 "$Directory/enrollment-records.json"
 & "$PSScriptRoot/summarize.ps1" -Directory $Directory
 $s=Get-Content -Raw "$Directory/summary.json"|ConvertFrom-Json
 if($s.outcomes.CONFIRMED -ne 1 -or $s.outcomes.SUBMIT_UNKNOWN -ne 1 -or $s.successfulJourney.count -ne 1 -or $s.reconciliation.confirmedAfterUnknown -ne 1){throw 'Reconciliation must not rewrite raw outcomes or successful latency'}
 '[]'|Set-Content -Encoding UTF8 "$Directory/enrollment-records.json"
 & "$PSScriptRoot/summarize.ps1" -Directory $Directory
 $s=Get-Content -Raw "$Directory/summary.json"|ConvertFrom-Json
 if($s.reconciliation.confirmedAfterUnknown -ne 0 -or $s.reconciliation.unknownWithoutStoredRecord -ne 1){throw 'Missing record must remain explicitly unconfirmed'}
}finally{
 $original|Set-Content -Encoding UTF8 "$Directory/mixed.jtl"
 Remove-Item "$Directory/enrollment-records.json" -ErrorAction SilentlyContinue
}
Write-Host 'Reconciliation preserves raw outcomes and distinguishes stored/unstored unknown requests.'
