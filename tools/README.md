# Debug Tools

Recommended flow:

1. Install the latest debug build:

```powershell
.\gradlew.bat installDebug
```

2. Replay a full share text into the app and collect artifacts:

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\Invoke-DouyinDebugCapture.ps1 `
  -ShareTextFile .\tools\samples\jhfs-share.txt `
  -WaitSeconds 20
```

3. Open WebView DevTools for the running app:

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\Open-WebViewDevTools.ps1
```

4. Optionally boot the local emulator:

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\Start-AndroidEmulator.ps1
```

Artifacts saved by `Invoke-DouyinDebugCapture.ps1`:

- `ui-before.xml`
- `ui-after.xml`
- `device-files.txt`
- `share-text.txt`

The app also writes structured debug files into internal storage during extraction:

- `debug_events.jsonl`
- `rendered_requests_<awemeId>.json`
- `rendered_snapshot_<awemeId>.json`
- `api_response_<awemeId>.json`
