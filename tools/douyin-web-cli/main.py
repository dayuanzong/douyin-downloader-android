import argparse
import json
import logging
import os
import re
import sys
from dataclasses import asdict, dataclass
from datetime import datetime
from pathlib import Path
from time import perf_counter
from typing import Any
from urllib.parse import quote, urlencode, urlparse

import httpx

from abogus import ABogus


DEFAULT_UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/90.0.4430.212 Safari/537.36"
)
SHORT_URL_RE = re.compile(r"https?://[^\s\"<>\\^`{|}]+", re.IGNORECASE)
AWEME_ID_RE = re.compile(r"/(?:video|note|slides)/(\d{19})", re.IGNORECASE)
SHARE_TITLE_RE = re.compile(r"【([^】]+)】")
DOUYIN_DETAIL_ENDPOINT = "https://www.douyin.com/aweme/v1/web/aweme/detail/"
TTWID_ENDPOINT = "https://ttwid.bytedance.com/ttwid/union/register/"
TTWID_PAYLOAD = (
    '{"region":"cn","aid":1768,"needFid":false,"service":"www.ixigua.com",'
    '"migrate_info":{"ticket":"","source":"node"},"cbUrlProtocol":"https","union":true}'
)
TEXT_ENCODINGS = ("utf-8-sig", "utf-8", "gb18030", "gbk", "utf-16")


@dataclass
class ProbeSummary:
    input_text: str
    share_url: str
    resolved_url: str
    aweme_id: str
    media_kind: str
    description: str
    author_hint: str
    image_count: int
    video_candidate_count: int
    motion_photo_count: int
    image_urls: list[str]
    video_urls: list[str]
    motion_photo_urls: list[str]
    resolve_ms: int
    cookie_ms: int
    detail_ms: int
    total_ms: int


@dataclass
class ProbeTimings:
    resolve_ms: int
    cookie_ms: int
    detail_ms: int
    total_ms: int


