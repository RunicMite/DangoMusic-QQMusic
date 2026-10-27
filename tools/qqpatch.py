"""QQ 音乐补丁的核心工具函数：常量池读写、class 结构校验、界面文案替换表。

只依赖标准库，不依赖任何 Minecraft / Fabric 的东西。
"""

import struct

# 常量池各项除 tag 本身外的固定长度（tag 1 是 UTF8，长度可变，单独处理）
CP_SIZES = {3: 5, 4: 5, 5: 9, 6: 9, 7: 3, 8: 3, 15: 4, 16: 3, 19: 3, 20: 3,
            9: 5, 10: 5, 11: 5, 12: 5, 17: 5, 18: 5}


def parse_cp(data):
    """拆出常量池，返回 (entries, cp_bytes, rest)。

    entries 是 [(tag, raw_bytes_including_tag), ...]，
    注意 long/double 会占两个槽位，所以 entries 数量可能小于常量池声明数量。
    """
    if data[:4] != b"\xca\xfe\xba\xbe":
        raise ValueError("not a class file")
    count = struct.unpack(">H", data[8:10])[0]
    pos = 10
    entries = []
    i = 1
    while i < count:
        tag = data[pos]
        if tag == 1:
            ln = struct.unpack(">H", data[pos + 1:pos + 3])[0]
            raw = data[pos:pos + 3 + ln]
            entries.append([tag, raw])
            pos += 3 + ln
        elif tag in (3, 4):
            entries.append([tag, data[pos:pos + 5]])
            pos += 5
        elif tag in (5, 6):
            entries.append([tag, data[pos:pos + 9]])
            pos += 9
            i += 1
        elif tag in (7, 8, 16, 19, 20):
            entries.append([tag, data[pos:pos + 3]])
            pos += 3
        elif tag in (9, 10, 11, 12, 17, 18):
            entries.append([tag, data[pos:pos + 5]])
            pos += 5
        elif tag == 15:
            entries.append([tag, data[pos:pos + 4]])
            pos += 4
        else:
            raise ValueError("unknown constant pool tag %d" % tag)
        i += 1
    return entries, data[10:pos], data[pos:]


def cp_end(data):
    """走一遍常量池，返回常量池结束处的偏移。"""
    count = struct.unpack(">H", data[8:10])[0]
    pos = 10
    i = 1
    while i < count:
        tag = data[pos]
        if tag == 1:
            ln = struct.unpack(">H", data[pos + 1:pos + 3])[0]
            pos += 3 + ln
        else:
            pos += CP_SIZES[tag]
            if tag in (5, 6):
                i += 1
        i += 1
    return pos


def validate_class(data):
    """严格的结构校验：按格式逐段走完，偏移必须正好等于文件长度。

    这是防止"常量池计数写错 → 游戏启动即 ArrayIndexOutOfBoundsException"的关键检查。
    """
    try:
        if data[:4] != b"\xca\xfe\xba\xbe":
            return False
        pos = cp_end(data)
        pos += 6                                     # access_flags, this_class, super_class
        ifc = struct.unpack(">H", data[pos:pos + 2])[0]
        pos += 2 + 2 * ifc                           # interfaces
        for _ in range(2):                           # fields, methods
            n = struct.unpack(">H", data[pos:pos + 2])[0]
            pos += 2
            for _ in range(n):
                pos += 6                             # access, name, descriptor
                ac = struct.unpack(">H", data[pos:pos + 2])[0]
                pos += 2
                for _ in range(ac):
                    al = struct.unpack(">I", data[pos + 2:pos + 6])[0]
                    pos += 6 + al
        ac = struct.unpack(">H", data[pos:pos + 2])[0]
        pos += 2
        for _ in range(ac):
            al = struct.unpack(">I", data[pos + 2:pos + 6])[0]
            pos += 6 + al
        return pos == len(data)
    except Exception:
        return False


def rewrite_strings(data, mapping):
    """按 UTF8 常量做精确替换，返回 (new_bytes, 命中次数)。

    只替换长度不变或变化的"文本常量"，常量池计数原样保留。
    """
    entries, _, rest = parse_cp(data)
    changed = 0
    out = bytearray()
    out += data[:8]
    out += data[8:10]                                # 常量池计数原样抄（long/double 占两槽）
    for tag, raw in entries:
        if tag == 1:
            ln = struct.unpack(">H", raw[1:3])[0]
            try:
                text = raw[3:3 + ln].decode("utf-8")
            except UnicodeDecodeError:
                out += raw
                continue
            if text in mapping:
                encoded = mapping[text].encode("utf-8")
                out += bytes([1]) + struct.pack(">H", len(encoded)) + encoded
                changed += 1
                continue
        out += raw
    out += rest
    return bytes(out), changed


# ---------------------------------------------------------------------------
# 界面文案替换表
# ---------------------------------------------------------------------------
# 键是常量池里的原文，值是 QQ 音乐版文案。
STRINGS = {
    "me/sorihiko/dango/musicui/LoginDialog.class": {
        "Log in to NetEase": "Log in to QQ Music",
        "打开手机网易云音乐 App 扫码": "用手机 QQ 扫码登录",
        "已扫码，请在手机上确认登录": "已扫码，在手机 QQ 里点确认",
    },
    "me/sorihiko/dango/musicui/Sidebar.class": {
        "Log in to NetEase": "Log in to QQ Music",
    },
    "me/sorihiko/dango/musicui/PartyPage.class": {
        "Everyone streams from their own NetEase account, so VIP songs need VIP on each side.":
            "Everyone streams from their own QQ Music account, so VIP songs need VIP on each side.",
    },
    "me/sorihiko/dango/module/impls/music/LyricsModule.class": {
        "Split two lines written as one (NetEase often does this) so they appear separately":
            "Split two lines written as one (QQ Music often does this) so they appear separately",
    },
    # 注意：模块名（@ModuleMeta 的 "NeteaseMusic"）绝对不要改。
    # 配置是按 module.name 存进 config.json 的，改名会让 "enabled" 和快捷键设置全部失效，
    # 模块回到默认关闭状态，表现就是"按 M 没反应"。
}

# 被替换实现取代后不再需要的类（保留会变成孤儿，甚至因签名不匹配导致校验失败）
DELETE = [
    "me/sorihiko/dango/music/NeteaseTrack$Companion.class",
    "me/sorihiko/dango/lyrics/LyricsProvider$parseLrc$$inlined$sortBy$1.class",
    "me/sorihiko/dango/lyrics/LyricsProvider$parseYrc$$inlined$sortBy$1.class",
]
