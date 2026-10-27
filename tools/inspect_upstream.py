#!/usr/bin/env python3
"""上游升级前的契约检查：确认目标类还在、枚举顺序没变、界面文案还能命中。

换新的上游版本后先跑这个，能提前发现"补丁假设不再成立"的地方，
不用等 build_all 编译失败或游戏里崩。

用法:
    python tools/inspect_upstream.py
"""

import os
import struct
import sys
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
UPSTREAM = os.path.join(ROOT, "upstream")

sys.path.insert(0, HERE)
import qqpatch  # noqa: E402

TARGETS = [
    "1.20.1-fabric", "1.20.1-forge",
    "1.21.1-fabric", "1.21.1-neoforge",
    "1.21.11-fabric", "1.21.11-neoforge",
    "26.2-fabric", "26.2-neoforge",
    "26.3-fabric", "26.3-neoforge",
]

UPSTREAM_VER = "1.5.0"

# 我们要替换/新增的类必须存在（或允许不存在→新增）
MUST_EXIST = [
    "me/sorihiko/dango/music/NeteaseApi.class",
    "me/sorihiko/dango/music/NeteaseLogin.class",
    "me/sorihiko/dango/music/NeteaseLogin$State.class",
    "me/sorihiko/dango/music/NeteaseTrack.class",
    "me/sorihiko/dango/music/NeteasePlaylist.class",
    "me/sorihiko/dango/lyrics/LyricsProvider.class",
    "me/sorihiko/dango/party/Firebase.class",
]

STATE_ORDER = ["IDLE", "LOADING", "WAITING", "SCANNED", "SUCCESS", "EXPIRED", "ERROR"]

MAJOR = {52: "Java 8", 55: "Java 11", 61: "Java 17", 65: "Java 21", 69: "Java 25"}


def utf8_constants(data):
    count = struct.unpack(">H", data[8:10])[0]
    pos, i, out = 10, 1, []
    while i < count:
        tag = data[pos]
        if tag == 1:
            ln = struct.unpack(">H", data[pos + 1:pos + 3])[0]
            out.append(data[pos + 3:pos + 3 + ln].decode("utf-8", "replace"))
            pos += 3 + ln
        else:
            pos += qqpatch.CP_SIZES[tag]
            if tag in (5, 6):
                i += 1
        i += 1
    return out


def main():
    if not os.path.isdir(UPSTREAM):
        sys.exit("没有 upstream/ 目录，先跑 tools/fetch_upstream.py")
    jars = [n for n in sorted(os.listdir(UPSTREAM)) if n.endswith(".jar") and "+" in n]
    if not jars:
        sys.exit("upstream/ 里没有上游平台 jar")

    problems = []
    for name in jars:
        path = os.path.join(UPSTREAM, name)
        z = zipfile.ZipFile(path)
        names = set(z.namelist())
        api = z.read("me/sorihiko/dango/music/NeteaseApi.class")
        major = struct.unpack(">H", api[6:8])[0]

        missing = [c for c in MUST_EXIST if c not in names]
        state = []
        for s in utf8_constants(z.read("me/sorihiko/dango/music/NeteaseLogin$State.class")):
            if s in STATE_ORDER and s not in state:
                state.append(s)

        # 文案是否还能命中（命中才能替换）
        text_miss = []
        for cls, mapping in qqpatch.STRINGS.items():
            have = set(utf8_constants(z.read(cls)))
            for old in mapping:
                if old not in have:
                    text_miss.append("%s: %s" % (cls.split("/")[-1], old[:40]))

        print("%-34s %-9s 缺失=%-2s 枚举=%-4s 文案unmatched=%d"
              % (name, MAJOR.get(major, major), len(missing), len(state), len(text_miss)))
        if missing:
            problems.append((name, "缺失类", missing))
        if state != STATE_ORDER:
            problems.append((name, "枚举顺序不同", state))
        if text_miss:
            problems.append((name, "文案未命中", text_miss))

    print()
    if problems:
        print("需要注意：")
        for name, kind, detail in problems:
            print("  %s -> %s: %s" % (name, kind, detail))
        return 1
    print("全部符合补丁假设，可以跑 build_all.py")
    return 0


if __name__ == "__main__":
    sys.exit(main())
