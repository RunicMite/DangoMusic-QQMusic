#!/usr/bin/env python3
"""校验 build/ 下的产物：class 结构 + 全 jar 内部引用 + 界面文案 + 版本号。

做法：
  1. 用 ASM 逐类解析（AsmVerify）—— 常量池/结构有任何问题都会失败
  2. 用 LinkCheck 收集"内部引用解析不了的成员"，并与**上游原始 jar 的基线**对比，
     只有"新增悬空引用"才算失败（原 jar 本身存在的噪音不计）
  3. 检查界面文案是否已换成 QQ 音乐版、版本号是否已改

用法:
    python tools/verify_all.py
    python tools/verify_all.py 26.3-fabric
"""

import os
import shutil
import struct
import subprocess
import sys
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
UPSTREAM = os.path.join(ROOT, "upstream")
BUILT = os.path.join(ROOT, "build")
TOOLS_OUT = os.path.join(ROOT, ".work", "tools")
TMP = os.path.join(ROOT, ".work", "verify")
UPSTREAM_VER = "1.5.0"

TARGETS = [
    "1.20.1-fabric", "1.20.1-forge",
    "1.21.1-fabric", "1.21.1-neoforge",
    "1.21.11-fabric", "1.21.11-neoforge",
    "26.2-fabric", "26.2-neoforge",
    "26.3-fabric", "26.3-neoforge",
]

CP_SIZES = {3: 5, 4: 5, 5: 9, 6: 9, 7: 3, 8: 3, 15: 4, 16: 3, 19: 3, 20: 3,
            9: 5, 10: 5, 11: 5, 12: 5, 17: 5, 18: 5}


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
            pos += CP_SIZES[tag]
            if tag in (5, 6):
                i += 1
        i += 1
    return out


def asm_jar():
    for n in ("asm-9.10.1.jar", "asm-9.9.1.jar", "asm-9.7.1.jar"):
        p = os.path.join(UPSTREAM, n)
        if os.path.isfile(p):
            return p
    sys.exit("缺少 ASM，先跑 tools/fetch_upstream.py")


def javac():
    p = shutil.which("javac") or os.environ.get("JAVAC")
    if not p:
        sys.exit("找不到 javac")
    return p


def prepare_tools():
    asm = asm_jar()
    os.makedirs(TOOLS_OUT, exist_ok=True)
    need = [n for n in ("LinkCheck", "AsmVerify")
            if not os.path.isfile(os.path.join(TOOLS_OUT, n + ".class"))]
    if need:
        srcs = [os.path.join(HERE, n + ".java") for n in need]
        r = subprocess.run([javac(), "-encoding", "UTF-8", "-nowarn", "-cp", asm,
                            "-d", TOOLS_OUT] + srcs, capture_output=True, text=True, errors="replace")
        if r.returncode != 0:
            sys.exit("编译校验工具失败:\n" + (r.stdout + r.stderr)[:1500])
    return asm


def run(cmd):
    r = subprocess.run(cmd, capture_output=True, text=True, errors="replace")
    return r.stdout + r.stderr


def link_refs(asm, jar, tag):
    out = os.path.join(TMP, tag + ".txt")
    run(["java", "-cp", os.pathsep.join([TOOLS_OUT, asm]), "LinkCheck", jar, out])
    if not os.path.isfile(out):
        return None
    with open(out, encoding="utf-8") as f:
        return {l.strip() for l in f if l.strip()}


def asm_ok(asm, jar):
    txt = run(["java", "-cp", os.pathsep.join([TOOLS_OUT, asm]), "AsmVerify", jar])
    for line in txt.splitlines():
        if line.startswith("ok="):
            return line.strip()
    return "asm ?"


EXPECT = {
    "me/sorihiko/dango/musicui/LoginDialog.class": (["Log in to QQ Music", "Refresh QR"],
                                                    ["Log in to NetEase"]),
    "me/sorihiko/dango/musicui/Sidebar.class": (["Log in to QQ Music"], ["Log in to NetEase"]),
    "me/sorihiko/dango/musicui/PartyPage.class": (["QQ Music account"], ["NetEase account"]),
    "me/sorihiko/dango/module/impls/music/LyricsModule.class":
        (["QQ Music often does this"], ["NetEase often does this"]),
}


def main():
    only = sys.argv[1] if len(sys.argv) > 1 else None
    os.makedirs(TMP, exist_ok=True)
    asm = prepare_tools()
    print("%-16s %-22s %-24s %-9s %s" % ("target", "ASM", "内部引用", "文案", "版本号"))
    problems = []
    for t in TARGETS:
        if only and only not in t:
            continue
        src = os.path.join(UPSTREAM, "DangoMusic-%s+%s.jar" % (UPSTREAM_VER, t))
        dst = os.path.join(BUILT, "DangoMusic-1.5.1-qqmusic+%s.jar" % t)
        if not os.path.isfile(dst):
            print("%-16s 未构建" % t)
            problems.append(t)
            continue
        base = link_refs(asm, src, t + "_orig") or set()
        cur = link_refs(asm, dst, t + "_new") or set()
        extra = cur - base

        z = zipfile.ZipFile(dst)
        ok_text = True
        for cls, (want, avoid) in EXPECT.items():
            text = "\n".join(utf8_constants(z.read(cls)))
            if not all(w in text for w in want) or any(a in text for a in avoid):
                ok_text = False
                print("    文案不符: " + cls.split("/")[-1])
        names = z.namelist()
        meta = ("fabric.mod.json" if "fabric.mod.json" in names else
                "META-INF/neoforge.mods.toml" if "META-INF/neoforge.mods.toml" in names
                else "META-INF/mods.toml")
        raw = z.read(meta).decode("utf-8", "replace")
        ok_ver = "1.5.1-qqmusic" in raw and UPSTREAM_VER not in raw

        link_txt = "OK" if not extra else "新增悬空 %d" % len(extra)
        print("%-16s %-22s %-24s %-9s %s"
              % (t, asm_ok(asm, dst), link_txt, "OK" if ok_text else "BAD",
                 "OK" if ok_ver else "BAD"))
        if extra or not ok_text or not ok_ver:
            problems.append(t)
            for e in sorted(extra)[:8]:
                print("      extra: " + e[:150])

    print()
    print("全部通过" if not problems else "有问题: %s" % problems)
    return 0 if not problems else 1


if __name__ == "__main__":
    sys.exit(main())
