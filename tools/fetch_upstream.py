#!/usr/bin/env python3
"""下载构建所需的上游文件：DangoMusic 各平台 jar、gson、ASM。

用法:
    python tools/fetch_upstream.py                 # 用系统/环境变量里的代理
    python tools/fetch_upstream.py --proxy 127.0.0.1:7890
"""

import argparse
import json
import os
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
OUT = os.path.join(ROOT, "upstream")

UPSTREAM_VER = "1.5.0"
REPO = "Sorihiko/DangoMusic"

PLATFORMS = [
    "1.20.1-fabric", "1.20.1-forge",
    "1.21.1-fabric", "1.21.1-neoforge",
    "1.21.11-fabric", "1.21.11-neoforge",
    "26.2-fabric", "26.2-neoforge",
    "26.3-fabric", "26.3-neoforge",
]

# 编译替换类需要 gson；校验工具需要 ASM（只需 asm 核心包）
MAVEN = [
    ("gson-2.11.0.jar",
     "https://repo1.maven.org/maven2/com/google/code/gson/gson/2.11.0/gson-2.11.0.jar"),
    ("asm-9.10.1.jar",
     "https://repo1.maven.org/maven2/org/ow2/asm/asm/9.10.1/asm-9.10.1.jar"),
    ("asm-9.9.1.jar",
     "https://repo1.maven.org/maven2/org/ow2/asm/asm/9.9.1/asm-9.9.1.jar"),
    ("asm-9.7.1.jar",
     "https://repo1.maven.org/maven2/org/ow2/asm/asm/9.7.1/asm-9.7.1.jar"),
]


def opener(proxy):
    if proxy:
        p = {"http": "http://" + proxy, "https": "http://" + proxy}
        return urllib.request.build_opener(urllib.request.ProxyHandler(p))
    return urllib.request.build_opener()


def download(op, url, dst, min_size=10000):
    if os.path.isfile(dst) and os.path.getsize(dst) >= min_size:
        print("  已存在 %s (%d 字节)" % (os.path.basename(dst), os.path.getsize(dst)))
        return True
    try:
        req = urllib.request.Request(url, headers={"User-Agent": "dangomusic-qq-builder"})
        with op.open(req, timeout=180) as r:
            data = r.read()
    except Exception as e:
        print("  失败 %s: %s" % (url, e))
        return False
    if len(data) < min_size:
        print("  失败 %s: 内容太小 (%d 字节)" % (url, len(data)))
        return False
    with open(dst, "wb") as f:
        f.write(data)
    print("  下载 %s (%d 字节)" % (os.path.basename(dst), len(data)))
    return True


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--proxy", help="例如 127.0.0.1:7890")
    args = ap.parse_args()
    os.makedirs(OUT, exist_ok=True)
    op = opener(args.proxy)

    print("[1/3] 取 %s 的 Release 资产列表" % UPSTREAM_VER)
    api = "https://api.github.com/repos/%s/releases/tags/v%s" % (REPO, UPSTREAM_VER)
    try:
        req = urllib.request.Request(api, headers={"User-Agent": "builder",
                                                   "Accept": "application/vnd.github+json"})
        with op.open(req, timeout=60) as r:
            rel = json.loads(r.read().decode())
        assets = {a["name"]: a["browser_download_url"] for a in rel["assets"]}
    except Exception as e:
        print("  取不到 Release 列表：%s" % e)
        print("  你也可以自己从 https://github.com/%s/releases 下载，放进 upstream/ 目录" % REPO)
        assets = {}

    print("[2/3] 下载上游平台 jar")
    for p in PLATFORMS:
        name = "DangoMusic-%s+%s.jar" % (UPSTREAM_VER, p)
        dst = os.path.join(OUT, name)
        if name in assets:
            download(op, assets[name], dst, 1000000)
        elif not os.path.isfile(dst):
            print("  缺少 %s（需要手动放进 upstream/）" % name)

    print("[3/3] 下载编译/校验依赖")
    for name, url in MAVEN:
        dst = os.path.join(OUT, name)
        if name.startswith("asm-") and any(
                os.path.isfile(os.path.join(OUT, n)) for n in
                ["asm-9.10.1.jar", "asm-9.9.1.jar", "asm-9.7.1.jar"]):
            continue                     # 有一个 asm 就够了
        download(op, url, dst, 50000)

    jars = [n for n in sorted(os.listdir(OUT)) if n.endswith(".jar")]
    print("\nupstream/ 里现有 %d 个文件：" % len(jars))
    for n in jars:
        print("  %-42s %8.1f KB" % (n, os.path.getsize(os.path.join(OUT, n)) / 1024.0))


if __name__ == "__main__":
    main()
