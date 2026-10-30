#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""生成可导入的音色包（koudai-voices-*.zip）。

把 speech/voices/tts_* 四个目录打成一个 zip，zip 内第一个条目固定是
manifest.json（相对路径 + 字节数），App 的「导入 .zip」会增量解压到
files/speech/ 下。

用法：
    VOICE_PKG_VER=1.0.0 python tools/make-voices-zip.py
"""
import json
import os
import sys
import zipfile

ROOT = os.environ.get("KCPM_ROOT_WIN", "D:/kcpm")
SRC = os.path.join(ROOT, "speech", "voices")
OUT_DIR = os.path.join(ROOT, "out")
VERSION = os.environ.get("VOICE_PKG_VER", "1.0.0")
OUT = os.path.join(OUT_DIR, "koudai-voices-%s.zip" % VERSION)


def main():
    if not os.path.isdir(SRC):
        print("找不到音色源目录：%s" % SRC)
        return 1

    dirs = sorted(d for d in os.listdir(SRC)
                  if d.startswith("tts_") and os.path.isdir(os.path.join(SRC, d)))
    if not dirs:
        print("音色源目录里没有 tts_* 子目录")
        return 1

    entries = []
    for d in dirs:
        base = os.path.join(SRC, d)
        for name in sorted(os.listdir(base)):
            p = os.path.join(base, name)
            if os.path.isfile(p):
                entries.append((p, "speech/%s/%s" % (d, name)))

    total = sum(os.path.getsize(p) for p, _ in entries)
    manifest = {
        "version": "%s-voices" % VERSION,
        "total": total,
        "files": [{"path": arc, "size": os.path.getsize(p)} for p, arc in entries],
    }

    os.makedirs(OUT_DIR, exist_ok=True)
    if os.path.exists(OUT):
        os.remove(OUT)

    with zipfile.ZipFile(OUT, "w", zipfile.ZIP_STORED, allowZip64=True) as zf:
        zf.writestr("manifest.json", json.dumps(manifest, ensure_ascii=False, indent=1))
        for p, arc in entries:
            print("   %-46s %7.1f MB" % (arc, os.path.getsize(p) / 1048576.0))
            zf.write(p, arc)

    size = os.path.getsize(OUT)
    print("\n音色包：%s" % OUT)
    print("   %d 个目录 / %d 个文件，原始 %.0f MB -> %.0f MB"
          % (len(dirs), len(entries), total / 1048576.0, size / 1048576.0))
    return 0


if __name__ == "__main__":
    sys.exit(main())
