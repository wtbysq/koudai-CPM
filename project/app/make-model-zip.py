#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""生成可导入的模型包（koudai-models-*.zip）。

包含文本模型、视觉投影器和语音模型，App 里「导入 .zip」会流式解压到
应用内部存储。zip 里第一个条目固定是 manifest.json（含每个文件的相对
路径和字节数），App 用它做空间预估和完整性校验。
"""
import json
import os
import sys
import zipfile

ROOT = "D:/kcpm"
OUT_DIR = os.path.join(ROOT, "out")
VERSION = os.environ.get("MODEL_PKG_VER", "0.6.0")
OUT = os.path.join(OUT_DIR, "koudai-models-%s.zip" % VERSION)

# (磁盘路径, zip 内路径, 是否 deflate)
FILES = [
    ("D:/口袋CPM/testbed/models/MiniCPM-V-4_7-1B-Q4_K_M.gguf",
     "models/MiniCPM-V-4_7-1B-Q4_K_M.gguf", True),
    ("D:/口袋CPM/testbed/models/mmproj-4.7-patched.gguf",
     "models/mmproj-4.7-patched.gguf", True),
    ("D:/kcpm/speech/asr/model.int8.onnx", "speech/asr/model.int8.onnx", True),
    ("D:/kcpm/speech/asr/tokens.txt", "speech/asr/tokens.txt", False),
    ("D:/kcpm/speech/tts-fc/vits-zh-hf-fanchen-C/vits-zh-hf-fanchen-C.onnx",
     "speech/tts/model.onnx", True),
    ("D:/kcpm/speech/tts-fc/vits-zh-hf-fanchen-C/tokens.txt", "speech/tts/tokens.txt", False),
    ("D:/kcpm/speech/tts-fc/vits-zh-hf-fanchen-C/lexicon.txt", "speech/tts/lexicon.txt", False),
    ("D:/kcpm/speech/tts-fc/vits-zh-hf-fanchen-C/date.fst", "speech/tts/date.fst", False),
    ("D:/kcpm/speech/tts-fc/vits-zh-hf-fanchen-C/number.fst", "speech/tts/number.fst", False),
    ("D:/kcpm/speech/tts-fc/vits-zh-hf-fanchen-C/phone.fst", "speech/tts/phone.fst", False),
    ("D:/kcpm/speech/tts-fc/vits-zh-hf-fanchen-C/new_heteronym.fst",
     "speech/tts/new_heteronym.fst", False),
    ("D:/kcpm/speech/tts-m/vits-zh-hf-fanchen-wnj.onnx", "speech/tts_m/model.onnx", True),
    ("D:/kcpm/speech/tts-m/tokens.txt", "speech/tts_m/tokens.txt", False),
    ("D:/kcpm/speech/tts-m/lexicon.txt", "speech/tts_m/lexicon.txt", False),
    ("D:/kcpm/speech/tts-m/date.fst", "speech/tts_m/date.fst", False),
    ("D:/kcpm/speech/tts-m/number.fst", "speech/tts_m/number.fst", False),
    ("D:/kcpm/speech/tts-m/phone.fst", "speech/tts_m/phone.fst", False),
    ("D:/kcpm/speech/tts-m/new_heteronym.fst", "speech/tts_m/new_heteronym.fst", False),
]

CHUNK = 4 * 1024 * 1024


def add_stream(zf, arcname, path, compress):
    info = zipfile.ZipInfo(arcname)
    info.compress_type = zipfile.ZIP_DEFLATED if compress else zipfile.ZIP_STORED
    info.external_attr = (0o644 & 0xFFFF) << 16
    with open(path, "rb") as src, zf.open(info, "w") as dst:
        left = os.fstat(src.fileno()).st_size
        while left > 0:
            chunk = src.read(min(CHUNK, left))
            if not chunk:
                break
            dst.write(chunk)
            left -= len(chunk)


def main():
    missing = [p for p, _, _ in FILES if not os.path.exists(p)]
    if missing:
        sys.exit("缺文件：\n  " + "\n  ".join(missing))

    total = sum(os.path.getsize(p) for p, _, _ in FILES)
    manifest = {
        "version": VERSION,
        "total": total,
        "files": [{"path": arc, "size": os.path.getsize(p)} for p, arc, _ in FILES],
    }

    os.makedirs(OUT_DIR, exist_ok=True)
    if os.path.exists(OUT):
        os.remove(OUT)

    with zipfile.ZipFile(OUT, "w", zipfile.ZIP_DEFLATED, allowZip64=True,
                         compresslevel=6) as zf:
        # manifest 放第一个，App 一进来就能算空间、知道要解哪些文件
        zf.writestr("manifest.json", json.dumps(manifest, ensure_ascii=False, indent=1))
        for path, arc, compress in FILES:
            print("   %-38s %6.1f MB%s" % (arc, os.path.getsize(path) / 1048576.0,
                                           "" if compress else "  (store)"))
            add_stream(zf, arc, path, compress)

    size = os.path.getsize(OUT)
    print("\n模型包：%s" % OUT)
    print("   原始 %.2f GB -> 压缩后 %.2f GB（解压需要 %.2f GB 空间）"
          % (total / 1073741824.0, size / 1073741824.0, total / 1073741824.0))


if __name__ == "__main__":
    main()