class DouyinWebProbe:
    def __init__(
        self,
        logger: logging.Logger,
        cookie: str | None = None,
        proxy: str | None = None,
        user_agent: str | None = None,
    ):
        self.logger = logger
        self.cookie = cookie or os.getenv("DOUYIN_COOKIE") or ""
        self.proxy = proxy or os.getenv("DOUYIN_PROXY") or ""
        self.user_agent = user_agent or os.getenv("DOUYIN_UA") or DEFAULT_UA
        self.client = httpx.Client(
            headers={
                "Accept-Language": "zh-CN,zh;q=0.9",
                "User-Agent": self.user_agent,
                "Referer": "https://www.douyin.com/",
            },
            proxy=self.proxy or None,
            timeout=20.0,
            follow_redirects=True,
        )

    def close(self) -> None:
        self.client.close()

    def run(self, text: str) -> tuple[ProbeSummary, dict[str, Any]]:
        total_started = perf_counter()
        self.logger.info("probe_started")

        share_url = self.extract_share_url(text)

        resolve_started = perf_counter()
        resolved_url = self.resolve_url(share_url)
        resolve_ms = elapsed_ms(resolve_started)

        aweme_id = self.extract_aweme_id(resolved_url)

        cookie_started = perf_counter()
        cookie = self.prepare_cookie()
        cookie_ms = elapsed_ms(cookie_started)

        detail_started = perf_counter()
        api_payload = self.fetch_detail(aweme_id, cookie)
        detail_ms = elapsed_ms(detail_started)

        timings = ProbeTimings(
            resolve_ms=resolve_ms,
            cookie_ms=cookie_ms,
            detail_ms=detail_ms,
            total_ms=elapsed_ms(total_started),
        )
        summary = self.normalize(text, share_url, resolved_url, aweme_id, api_payload, timings)
        self.logger.info(
            "probe_finished media_kind=%s aweme_id=%s total_ms=%s",
            summary.media_kind,
            summary.aweme_id,
            summary.total_ms,
        )
        return summary, api_payload

    def extract_share_url(self, text: str) -> str:
        match = SHORT_URL_RE.search(text.strip())
        if not match:
            raise ValueError("No share URL found in input text.")
        share_url = match.group(0).rstrip(".,!?;)]}>\"'")
        self.logger.info("share_url=%s", share_url)
        return share_url

    def resolve_url(self, url: str) -> str:
        response = self.client.get(url)
        response.raise_for_status()
        final_url = str(response.url)
        self.logger.info("resolved_url=%s", final_url)
        return final_url

    def extract_aweme_id(self, resolved_url: str) -> str:
        match = AWEME_ID_RE.search(resolved_url)
        if not match:
            raise ValueError(f"Unable to extract aweme_id from resolved URL: {resolved_url}")
        aweme_id = match.group(1)
        self.logger.info("aweme_id=%s", aweme_id)
        return aweme_id

    def prepare_cookie(self) -> str:
        if self.cookie:
            self.logger.info("cookie_mode=provided")
            return self.cookie
        ttwid = self.generate_ttwid()
        cookie = f"ttwid={ttwid}"
        self.logger.info("cookie_mode=generated_ttwid")
        return cookie

    def generate_ttwid(self) -> str:
        response = self.client.post(
            TTWID_ENDPOINT,
            content=TTWID_PAYLOAD,
            headers={"Content-Type": "application/json", "User-Agent": self.user_agent},
        )
        response.raise_for_status()
        ttwid = response.cookies.get("ttwid")
        if not ttwid:
            raise ValueError("Failed to generate ttwid cookie.")
        self.logger.info("ttwid_generated")
        return ttwid

    def fetch_detail(self, aweme_id: str, cookie: str) -> dict[str, Any]:
        params = {
            "device_platform": "webapp",
            "aid": "6383",
            "channel": "channel_pc_web",
            "pc_client_type": "1",
            "version_code": "290100",
            "version_name": "29.1.0",
            "cookie_enabled": "true",
            "screen_width": "1920",
            "screen_height": "1080",
            "browser_language": "zh-CN",
            "browser_platform": "Win32",
            "browser_name": "Chrome",
            "browser_version": "130.0.0.0",
            "browser_online": "true",
            "engine_name": "Blink",
            "engine_version": "130.0.0.0",
            "os_name": "Windows",
            "os_version": "10",
            "cpu_core_num": "12",
            "device_memory": "8",
            "platform": "PC",
            "downlink": "10",
            "effective_type": "4g",
            "round_trip_time": "0",
            "update_version_code": "170400",
            "pc_libra_divert": "Windows",
            "aweme_id": aweme_id,
            "msToken": "",
        }
        a_bogus = quote(ABogus().get_value(params), safe="")
        endpoint = f"{DOUYIN_DETAIL_ENDPOINT}?{urlencode(params)}&a_bogus={a_bogus}"
        self.logger.info("detail_endpoint=%s", endpoint)
        response = self.client.get(
            endpoint,
            headers={
                "Accept-Language": "zh-CN,zh;q=0.9",
                "User-Agent": self.user_agent,
                "Referer": "https://www.douyin.com/",
                "Cookie": cookie,
            },
        )
        self.logger.info("detail_status=%s", response.status_code)
        response.raise_for_status()
        payload = response.json()
        if payload.get("status_code") not in (0, None):
            raise ValueError(f"Douyin detail API returned status_code={payload.get('status_code')}")
        return payload

    def normalize(
        self,
        text: str,
        share_url: str,
        resolved_url: str,
        aweme_id: str,
        payload: dict[str, Any],
        timings: ProbeTimings,
    ) -> ProbeSummary:
        detail = payload.get("aweme_detail") or payload.get("aweme") or {}
        description = str(detail.get("desc") or detail.get("preview_title") or "").strip()
        author_hint = self.extract_author_hint(detail, text)

        image_urls = self.collect_image_urls(detail)
        video_urls = self.collect_video_urls(detail)
        motion_photo_urls = self.collect_motion_photo_urls(detail)
        media_kind = self.detect_media_kind(image_urls, video_urls, motion_photo_urls)

        return ProbeSummary(
            input_text=text,
            share_url=share_url,
            resolved_url=resolved_url,
            aweme_id=aweme_id,
            media_kind=media_kind,
            description=description,
            author_hint=author_hint,
            image_count=len(image_urls),
            video_candidate_count=len(video_urls),
            motion_photo_count=len(motion_photo_urls),
            image_urls=image_urls,
            video_urls=video_urls,
            motion_photo_urls=motion_photo_urls,
            resolve_ms=timings.resolve_ms,
            cookie_ms=timings.cookie_ms,
            detail_ms=timings.detail_ms,
            total_ms=timings.total_ms,
        )

    def extract_author_hint(self, detail: dict[str, Any], text: str) -> str:
        author = detail.get("author") or {}
        if isinstance(author, dict):
            nickname = str(author.get("nickname") or "").strip()
            if nickname:
                return nickname

        match = SHARE_TITLE_RE.search(text)
        if not match:
            return ""
        content = match.group(1).strip()
        for suffix in ("的图文作品", "的作品"):
            if content.endswith(suffix):
                return content[: -len(suffix)].strip()
        return content

    def detect_media_kind(
        self,
        image_urls: list[str],
        video_urls: list[str],
        motion_photo_urls: list[str],
    ) -> str:
        if image_urls and motion_photo_urls:
            return "image_with_motion"
        if image_urls:
            return "image"
        if video_urls:
            return "video"
        return "unknown"

    def collect_image_urls(self, detail: dict[str, Any]) -> list[str]:
        seen: set[str] = set()
        image_urls: list[str] = []
        for node in self.iter_image_nodes(detail):
            dedupe_key = str(node.get("uri") or "")
            primary_url = self.pick_best_image_url(node)
            if not primary_url:
                continue
            dedupe_key = dedupe_key or strip_query(primary_url)
            if dedupe_key in seen:
                continue
            seen.add(dedupe_key)
            image_urls.append(primary_url)
        return image_urls

    def collect_video_urls(self, detail: dict[str, Any]) -> list[str]:
        video = detail.get("video") or {}
        if not isinstance(video, dict):
            return []
        return self.collect_preferred_video_urls([video])

    def collect_motion_photo_urls(self, detail: dict[str, Any]) -> list[str]:
        videos: list[dict[str, Any]] = []
        for node in self.iter_image_nodes(detail):
            video = node.get("video")
            if isinstance(video, dict):
                videos.append(video)
        return self.collect_preferred_video_urls(videos)

    def collect_preferred_video_urls(self, video_nodes: list[dict[str, Any]]) -> list[str]:
        selected: list[str] = []
        seen: set[str] = set()
        for video in video_nodes:
            for address in self.iter_video_address_nodes(video):
                uri = str(address.get("uri") or "").strip()
                candidates = [url for url in self.extract_url_candidates(address) if is_probable_video_url(url)]
                if not candidates:
                    continue
                best_url = self.pick_best_video_url(candidates)
                dedupe_key = uri or strip_query(best_url)
                if dedupe_key in seen:
                    continue
                seen.add(dedupe_key)
                selected.append(best_url)
        return selected

    def iter_video_address_nodes(self, video: dict[str, Any]) -> list[dict[str, Any]]:
        addresses: list[dict[str, Any]] = []
        for key in ("download_addr", "play_addr_h264", "play_addr"):
            value = video.get(key)
            if isinstance(value, dict):
                addresses.append(value)
        for item in video.get("bit_rate", []) or []:
            if not isinstance(item, dict):
                continue
            for key in ("play_addr_h264", "play_addr"):
                value = item.get(key)
                if isinstance(value, dict):
                    addresses.append(value)
        return addresses

    def iter_image_nodes(self, detail: dict[str, Any]) -> list[dict[str, Any]]:
        nodes: list[dict[str, Any]] = []
        for key in ("images", "image_list", "image_infos", "origin_images"):
            value = detail.get(key)
            if isinstance(value, list):
                nodes.extend(item for item in value if isinstance(item, dict))
        post_info = detail.get("image_post_info")
        if isinstance(post_info, dict):
            for key in ("images", "image_list"):
                value = post_info.get(key)
                if isinstance(value, list):
                    nodes.extend(item for item in value if isinstance(item, dict))
        return nodes

    def pick_best_image_url(self, node: dict[str, Any]) -> str:
        candidates: list[str] = []
        for key in (
            "url_list",
            "origin_url_list",
            "download_url_list",
            "watermark_free_download_url_list",
            "download_url",
            "url",
            "image_url",
            "large_image",
            "display_image",
            "origin_image",
            "download_image",
        ):
            if key in node:
                candidates.extend(self.extract_url_candidates(node[key]))
        if not candidates:
            return ""
        unique_candidates = dedupe(candidates)
        return min(unique_candidates, key=image_url_rank)

    def pick_best_video_url(self, candidates: list[str]) -> str:
        unique_candidates = dedupe(candidates)
        return min(unique_candidates, key=video_url_rank)

    def extract_url_candidates(self, node: Any) -> list[str]:
        if isinstance(node, str):
            return [node] if node.startswith("http") else []
        if isinstance(node, list):
            results: list[str] = []
            for item in node:
                results.extend(self.extract_url_candidates(item))
            return results
        if isinstance(node, dict):
            results: list[str] = []
            for key in (
                "url_list",
                "origin_url_list",
                "download_url_list",
                "download_url",
                "url",
                "watermark_free_download_url_list",
                "display_image",
                "origin_image",
                "image_url",
                "large_image",
                "download_image",
            ):
                if key in node:
                    results.extend(self.extract_url_candidates(node[key]))
            return results
        return []


