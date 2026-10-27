# Dango Music · QQ 音乐版

把 [DangoMusic](https://github.com/Sorihiko/DangoMusic) 的音乐源从**网易云音乐**换成 **QQ 音乐**的改版。
界面、3D 歌词、歌卡、灵动岛、一起听全部保持原样，只替换「音乐数据来源」这一层。

*A patch for [DangoMusic](https://github.com/Sorihiko/DangoMusic) that swaps the NetEase Cloud Music backend for
QQ Music. The UI, 3D in-world lyrics, song card, Dynamic Island and Listen Together are untouched —
only the music data layer is replaced. Supports Minecraft 1.20.1 (Fabric & Forge), 1.21.1, 1.21.11,
26.2, 26.3 (Fabric & NeoForge).*

> **非官方改版**：本项目与腾讯、QQ 音乐、网易云音乐、Mojang 均无关联，使用的是非公开接口，仅供学习与自用。
> 请自备 QQ 音乐账号，不要用它做商业用途或大规模分发。

---

## 支持平台

| Minecraft | Fabric | Forge / NeoForge |
| --- | --- | --- |
| 1.20.1 | ✅ | ✅ Forge |
| 1.21.1 | ✅ | ✅ NeoForge |
| 1.21.11 | ✅ | ✅ NeoForge |
| 26.2 | ✅ | ✅ NeoForge |
| 26.3 | ✅ | ✅ NeoForge |

上游为这些版本同时发布了 Fabric 与 Forge/NeoForge 的 jar，本仓库对**每一个**平台重新编译并拼接，
10 个产物全部通过结构校验与内部引用校验（方法见 [docs/技术实现.md](docs/技术实现.md)）。
上游另有 1.8.9 Forge 版本，本仓库暂未包含。

## 下载与安装

1. 去 [Releases](../../releases) 按你的游戏版本 + 加载器下载**一个** jar（`DangoMusic-1.5.1-qqmusic+<版本>-<加载器>.jar`）。
2. 放进 `.minecraft/mods/`。
   - 之前装过原版 DangoMusic 的话**先删掉**：两者 `id` 都是 `dangomusic`，同时放会冲突。
   - Fabric 端需要 Fabric API；Forge / NeoForge 端不需要。
3. 启动游戏，按 `M` 打开播放器。

详细步骤见 [docs/安装与使用.md](docs/安装与使用.md)。

## 登录 QQ 音乐（必需）

播放器左下角 `Log in to QQ Music` → 用**手机 QQ 扫码** → 手机上点确认。
凭据写入 `config/dangomusic/qqmusic_cookie.txt`，约 3 天过期，过期后再扫一次。

不登录也能进界面，但拿不到播放地址（大多数歌没声音）。会员歌、无损音质需要你自己的 QQ 音乐会员。

## 一起听（Listen Together）

一起听用的是 Firebase Realtime Database。**同一个房间里的人必须连同一个 Firebase 项目**，
所以你需要自己有（或共用）一个项目：

1. 建项目 → 开 Realtime Database（区域建议 `asia-southeast1`）
2. Authentication → 启用 **Anonymous**
3. 写入规则（登录即可读写）：
   ```json
   {
     "rules": {
       "rooms": { "$code": { ".read": "auth != null", ".write": "auth != null" } },
       "clock": { "$uid": { ".read": "auth != null", ".write": "auth != null",
                            ".validate": "auth.uid === $uid" } }
     }
   }
   ```
4. 把 `apiKey` 与 `databaseUrl` 填进 `config/dangomusic/firebase.properties`
   （模板见 [tools/firebase.properties.example](tools/firebase.properties.example)）

> ⚠️ **`firebase.properties` 不要提交进仓库、不要公开发布**。里面的 Web API Key 等于你数据库的钥匙，
> 泄露后任何人都能读写你的库、刷你的免费额度（100 并发连接 / 1 GB 存储 / 每月 10 GB 下载）。
> 分发模组给朋友时，让他们各自建项目或私下另发配置文件。

中国大陆直连不了 Google 的认证与数据库域名，需要代理。模组本身支持配置代理：

```properties
proxy=127.0.0.1:7890
proxyType=http
```

完整说明与排错见 [docs/一起听.md](docs/一起听.md)。

> 不写配置时，模组会回落到上游 jar 内置的公共项目（与原版行为一致）。那个项目在国内同样需要代理，
> 而且房间里所有人都得用同一个项目——想自己掌控就按上面建一个。

## 从源码复现构建

这个仓库不是一个能直接 `gradle build` 的工程，而是**把上游已发布的 jar 打补丁**的完整管线。
原因是这套改版替换的是若干编译好的类：用目标平台 jar 自身当 classpath 重新编译替换类，
签名对不上就编译失败，因此每个平台都是"原生适配"而不是硬塞。

```bash
# 1. 下载上游 jar 与编译/校验依赖（需要能访问 GitHub 与 Maven Central）
python tools/fetch_upstream.py --proxy 127.0.0.1:7890   # 走代理时加这个参数

# 2. 构建全部平台（或指定一个：python tools/build_all.py 26.3-fabric）
python tools/build_all.py

# 3. 校验：ASM 结构 + 全 jar 内部引用（与上游基线对比）+ 文案 + 版本号
python tools/verify_all.py
```

需要 JDK 17+（`javac`/`java` 在 PATH 里）。产物在 `build/`。

## 仓库结构

```
src/main/java/me/sorihiko/dango/   替换实现（QQ 音乐接口、扫码登录、歌词、Firebase 一起听）
tools/qqpatch.py                   常量池改写与结构校验工具、文案替换表
tools/build_all.py                 对每个平台 jar 重跑补丁管线（可复现构建）
tools/verify_all.py                产物校验
tools/inspect_upstream.py          上游升级前的契约检查（目标类/枚举/文案是否还成立）
tools/LinkCheck.java               全 jar 内部引用校验（ASM）
tools/AsmVerify.java               逐类结构校验（ASM）
tools/fetch_upstream.py            下载上游 jar / gson / ASM
tools/firebase.properties.example  一起听配置模板
docs/                              安装、一起听、技术实现、发布步骤
```

> 构建是可复现的：同样的上游 jar + 同样的源码，跑 `tools/build_all.py` 得到**逐字节相同**的产物，
> 因此 Release 里的 SHA256 可以被任何人独立核对。

## 修改说明（GPL-3.0 第 5 条要求）

本仓库是对 GPL-3.0 项目 DangoMusic 的修改版本，修改内容：

- 替换音乐数据层：`NeteaseApi` / `NeteaseLogin` / `NeteaseTrack` / `NeteasePlaylist` / `LyricsProvider`
  改为调用 QQ 音乐接口（`musicu.fcg` 等），并新增 QQ 扫码登录状态机。
- 重写 `party/Firebase`：等价 API，增加 HTTP 代理支持、超时与轮询间隔可配、错误提示更明确。
- 改写界面文案常量（登录按钮、扫码提示、一起听说明等），游戏内版本号改为 `1.5.1-qqmusic`。
- **未修改**：界面布局与渲染、3D 歌词、歌卡、灵动岛、模块系统与其他所有逻辑。

修改日期：2026-10。完整对应源码 = 上游源码（见上方链接）+ 本仓库 `src/`、`tools/`。

## 已知限制

- QQ 音乐没有公开 API，接口随时可能失效；失效时需要更新实现。
- 一起听同步的是 QQ 音乐的 `songid`，房间里的人需要用**同一版本**的模组。
- 模块内部名字仍是 `NeteaseMusic`（`config.json` 的键），**不要改**，改了会导致"按 M 没反应"。
- 登录态约 3 天过期，需要重新扫码。

## 致谢与许可

- 原始项目：[Sorihiko/DangoMusic](https://github.com/Sorihiko/DangoMusic)，作者 Sorihiko。
- 本改版同样按 **GPL-3.0** 分发，见 [LICENSE](LICENSE)。如果你再分发，请保留同样的协议并公开你的修改。
- 代码签名与混淆相关工具：[OW2 ASM](https://asm.ow2.io/)（BSD）。
