#!/usr/bin/env python3
"""
Piper 官方导出的 .onnx 通常不带 sherpa-onnx 所需的 metadata，
会导致 log 中出现 'sample_rate' does not exist in the metadata 并可能在部分机型上 SIGABRT。

本脚本根据同目录的 *.onnx.json 写入 sherpa-onnx/offline-tts-vits-model 期望的字段。
参见: https://github.com/k2-fsa/sherpa-onnx/issues/1637

用法:
  pip install onnx
  python inject_sherpa_piper_onnx_metadata.py \\
    --onnx path/to/en_US-amy-medium.onnx \\
    --json path/to/en_US-amy-medium.onnx.json \\
    [--out path/to/out.onnx]   # 默认覆盖 --onnx（会先写临时文件再替换）
"""

from __future__ import annotations

import argparse
import json
import os
import shutil
import sys
import tempfile


def _set_meta(model, key: str, value: str) -> None:
    for p in model.metadata_props:
        if p.key == key:
            p.value = value
            return
    entry = model.metadata_props.add()
    entry.key = key
    entry.value = value


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--onnx", required=True, help="输入 ONNX 路径")
    ap.add_argument("--json", required=True, help="同名的 Piper onnx.json")
    ap.add_argument("--out", default=None, help="输出路径；默认原地覆盖 onnx")
    args = ap.parse_args()

    try:
        import onnx
    except ImportError:
        print("请先安装: pip install onnx", file=sys.stderr)
        return 1

    with open(args.json, encoding="utf-8") as f:
        cfg = json.load(f)

    audio = cfg.get("audio") or {}
    sample_rate = int(audio.get("sample_rate") or 22050)
    num_speakers = int(cfg.get("num_speakers") or 1)
    # sherpa 侧 language 字符串常与 Piper/espeak 一致；优先 espeak.voice（如 en-us）
    espeak = cfg.get("espeak") or {}
    voice = espeak.get("voice")
    if isinstance(voice, str) and voice.strip():
        language = voice.strip()
    else:
        lang_obj = cfg.get("language")
        if isinstance(lang_obj, dict):
            language = str(lang_obj.get("code") or "en_US")
        else:
            language = str(lang_obj or "en_US")

    model = onnx.load(args.onnx)
    _set_meta(model, "sample_rate", str(sample_rate))
    _set_meta(model, "n_speakers", str(num_speakers))
    _set_meta(model, "language", language)
    # 必须包含子串 piper，否则 sherpa 不会走 Piper 推理分支
    _set_meta(model, "comment", "piper; injected metadata for sherpa-onnx")
    _set_meta(model, "add_blank", "0")

    out_path = args.out or args.onnx
    if args.out is None:
        fd, tmp = tempfile.mkstemp(suffix=".onnx", prefix="sherpa_meta_")
        os.close(fd)
        try:
            onnx.save(model, tmp)
            shutil.move(tmp, out_path)
        finally:
            if os.path.exists(tmp):
                try:
                    os.remove(tmp)
                except OSError:
                    pass
    else:
        onnx.save(model, out_path)

    print(f"OK: wrote metadata to {out_path}")
    print(f"    sample_rate={sample_rate} n_speakers={num_speakers} language={language}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
