param(
  [switch]$Volumes
)

$ErrorActionPreference = "Stop"

if ($Volumes) {
  docker compose --profile demo --profile infra down -v
} else {
  docker compose --profile demo --profile infra down
}
