# Builds the single-file Windows executable on Windows (needs .NET 8 SDK).
$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")
$env:DOTNET_CLI_TELEMETRY_OPTOUT = "1"; $env:DOTNET_NOLOGO = "1"
dotnet test tests/Remindly.Core.Tests/Remindly.Core.Tests.csproj -c Release
# BLOCKING static XAML check (needs python3): catches run-time-only XAML faults such as a Setter for a non-DependencyProperty.
python3 build/xaml_check.py
if ($LASTEXITCODE -ne 0) { throw "xaml_check.py reported problems - fix them before publishing" }
if (Test-Path publish) { Remove-Item publish -Recurse -Force }
dotnet publish src/Remindly.App/Remindly.App.csproj -c Release -o publish
Get-FileHash publish/Remindly.exe -Algorithm SHA256 | Format-List
