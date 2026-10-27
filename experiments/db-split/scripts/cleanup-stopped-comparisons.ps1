$ErrorActionPreference='Stop'
$compose=Join-Path (Split-Path $PSScriptRoot -Parent) 'compose.compare.yml'
# Discover only projects created by this experiment. Do not prune globally.
$containerProjects=@(& docker ps -a --filter 'label=com.docker.compose.project' --format '{{.Labels}}')
if($LASTEXITCODE -ne 0){throw 'Cannot list Docker containers'}
$networkProjects=@(& docker network ls --filter 'label=com.docker.compose.project' --format '{{.Labels}}')
if($LASTEXITCODE -ne 0){throw 'Cannot list Docker networks'}
$projects=@(($containerProjects+$networkProjects) | ForEach-Object {
 foreach($label in ($_ -split ',')){
  if($label -match '^com\.docker\.compose\.project=(enrollment-compare-[0-9]{14}-[0-9a-f]{8})$'){ $Matches[1] }
 }
} | Sort-Object -Unique)
foreach($project in $projects){
 $running=@(& docker ps -q --filter "label=com.docker.compose.project=$project")
 if($LASTEXITCODE -ne 0){throw "Cannot check project: $project"}
 if($running.Count -gt 0){
  Write-Warning "Skipped running experiment: $project"
  continue
 }
 & docker compose -p $project -f $compose --profile single --profile split --profile redis down
 if($LASTEXITCODE -ne 0){throw "Cleanup failed: $project"}
}
Write-Host 'Stopped experiment containers/networks cleaned. Named volumes and result files preserved.'
