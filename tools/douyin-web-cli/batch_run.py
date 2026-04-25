import argparse
import json
from dataclasses import asdict
from datetime import datetime
from pathlib import Path

from main import DouyinWebProbe, configure_logger, load_text_file, write_dump, write_failure


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Batch-run the Douyin web API probe against sample files")
    parser.add_argument("--glob", required=True, help="Glob pattern for input share-text files")
    parser.add_argument("--output-dir", required=True, help="Directory for batch outputs")
    parser.add_argument("--cookie", help="Optional Douyin cookie override")
    parser.add_argument("--proxy", help="Optional proxy URL")
    parser.add_argument("--ua", help="Optional user-agent override")
    parser.add_argument("--stop-on-error", action="store_true", help="Stop the batch immediately after the first failure")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    output_dir = Path(args.output_dir).resolve()
    output_dir.mkdir(parents=True, exist_ok=True)

    sample_files = sorted(Path().glob(args.glob))
    if not sample_files:
        raise SystemExit(f"No files matched: {args.glob}")

    items: list[dict] = []
    success_count = 0
    failure_count = 0

    for sample_file in sample_files:
        sample_dir = output_dir / sample_file.stem
        logger = configure_logger(sample_dir)
        logger.info("batch_item_started sample=%s", sample_file)

        text = load_text_file(sample_file)
        probe = DouyinWebProbe(logger, cookie=args.cookie, proxy=args.proxy, user_agent=args.ua)
        try:
            summary, payload = probe.run(text)
            write_dump(sample_dir, summary, payload)
            items.append(
                {
                    "sample_file": str(sample_file.resolve()),
                    "success": True,
                    "summary": asdict(summary),
                }
            )
            success_count += 1
        except Exception as error:
            logger.exception("batch_item_failed sample=%s error=%s", sample_file, error)
            write_failure(sample_dir, error, text)
            items.append(
                {
                    "sample_file": str(sample_file.resolve()),
                    "success": False,
                    "error": str(error),
                }
            )
            failure_count += 1
            if args.stop_on_error:
                break
        finally:
            probe.close()

    batch_summary = {
        "time": datetime.now().isoformat(),
        "sample_count": len(items),
        "success_count": success_count,
        "failure_count": failure_count,
        "items": items,
    }
    (output_dir / "batch_summary.json").write_text(
        json.dumps(batch_summary, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )

    print(json.dumps(batch_summary, ensure_ascii=False, indent=2))
    return 1 if failure_count else 0


if __name__ == "__main__":
    raise SystemExit(main())
