# 更新日志

## 1.5.1-qqmusic（2026-10）

首个公开版本。基于上游 **1.5.0** 的 10 个平台 jar 分别适配。

**平台**

- 1.20.1：Fabric、Forge
- 1.21.1：Fabric、NeoForge
- 1.21.11：Fabric、NeoForge
- 26.2：Fabric、NeoForge
- 26.3：Fabric、NeoForge

**音乐**

- 音乐源从网易云音乐换成 QQ 音乐：搜索、歌单、榜单、推荐、曲目、歌词、播放地址。
- 播放地址优先 320k MP3（`M800`），需要登录态。
- 支持手机 QQ 扫码登录，凭据存 `config/dangomusic/qqmusic_cookie.txt`（约 3 天有效）。

**一起听**

- Firebase 实现重写为等价 API，新增：
  - HTTP 代理支持（`proxy` / `proxyType`）
  - 可配置连接/读取超时与轮询间隔（`connectTimeout` / `readTimeout` / `pollMs`）
  - 更明确的失败提示（代理不通 / 规则拒绝 / 匿名登录未启用可区分）
- 修复 `HttpURLConnection` 不支持 `PATCH` 的问题（改用 `POST` + `X-HTTP-Method-Override`）。

**界面**

- 登录相关文案改为 QQ 音乐版（登录按钮、扫码提示、二维码过期）。
- 一起听页面说明改为"各人用自己的 QQ 音乐账号，会员歌需各自有会员"。

**校验**

- 10/10 平台产物通过 class 结构校验（ASM）与全 jar 内部引用校验（与上游基线对比无新增悬空引用）。
- 26.3 Fabric 版用真实账号验证：登录态识别、歌单/榜单/推荐/曲目、歌词、320k 播放地址；
  一起听链路（匿名登录、时钟对齐、建房、局部更新、轮询监听、删房）端到端跑通。