def elapsed_ms(started: float) -> int:
    return int((perf_counter() - started) * 1000)


def dedupe(values: list[str]) -> list[str]:
    seen: set[str] = set()
    result: list[str] = []
    for value in values:
        if value and value not in seen:
            seen.add(value)
            result.append(value)
    return result


def strip_query(value: str) -> str:
    parsed = urlparse(value)
    return f"{parsed.scheme}://{parsed.netloc}{parsed.path}"


def image_url_rank(url: str) -> tuple[int, int, int, str]:
    lower = url.lower()
    watermark_penalty = 10 if "water-" in lower or "watermark" in lower or "logo" in lower else 0
    source_penalty = 0 if "aweme-images" in lower else 1
    host_penalty = 1 if "p9-" in lower else 0
    if ".jpeg" in lower or ".jpg" in lower or ".png" in lower:
        format_penalty = 0
    elif ".webp" in lower:
        format_penalty = 1
    else:
        format_penalty = 2
    return (watermark_penalty, source_penalty, format_penalty, host_penalty, lower)


def video_url_rank(url: str) -> tuple[int, int, int, str]:
    lower = url.lower()
    if (
        "download_addr" in lower
        or "/video/cn/mps/" in lower
        or "/video/tos/" in lower
        or "mime_type=video_mp4" in lower
        or ".mp4" in lower
    ):
        kind_penalty = 0
    elif "/aweme/v1/play/" in lower:
        kind_penalty = 1
    else:
        kind_penalty = 2
    host_penalty = 1 if "v11-weba" in lower else 0
    watermark_penalty = 1 if "logo_type=" in lower or "watermark=1" in lower else 0
    return (kind_penalty, watermark_penalty, host_penalty, lower)


