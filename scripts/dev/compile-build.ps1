param(
  [string]$JavaHome = "C:\Program Files\Eclipse Adoptium\jdk-21.0.7.6-hotspot"
)

$ErrorActionPreference = "Stop"

if (Test-Path $JavaHome) {
  $env:JAVA_HOME = $JavaHome
  $env:Path = "$env:JAVA_HOME\bin;$env:Path"
}

Write-Host "Compiling backend with tests skipped..."
.\mvnw.cmd -B -DskipTests compile

Write-Host "Building frontend..."
Push-Location web
try {
  npm run build
} finally {
  Pop-Location
}
