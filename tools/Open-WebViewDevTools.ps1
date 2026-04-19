param(
    [string]$PackageName = "com.douyindownloader.android",
    [int]$LocalPort = 9222,
    [switch]$OpenBrowser,
    [string]$Serial
)

$ErrorActionPreference = "Stop"

function Resolve-TargetSerial {
    if ($Serial) {
        return $Serial
    }

    $devices = (& adb devices) -split "`r?`n" |
        Where-Object { $_ -match "device$" } |
        ForEach-Object { ($_ -split "\s+")[0] }

    $physical = $devices | Where-Object { $_ -notlike "emulator-*" } | Select-Object -First 1
    if ($physical) {
        return $physical
    }

    return $devices | Select-Object -First 1
}

$targetSerial = Resolve-TargetSerial
if (-not $targetSerial) {
    throw "No adb device is available."
}

$processIdRaw = & adb -s $targetSerial shell pidof -s $PackageName
$processId = ($processIdRaw | Out-String).Trim()
if (-not $processId) {
    throw "Process is not running: $PackageName on $targetSerial"
}

& adb -s $targetSerial forward --remove "tcp:$LocalPort" 2>$null | Out-Null
& adb -s $targetSerial forward "tcp:$LocalPort" "localabstract:webview_devtools_remote_$processId" | Out-Null

$url = "http://127.0.0.1:$LocalPort/json"
Write-Host "Target device: $targetSerial"
Write-Host "WebView DevTools list: $url"

if ($OpenBrowser) {
    Start-Process $url
}
