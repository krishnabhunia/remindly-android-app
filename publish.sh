#!/usr/bin/env bash
# Builds the single-file Windows executable from Linux/macOS/Windows (needs .NET 8 SDK).
set -euo pipefail
cd "$(dirname "$0")/.."
export DOTNET_CLI_TELEMETRY_OPTOUT=1 DOTNET_NOLOGO=1
dotnet test tests/Remindly.Core.Tests/Remindly.Core.Tests.csproj -c Release
python3 build/xaml_check.py   # BLOCKING: catches run-time-only XAML faults (bad Setter.Property, forward StaticResource, ...)
rm -rf publish
dotnet publish src/Remindly.App/Remindly.App.csproj -c Release -o publish
ls -la publish/Remindly.exe
sha256sum publish/Remindly.exe | tee publish/Remindly.exe.sha256
