param([string]$Output='bin/inkweft-backup.exe')
$ErrorActionPreference='Stop'
Push-Location $PSScriptRoot
try {
    $env:CGO_ENABLED='0'
    go mod verify
    if($LASTEXITCODE -ne 0){throw 'Go module verification failed'}
    go test ./...
    if($LASTEXITCODE -ne 0){throw 'Go tests failed'}
    go build -trimpath -o $Output .
    if($LASTEXITCODE -ne 0){throw 'Go build failed'}
} finally {Pop-Location}
