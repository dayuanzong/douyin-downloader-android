param(
    [string]$PackageName = "com.douyindownloader.android",
    [string]$ShareText = "3.53 我们团结一心，用我们血肉修成新的长城  https://v.douyin.com/JhfsWTHdyBQ/ 复制此链接，打开抖音搜索，直接观看视频！ U@L.Ji goq:/ 10/17",
    [string]$ShareTextFile,
    [int]$WaitSeconds = 40,
    [string]$OutputRoot = ".\debug-captures",
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

function Get-Timestamp {
    Get-Date -Format "yyyyMMdd-HHmmss"
}

function Save-UiDump {
    param([string]$DestinationPath)
    & adb -s $script:TargetSerial shell uiautomator dump /sdcard/window_dump.xml | Out-Null
    $content = & adb -s $script:TargetSerial exec-out cat /sdcard/window_dump.xml
    Set-Content -Path $DestinationPath -Value $content -Encoding UTF8
}

function Get-NodeCenter {
    param(
        [string]$XmlPath,
        [string]$ResourceId
    )

    [xml]$xml = Get-Content -Path $XmlPath -Raw
    $node = Select-Xml -Xml $xml -XPath "//node[@resource-id='$ResourceId']" | Select-Object -First 1
    if (-not $node) {
        throw "Missing UI node: $ResourceId"
    }

    $bounds = $node.Node.bounds
    if ($bounds -notmatch '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
        throw "Invalid bounds for: $ResourceId"
    }

    $x = [int](($matches[1] + $matches[3]) / 2)
    $y = [int](($matches[2] + $matches[4]) / 2)
    return @{ X = $x; Y = $y }
}

function Tap-Resource {
    param(
        [string]$XmlPath,
        [string]$ResourceId
    )

    $center = Get-NodeCenter -XmlPath $XmlPath -ResourceId $ResourceId
    & adb -s $script:TargetSerial shell input tap $center.X $center.Y | Out-Null
}

function Save-AppInternalFiles {
    param(
        [string]$PackageName,
        [string]$DestinationPath
    )

    $script = 'for f in files/debug_events.jsonl files/rendered_requests_* files/rendered_snapshot_* files/api_response_*; do if [ -f "$f" ]; then echo "__FILE__:${f#files/}"; cat "$f"; echo; fi; done'
    $content = & adb -s $script:TargetSerial exec-out run-as $PackageName sh -c $script
    Set-Content -Path $DestinationPath -Value $content -Encoding UTF8
}

$script:TargetSerial = Resolve-TargetSerial
if (-not $script:TargetSerial) {
    throw "No adb device is available."
}

if ($ShareTextFile) {
    $ShareText = Get-Content -Path $ShareTextFile -Raw -Encoding UTF8
}

$sessionDir = Join-Path $OutputRoot (Get-Timestamp)
New-Item -ItemType Directory -Path $sessionDir -Force | Out-Null

$beforeXml = Join-Path $sessionDir "ui-before.xml"
$afterXml = Join-Path $sessionDir "ui-after.xml"
$deviceFiles = Join-Path $sessionDir "device-files.txt"
$shareTextFile = Join-Path $sessionDir "share-text.txt"
Set-Content -Path $shareTextFile -Value $ShareText -Encoding UTF8

Write-Host "Target device: $script:TargetSerial"
Write-Host "Sending full share text to app..."
$remoteCommand = "am start -S -a android.intent.action.SEND -t text/plain -n $PackageName/.MainActivity --es android.intent.extra.TEXT '$ShareText'"
& adb -s $script:TargetSerial shell $remoteCommand | Out-Null

Start-Sleep -Seconds 2
Save-UiDump -DestinationPath $beforeXml
Tap-Resource -XmlPath $beforeXml -ResourceId "${PackageName}:id/extractButton"

Write-Host "Waiting $WaitSeconds seconds for extraction..."
Start-Sleep -Seconds $WaitSeconds

Save-UiDump -DestinationPath $afterXml
Save-AppInternalFiles -PackageName $PackageName -DestinationPath $deviceFiles

Write-Host "Capture saved to: $sessionDir"
Write-Host "UI snapshot: $afterXml"
Write-Host "App files: $deviceFiles"
