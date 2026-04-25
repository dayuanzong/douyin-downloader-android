# Douyin Web CLI Prototype

PC command-line prototype for validating the Douyin web API route before we bring anything back into Android.

## What It Does

- Extracts a share URL from raw share text
- Resolves the short link to the final Douyin page
- Extracts `aweme_id`
- Calls the Douyin web detail API with an `a_bogus` signature
- Normalizes image posts by logical asset instead of counting every URL variant
- Separates normal video URLs from motion-photo video URLs
- Writes structured logs and optional raw JSON dumps

## Quick Start

```powershell
cd C:\Users\123456\AndroidStudioProjects\douyin-downloader-android\tools\douyin-web-cli
python -m venv .venv
.\.venv\Scripts\activate
pip install -r requirements.txt
python main.py --text-file ..\samples\video-share-20260423.txt --dump-dir .\runs\single
```

If `gmssl` cannot be installed from the default index in your environment, use a mirror:

```powershell
python -m pip install gmssl==3.2.2 -i https://pypi.tuna.tsinghua.edu.cn/simple
```

You can also pass text directly:

```powershell
python main.py --text "2.07 复制打开抖音，看看【永不言弃的作品】花哥落幕了 https://v.douyin.com/xGIbUCwXtV4/"
```

## Optional Environment Variables

- `DOUYIN_COOKIE`
- `DOUYIN_PROXY`
- `DOUYIN_UA`

If `DOUYIN_COOKIE` is missing, the tool will still try the public route with a generated `ttwid`.

## Batch Example

```powershell
python batch_run.py --glob "..\samples\*.txt" --output-dir .\runs\batch
```

The batch output writes:

- `batch_summary.json` at the output root
- one subdirectory per sample
- `summary.json`, `detail_raw.json`, and `run.log` for each successful sample
- `failure.json` for failed samples
