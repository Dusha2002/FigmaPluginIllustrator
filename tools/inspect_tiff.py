import json
import sys
from pathlib import Path

from PIL import Image
from PIL.TiffImagePlugin import IFDRational
from PIL.TiffTags import TAGS_V2


def summarize(value):
    if isinstance(value, IFDRational):
        return float(value)
    if isinstance(value, (bytes, bytearray)):
        preview = value[:16]
        hex_preview = " ".join(f"{b:02x}" for b in preview)
        suffix = "..." if len(value) > len(preview) else ""
        return f"{type(value).__name__} len={len(value)} [{hex_preview}{suffix}]"
    if isinstance(value, (list, tuple)):
        numeric = all(isinstance(x, (int, float)) for x in value)
        if len(value) <= 16 and numeric:
            return value
        head = ", ".join(str(x) for x in value[:8])
        tail = "..." if len(value) > 8 else ""
        return f"{type(value).__name__} len={len(value)} [{head}{tail}]"
    if isinstance(value, dict):
        return f"dict len={len(value)}"
    return value


def extract_tags(img: Image.Image) -> dict[str, object]:
    tags = img.tag_v2
    extracted: dict[str, object] = {}
    for key in sorted(tags.keys()):
        name = TAGS_V2.get(key, str(key))
        try:
            value = tags.get(key)
        except Exception as exc:  # pragma: no cover
            value = f"ERROR {exc}"
        extracted[f"{key}:{name}"] = summarize(value)
    return extracted


def inspect(path: Path) -> None:
    if not path.exists():
        print(f"File not found: {path}")
        return

    with Image.open(path) as img:
        print(f"=== {path.name} ===")
        print(f"size: {img.size}")
        print(f"mode: {img.mode}")
        print(f"info: {img.info}")
        for key, value in extract_tags(img).items():
            print(f"{key} -> {value}")


def main(argv: list[str]) -> int:
    summary_mode = False
    args = []
    for arg in argv[1:]:
        if arg == "--summary":
            summary_mode = True
        else:
            args.append(arg)

    if not args:
        print("Usage: inspect_tiff.py [--summary] <file1> [file2 ...]")
        return 1
    for arg in args:
        path = Path(arg)
        if summary_mode:
            with Image.open(path) as img:
                info = {k: summarize(v) for k, v in img.info.items()}
                summary = {
                    "file": path.name,
                    "size": list(img.size),
                    "mode": img.mode,
                    "info": info,
                    "tags": extract_tags(img),
                }
            print(json.dumps(summary, ensure_ascii=False, indent=2))
        else:
            inspect(path)
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
