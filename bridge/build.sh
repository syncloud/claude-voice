#!/bin/sh
# Build the Termux-side Go bridge (android/arm64 static binary).
# Output: ../claude-voice-bridge-arm64 (repo root). Runnable locally or in CI.
set -eu
cd "$(dirname "$0")"
go vet ./...
CGO_ENABLED=0 GOOS=android GOARCH=arm64 go build -ldflags="-s -w" -o ../claude-voice-bridge-arm64 ./cmd/claude-voice-bridge
echo "built claude-voice-bridge-arm64"