def is_probable_video_url(url: str) -> bool:
    lower = url.lower()
    if is_probable_audio_url(lower):
        return False
    return any(
        token in lower
        for token in ("mime_type=video", "/video/", ".mp4", "/aweme/v1/play/", "video_mp4")
    )


def is_probable_audio_url(url: str) -> bool:
    return ".mp3" in url or "ies-music" in url or "mime_type=audio" in url


def configure_logger(dump_dir: Path | None) -> logging.Logger:
    logger = logging.getLogger("douyin_web_cli")
    logger.setLevel(logging.INFO)
    logger.handlers.clear()
    formatter = logging.Formatter("%(asctime)s %(levelname)s %(message)s")

    stream_handler = logging.StreamHandler(sys.stdout)
    stream_handler.setFormatter(formatter)
    logger.addHandler(stream_handler)

    if dump_dir:
        dump_dir.mkdir(parents=True, exist_ok=True)
        file_handler = logging.FileHandler(dump_dir / "run.log", encoding="utf-8")
        file_handler.setFormatter(formatter)
        logger.addHandler(file_handler)

    return logger


def load_text_file(path: Path) -> str:
    raw = path.read_bytes()
    for encoding in TEXT_ENCODINGS:
        try:
            return raw.decode(encoding).strip()
        except UnicodeDecodeError:
            continue
    raise ValueError(f"Unable to decode file: {path}")


def read_input_text(args: argparse.Namespace) -> str:
    if args.text:
        return args.text.strip()
    if args.text_file:
        return load_text_file(Path(args.text_file))
    raise ValueError("Either --text or --text-file is required.")


def write_dump(dump_dir: Path, summary: ProbeSummary, payload: dict[str, Any]) -> None:
    dump_dir.mkdir(parents=True, exist_ok=True)
    (dump_dir / "summary.json").write_text(
        json.dumps(asdict(summary), ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    (dump_dir / "detail_raw.json").write_text(
        json.dumps(payload, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )


def write_failure(dump_dir: Path, error: Exception, text: str) -> None:
    dump_dir.mkdir(parents=True, exist_ok=True)
    failure = {
        "time": datetime.now().isoformat(),
        "error": str(error),
        "input_text": text,
    }
    (dump_dir / "failure.json").write_text(
        json.dumps(failure, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )


def main() -> int:
    parser = argparse.ArgumentParser(description="Douyin web API CLI prototype")
    parser.add_argument("--text", help="Raw share text")
    parser.add_argument("--text-file", help="Path to a text file containing raw share text")
    parser.add_argument("--dump-dir", help="Directory for logs and JSON dumps")
    parser.add_argument("--cookie", help="Optional Douyin cookie override")
    parser.add_argument("--proxy", help="Optional proxy URL")
    parser.add_argument("--ua", help="Optional user-agent override")
    args = parser.parse_args()

    dump_dir = Path(args.dump_dir).resolve() if args.dump_dir else None
    logger = configure_logger(dump_dir)
    text = read_input_text(args)
    logger.info("input_loaded length=%s", len(text))

    probe = DouyinWebProbe(logger, cookie=args.cookie, proxy=args.proxy, user_agent=args.ua)
    try:
        summary, payload = probe.run(text)
        print(json.dumps(asdict(summary), ensure_ascii=False, indent=2))
        if dump_dir:
            write_dump(dump_dir, summary, payload)
        return 0
    except Exception as error:
        logger.exception("probe_failed error=%s", error)
        if dump_dir:
            write_failure(dump_dir, error, text)
        return 1
    finally:
        probe.close()


if __name__ == "__main__":
    raise SystemExit(main())
