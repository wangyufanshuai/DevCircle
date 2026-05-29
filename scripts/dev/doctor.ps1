param(
  [string]$JavaHome = "C:\Program Files\Eclipse Adoptium\jdk-21.0.7.6-hotspot"
)

$ErrorActionPreference = "Stop"

function Show-CommandVersion {
  param([string]$Name, [string]$VersionArg = "--version")
  $command = Get-Command $Name -ErrorAction SilentlyContinue
  if (-not $command) {
    Write-Host "[missing] $Name"
    return
  }
  $version = & $Name $VersionArg 2>$null | Select-Object -First 1
  Write-Host "[ok] $Name - $version"
}

Write-Host "DevCircle local doctor"
Write-Host "Workspace: $(Resolve-Path .)"

if (Test-Path $JavaHome) {
  $env:JAVA_HOME = $JavaHome
  $env:Path = "$env:JAVA_HOME\bin;$env:Path"
}

Show-CommandVersion "java" "-version"
Show-CommandVersion "docker" "--version"
Show-CommandVersion "git" "--version"
Show-CommandVersion "node" "--version"
Show-CommandVersion "npm" "--version"

if (Test-Path ".env") {
  Write-Host "[ok] .env exists"
} else {
  Write-Host "[warn] .env is missing; copy .env.example if you need local overrides"
}

docker info *> $null
Write-Host "[ok] Docker engine is reachable"

docker compose --profile infra config *> $null
Write-Host "[ok] docker compose infra config is valid"

docker compose --profile demo config *> $null
Write-Host "[ok] docker compose demo config is valid"
