#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""多线程分段下载（支持断点续传），用于拉大模型。

用法: python pget.py <url> <输出文件> [线程数]
"""
import concurrent.futures
import os
import sys
import threading
import time
import urllib.request

CHUNK = 32 * 1024 * 1024


def head_size(url):
    req = urllib.request.Request(url, method="HEAD")
    with urllib.request.urlopen(req, timeout=60) as r:
        return int(r.headers["Content-Length"])


def fetch_range(url, path, start, end, label):
    have = 0
    if os.path.exists(path):
        have = os.path.getsize(path)
        if have > end - start + 1:
            have = 0
    attempt = 0
    while have <= end - start:
        try:
            req = urllib.request.Request(url)
            req.add_header("Range", "bytes=%d-%d" % (start + have, end))
            with urllib.request.urlopen(req, timeout=120) as r, open(path, "ab") as f:
                while True:
                    buf = r.read(1 << 20)
                    if not buf:
                        break
                    f.write(buf)
                    have += len(buf)
            break
        except Exception as e:
            attempt += 1
            if attempt > 8:
                raise
            time.sleep(2 * attempt)
    return have


def main():
    url, out = sys.argv[1], sys.argv[2]
    n = int(sys.argv[3]) if len(sys.argv) > 3 else 16
    total = head_size(url)
    print("总大小 %.2f GB，分 %d 段" % (total / 1073741824.0, n), flush=True)
    os.makedirs(os.path.dirname(out) or ".", exist_ok=True)

    step = max(CHUNK, total // n + 1)
    ranges = []
    s = 0
    idx = 0
    while s < total:
        e = min(s + step - 1, total - 1)
        ranges.append((s, e, out + ".part%02d" % idx))
        s = e + 1
        idx += 1

    done_bytes = [0] * len(ranges)
    stop = threading.Event()

    def run(i, rng):
        s, e, p = rng
        got = fetch_range(url, p, s, e, str(i))
        done_bytes[i] = got

    def report():
        last = 0
        while not stop.is_set():
            time.sleep(2)
            now = sum(done_bytes) + sum(
                os.path.getsize(p) for _, _, p in ranges if os.path.exists(p)) - sum(done_bytes)
            now = sum(os.path.getsize(p) for _, _, p in ranges if os.path.exists(p))
            speed = (now - last) / 2.0 / 1048576.0
            last = now
            pct = now * 100.0 / total
            print("   %.1f%%  %.2f/%.2f GB  %.1f MB/s" % (
                pct, now / 1073741824.0, total / 1073741824.0, speed), flush=True)

    th = threading.Thread(target=report, daemon=True)
    th.start()

    with concurrent.futures.ThreadPoolExecutor(max_workers=n) as ex:
        list(ex.map(lambda ir: run(*ir), enumerate(ranges)))

    stop.set()
    time.sleep(2.5)

    got = sum(os.path.getsize(p) for _, _, p in ranges)
    if got != total:
        sys.exit("大小不一致：%d / %d" % (got, total))

    if os.path.exists(out):
        os.remove(out)
    with open(out, "wb") as dst:
        for _, _, p in ranges:
            with open(p, "rb") as src:
                while True:
                    buf = src.read(1 << 22)
                    if not buf:
                        break
                    dst.write(buf)
            os.remove(p)
    print("完成：%s (%.2f GB)" % (out, os.path.getsize(out) / 1073741824.0), flush=True)


if __name__ == "__main__":
    main()
