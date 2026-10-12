#!/bin/sh
set -eu
cd "$(dirname "$0")"
export CGO_ENABLED=0
go mod verify
go test ./...
go build -trimpath -o "${1:-bin/inkweft-backup}" .
