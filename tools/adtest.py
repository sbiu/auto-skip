#!/usr/bin/env python3
"""冷启动采样：抓三帧 + AutoSkip 日志，用饱和度粗判是否开屏广告。
用法: /usr/bin/python3 tools/adtest.py <pkg> <activity> <次数> <标签>
"""
import os
import subprocess
import sys
import time

from PIL import Image


def sh(cmd, timeout=40):
    try:
        return subprocess.run(cmd, shell=True, capture_output=True, text=True, timeout=timeout).stdout
    except subprocess.TimeoutExpired:
        return ""


def shot(path):
    sh("adb shell screencap -p /sdcard/t.png")
    sh(f"adb pull /sdcard/t.png {path} >/dev/null 2>&1")


def ad_score(path):
    try:
        im = Image.open(path).convert("RGB").resize((144, 320))
    except Exception:
        return 0.0
    data = list(im.getdata())
    sat = 0
    for r, g, b in data:
        mx, mn = max(r, g, b), min(r, g, b)
        if mx - mn > 60 and mx > 70:
            sat += 1
    return sat / len(data)


def main():
    pkg, act, n, tag = sys.argv[1], sys.argv[2], int(sys.argv[3]), sys.argv[4]
    os.makedirs("/tmp/ads", exist_ok=True)
    for i in range(n):
        sh("adb logcat -c")
        sh(f"adb shell am force-stop {pkg}")
        sh(f"adb shell am start -W -n {act}")
        time.sleep(0.8)
        p1 = f"/tmp/ads/{tag}_{i}_a.png"
        shot(p1)
        s1 = ad_score(p1)
        time.sleep(0.9)
        p2 = f"/tmp/ads/{tag}_{i}_b.png"
        shot(p2)
        s2 = ad_score(p2)
        time.sleep(0.9)
        p3 = f"/tmp/ads/{tag}_{i}_c.png"
        shot(p3)
        s3 = ad_score(p3)
        log = sh("adb logcat -d -s AutoSkip").strip().splitlines()
        log = [l for l in log if "skipped" in l or "scan" in l]
        print(f"{tag} #{i}: {s1:.2f} {s2:.2f} {s3:.2f}  {log[-1] if log else '-'}", flush=True)
        time.sleep(2)


if __name__ == "__main__":
    main()
