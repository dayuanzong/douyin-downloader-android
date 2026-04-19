param(
    [string]$SdkRoot = "$env:LOCALAPPDATA\Android\Sdk",
    [string]$AvdName
)

$ErrorActionPreference = "Stop"

$emulator = Join-Path $SdkRoot "emulator\emulator.exe"
if (-not (Test-Path $emulator)) {
    throw "emulator.exe not found: $emulator"
}

$avds = & $emulator -list-avds
if (-not $AvdName) {
    $AvdName = $avds | Select-Object -First 1
}

if (-not $AvdName) {
    throw "No AVD is available."
}

Write-Host "Starting emulator: $AvdName"
Start-Process -FilePath $emulator -ArgumentList @("-avd", $AvdName)
