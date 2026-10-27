#!/usr/bin/env python3
"""对上游 DangoMusic 的每个平台 jar 重跑 QQ 音乐补丁管线。

用法:
    python tools/fetch_upstream.py          # 先把上游 jar / gson / asm 下到 upstream/
    python tools/build_all.py               # 构建全部平台
    python tools/build_all.py 26.3-fabric   # 只构建匹配的目标

每个目标都是"用该 jar 自身当 classpath 重新编译替换类"，所以编译不通过就说明
签名对不上 —— 这是最硬的一道适配检查。
"""

import os
import shutil
import subprocess
import sys
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
UPSTREAM = os.path.join(ROOT, "upstream")
OUT_DIR = os.path.join(ROOT, "build")
WORK = os.path.join(ROOT, ".work", "classes")
SRC_DIRS = [os.path.join(ROOT, "src", "main", "java")]

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
META = ("fabric.mod.json", "META-INF/mods.toml", "META-INF/neoforge.mods.toml")
OLD_VER = UPSTREAM_VER.encode()
NEW_VER = b"1.5.1-qqmusic"
OLD_DESC = b"A NetEase Cloud Music player inside Minecraft"
NEW_DESC = b"A QQ Music player inside Minecraft"


def javac():
    p = shutil.which("javac")
    if p:
        return p
    env = os.environ.get("JAVAC")
    if env and os.path.isfile(env):
        return env
    sys.exit("找不到 javac，请装 JDK 17+ 并确保在 PATH 里，或设置环境变量 JAVAC")


def sources():
    out = []
    for base in SRC_DIRS:
        for dp, _, fn in os.walk(base):
            for f in fn:
                if f.endswith(".java"):
                    out.append(os.path.join(dp, f))
    return out


def compile_for(label, jar):
    """用目标 jar 当 classpath 编译替换类。返回输出目录，失败返回 None。"""
    out = os.path.join(WORK, label)
    if os.path.isdir(out):
        shutil.rmtree(out)
    os.makedirs(out, exist_ok=True)
    gson = os.path.join(UPSTREAM, "gson-2.11.0.jar")
    if not os.path.isfile(gson):
        sys.exit("缺少 %s，先跑 tools/fetch_upstream.py" % gson)
    cmd = [javac(), "-encoding", "UTF-8", "-nowarn", "--release=8",
           "-cp", os.pathsep.join([jar, gson]), "-d", out] + sources()
    r = subprocess.run(cmd, capture_output=True, text=True, errors="replace")
    if r.returncode != 0:
        print("  !! 编译失败（说明该平台内部签名与我方实现不一致）")
        for line in (r.stdout + r.stderr).splitlines()[:12]:
            print("     " + line.strip()[:160])
        return None
    return out


def build(label):
    src = os.path.join(UPSTREAM, "DangoMusic-%s+%s.jar" % (UPSTREAM_VER, label))
    if not os.path.isfile(src):
        print("skip %-16s (没有 %s)" % (label, os.path.basename(src)))
        return None
    print("== %s" % label, end="  ")
    classes = compile_for(label, src)
    if not classes:
        return None

    new_classes = {}
    for dp, _, fn in os.walk(classes):
        for f in fn:
            if f.endswith(".class"):
                p = os.path.join(dp, f)
                rel = os.path.relpath(p, classes).replace("\\", "/")
                with open(p, "rb") as fh:
                    new_classes[rel] = fh.read()

    os.makedirs(OUT_DIR, exist_ok=True)
    dst = os.path.join(OUT_DIR, "DangoMusic-1.5.1-qqmusic+%s.jar" % label)
    replaced, added, tuned, invalid = [], [], [], []
    with zipfile.ZipFile(src) as zin, zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED) as zout:
        for item in zin.infolist():
            name = item.filename
            if name in qqpatch.DELETE:
                continue
            data = zin.read(name)
            if name in new_classes:
                data = new_classes[name]
                replaced.append(name)
            elif name in qqpatch.STRINGS:
                data, n = qqpatch.rewrite_strings(data, qqpatch.STRINGS[name])
                if n:
                    tuned.append(name)
            elif name in META:
                data = data.replace(OLD_VER, NEW_VER).replace(OLD_DESC, NEW_DESC)
                tuned.append(name)
            if name.endswith(".class") and not qqpatch.validate_class(data):
                invalid.append(name)
            info = zipfile.ZipInfo(name, date_time=item.date_time)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = item.external_attr
            zout.writestr(info, data)

        for rel, data in sorted(new_classes.items()):
            if rel not in replaced:
                if not qqpatch.validate_class(data):
                    invalid.append(rel)
                # 固定时间戳，保证相同输入得到逐字节相同的产物（可复现构建）
                info = zipfile.ZipInfo(rel, date_time=(1980, 1, 1, 0, 0, 0))
                info.compress_type = zipfile.ZIP_DEFLATED
                info.external_attr = 0o644 << 16
                zout.writestr(info, data)
                added.append(rel)

    if invalid:
        print("结构校验失败: %s" % invalid[:5])
        return None
    print("替换 %d / 新增 %d / 文案与元数据 %d -> %s (%.0f KB)"
          % (len(replaced), len(added), len(tuned), os.path.basename(dst),
             os.path.getsize(dst) / 1024.0))
    return dst


def main():
    only = sys.argv[1] if len(sys.argv) > 1 else None
    built = []
    for t in TARGETS:
        if only and only not in t:
            continue
        p = build(t)
        if p:
            built.append(p)
    print("\n构建完成 %d 个：" % len(built))
    for p in built:
        print("  " + os.path.relpath(p, ROOT))


if __name__ == "__main__":
    main()
