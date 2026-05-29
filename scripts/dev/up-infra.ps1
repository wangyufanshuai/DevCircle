$ErrorActionPreference = "Stop"
docker compose --profile infra up -d
.\deploy\kafka\create-topics.ps1
docker compose --profile infra ps
