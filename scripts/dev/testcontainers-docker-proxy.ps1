param(
    [switch] $Stop
)

$name = "devcircle-docker-socket-proxy"

if ($Stop) {
    docker rm -f $name 2>$null | Out-Null
    Write-Host "Stopped $name"
    exit 0
}

docker rm -f $name 2>$null | Out-Null
docker run -d `
    --name $name `
    -p 127.0.0.1:2375:2375 `
    -v /var/run/docker.sock:/var/run/docker.sock `
    alpine/socat tcp-listen:2375,fork,reuseaddr unix-connect:/var/run/docker.sock | Out-Null

$ready = $false
for ($i = 0; $i -lt 20; $i++) {
    $probe = Test-NetConnection 127.0.0.1 -Port 2375 -WarningAction SilentlyContinue
    if ($probe.TcpTestSucceeded) {
        $ready = $true
        break
    }
    Start-Sleep -Milliseconds 500
}

if (-not $ready) {
    throw "Docker socket proxy did not become ready on 127.0.0.1:2375"
}

Write-Host "Docker socket proxy is ready on tcp://127.0.0.1:2375"
Write-Host 'Run tests with: $env:DOCKER_HOST="tcp://127.0.0.1:2375"; .\mvnw.cmd -B test'
