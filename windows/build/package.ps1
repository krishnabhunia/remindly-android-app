# Local end-to-end build of Remindly for Windows — the same steps as .github/workflows/windows.yml:
# unit tests → single-file exe → UI smoke test → installer → Remindly-Windows-<version>.zip (installer/ + portable/).
# Needs the .NET 8 SDK; the installer step needs Inno Setup 6 (winget install JRSoftware.InnoSetup).
$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")
$env:DOTNET_CLI_TELEMETRY_OPTOUT = "1"
$v = ([xml](Get-Content Directory.Build.props)).Project.PropertyGroup.Version
Write-Host "Remindly for Windows $v"

dotnet test tests/Remindly.Core.Tests/Remindly.Core.Tests.csproj -c Release
if ($LASTEXITCODE -ne 0) { throw "unit tests failed" }

Remove-Item out -Recurse -Force -ErrorAction SilentlyContinue
dotnet publish src/Remindly.App/Remindly.App.csproj -c Release -o out/portable
if ($LASTEXITCODE -ne 0) { throw "publish failed" }
Get-ChildItem out/portable | Where-Object { $_.Name -ne 'Remindly.exe' } | Remove-Item -Force -Recurse

$p = Start-Process out/portable/Remindly.exe -ArgumentList @('--smoke-test', "$PWD\out\smoke", '--data-dir', "$PWD\out\smoke-data") -PassThru -Wait
Get-Content out/smoke/smoke-result.txt
if ($p.ExitCode -ne 0) { throw "smoke test failed" }

$iscc = "${env:ProgramFiles(x86)}\Inno Setup 6\ISCC.exe"
if (-not (Test-Path $iscc)) { $iscc = "$env:LOCALAPPDATA\Programs\Inno Setup 6\ISCC.exe" }
if (-not (Test-Path $iscc)) { throw "Inno Setup 6 not found — winget install JRSoftware.InnoSetup" }
& $iscc "/DMyAppVersion=$v" "/DSourceDir=$PWD\out\portable" "/DOutputDir=$PWD\out\installer" installer\Remindly.iss
if ($LASTEXITCODE -ne 0) { throw "ISCC failed" }

New-Item -ItemType Directory out/zip/installer, out/zip/portable, out/dist -Force | Out-Null
Copy-Item "out/installer/Remindly-Setup-$v.exe" out/zip/installer/
Copy-Item out/portable/Remindly.exe out/zip/portable/
$zip = "out/dist/Remindly-Windows-$v.zip"
Compress-Archive -Path out/zip/installer, out/zip/portable -DestinationPath $zip -Force
$sha = (Get-FileHash $zip -Algorithm SHA256).Hash.ToLower()
"$sha  Remindly-Windows-$v.zip" | Out-File "$zip.sha256" -Encoding ascii -NoNewline
Write-Host "Built $zip  sha256 $sha"
