param(
  [ValidateSet('quick', 'up', 'down', 'status', 'verify', 'accept')]
  [string]$Mode = 'status',
  [int]$Cycles = 2,
  [int]$HoldSeconds = 30
)
$ErrorActionPreference = 'Stop'
& python (Join-Path $PSScriptRoot 'local.py') $Mode --cycles $Cycles --hold-seconds $HoldSeconds
exit $LASTEXITCODE
