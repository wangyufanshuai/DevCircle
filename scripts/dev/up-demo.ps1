param(
  [switch]$Build
)

$ErrorActionPreference = "Stop"

if ($Build) {
  docker compose --profile demo up -d --build
} else {
  docker compose --profile demo up -d
}

.\deploy\kafka\create-topics.ps1
docker compose --profile demo ps
