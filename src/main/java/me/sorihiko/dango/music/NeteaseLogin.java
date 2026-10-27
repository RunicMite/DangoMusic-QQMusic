package me.sorihiko.dango.music;

import me.sorihiko.dango.DangoLogger;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * QQ 音乐扫码登录。
 *
 * 流程（全部在后台线程跑，界面线程只读 getState()/getQrImage()）：
 *   1. 已经有可用的 Cookie → 直接 SUCCESS
 *   2. ssl.ptlogin2.qq.com/ptqrshow 取二维码图片 + qrsig
 *   3. 轮询 ptqrlogin：66 未扫描 / 67 已扫码待确认 / 65 过期 / 0 成功
 *   4. 成功后跟 check_sig，拿到 graph.qq.com 的 p_skey / p_uin / pt4_token
 *   5. POST graph.qq.com/oauth2.0/authorize（模拟「授权并同意」，g_tk = hash33(p_skey)）
 *      → 302 到 y.qq.com 的 wx_redirect，Location 里带 code
 *   6. POST u.y.qq.com musicu.fcg 的 QQConnectLogin.LoginServer/QQLogin {"code":...}
 *      → 拿到 musickey，写成和手动粘贴一样的 Cookie 文件
 *   7. validateLogin() 通了就是 SUCCESS
 */
public final class NeteaseLogin {

    /** 与界面层约定的状态（顺序不能改，LoginDialog 按 ordinal 做分支） */
    public enum State {
        IDLE, LOADING, WAITING, SCANNED, SUCCESS, EXPIRED, ERROR
    }

    public static final NeteaseLogin INSTANCE = new NeteaseLogin();

    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/124.0 Safari/537.36";
    private static final String APPID = "716027609";
    private static final String DAID = "383";
    private static final String PT_3RD_AID = "100497308";
    private static final String CLIENT_ID = "100497308";
    private static final String LOGIN_JUMP = "https://graph.qq.com/oauth2.0/login_jump";
    private static final String REDIRECT = "https://y.qq.com/portal/wx_redirect.html?login_type=1";
    private static final String SCOPE = "get_user_info,get_app_friends";
    /** 授权页上两个权限勾选框的 id（1010 = 头像昵称，1030 = 好友关系） */
    private static final String OPENAPI = "1010_1030";

    private volatile State state = State.IDLE;
    private volatile BufferedImage qrImage;
    private volatile int qrVersion;
    private volatile int session;
    private volatile String detail = "";

    private NeteaseLogin() {
    }

    // ---------------------------------------------------------------- 对外

    public State getState() {
        return state;
    }

    public BufferedImage getQrImage() {
        return qrImage;
    }

    public int getQrVersion() {
        return qrVersion;
    }

    /** 最近一次失败原因，方便排查（界面不显示，写日志用） */
    public String getDetail() {
        return detail;
    }

    public void start() {
        State now = state;
        // 正在跑就别重开
        if (now == State.LOADING || now == State.WAITING || now == State.SCANNED) return;
        // 已经登录成功且 Cookie 还在，弹窗直接用登录态
        if (now == State.SUCCESS && NeteaseApi.INSTANCE.getLoggedIn()) return;
        int me = ++session;
        state = State.LOADING;
        detail = "";
        Thread t = new Thread(() -> run(me), "Dango-QQLogin");
        t.setDaemon(true);
        t.start();
    }

    public void cancel() {
        session++;
        if (state != State.SUCCESS) state = State.IDLE;
    }

    // ---------------------------------------------------------------- 主流程

    private void run(int me) {
        try {
            // 1) 已有 Cookie 直接过
            NeteaseApi api = NeteaseApi.INSTANCE;
            if (api.getLoggedIn() && api.validateLogin()) {
                DangoLogger.info("QQ Music already logged in, skip QR");
                if (alive(me)) state = State.SUCCESS;
                return;
            }

            Map<String, String> jar = new LinkedHashMap<>();
            String qrsig = fetchQr(jar);
            if (qrsig == null) {
                fail(me, "拿二维码失败");
                return;
            }
            if (!alive(me)) return;
            state = State.WAITING;

            String checkSig = poll(jar, qrsig, me);
            if (checkSig == null) return;      // 已经设好 EXPIRED/ERROR/被取消

            if (!alive(me)) return;
            state = State.LOADING;
            get(jar, checkSig, LOGIN_JUMP, null);      // 拿 graph.qq.com 的 p_skey / p_uin

            String code = consent(jar);
            if (code == null) {
                fail(me, "授权失败，没拿到 code");
                return;
            }
            if (!alive(me)) return;

            String cookie = qqLogin(jar, code);
            if (cookie == null) {
                fail(me, "换取 music key 失败");
                return;
            }
            api.saveCookie(cookie);
            if (api.validateLogin()) {
                DangoLogger.info("QQ Music QR login OK: {} ({})",
                        api.getNickname(), api.getUserId());
                if (alive(me)) state = State.SUCCESS;
            } else {
                fail(me, "music key 拿到了但校验没过");
            }
        } catch (Throwable t) {
            DangoLogger.warn("QQ Music QR login error: {}", t.toString());
            fail(me, t.toString());
        }
    }

    private boolean alive(int me) {
        return session == me;
    }

    private void fail(int me, String why) {
        detail = why;
        DangoLogger.warn("QQ Music QR login failed: {}", why);
        if (alive(me)) state = State.ERROR;
    }

    // ---------------------------------------------------------------- 1. 二维码

    private String fetchQr(Map<String, String> jar) {
        String url = "https://ssl.ptlogin2.qq.com/ptqrshow?appid=" + APPID + "&e=2&l=M&s=3&d=72&v=4&t="
                + System.currentTimeMillis() + "&daid=" + DAID + "&pt_3rd_aid=" + PT_3RD_AID
                + "&u1=" + enc(LOGIN_JUMP);
        byte[] png = get(jar, url, "https://y.qq.com/", null);
        if (png == null || png.length < 100) return null;
        try {
            BufferedImage raw = ImageIO.read(new ByteArrayInputStream(png));
            if (raw == null) return null;
            qrImage = scale(raw, 3);
            qrVersion++;
        } catch (Exception e) {
            DangoLogger.warn("QQ Music QR image decode failed: {}", e.toString());
            return null;
        }
        return jar.get("qrsig");
    }

    /** 二维码放大 + 补白边，游戏里更好扫 */
    private static BufferedImage scale(BufferedImage src, int factor) {
        int pad = 24;
        int w = src.getWidth() * factor;
        int h = src.getHeight() * factor;
        BufferedImage out = new BufferedImage(w + pad * 2, h + pad * 2, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = out.createGraphics();
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, out.getWidth(), out.getHeight());
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                java.awt.RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(src, pad, pad, w, h, null);
        g.dispose();
        return out;
    }

    // ---------------------------------------------------------------- 2. 轮询

    private String poll(Map<String, String> jar, String qrsig, int me) {
        long token = hash33(qrsig, 0);
        for (int i = 0; i < 120; i++) {
            if (!alive(me)) return null;
            String url = "https://ssl.ptlogin2.qq.com/ptqrlogin?u1=" + enc(LOGIN_JUMP)
                    + "&ptqrtoken=" + token + "&ptredirect=0&h=1&t=1&g=1&from_ui=1&ptlang=2052"
                    + "&action=0-0-" + System.currentTimeMillis() + "&js_ver=20102616&js_type=1"
                    + "&login_sig=&pt_uistyle=40&aid=" + APPID + "&daid=" + DAID + "&pt_3rd_aid=" + PT_3RD_AID;
            byte[] body = get(jar, url, "https://y.qq.com/", null);
            String text = body == null ? "" : new String(body, StandardCharsets.UTF_8);
            List<String> parts = quoted(text);
            String ret = parts.isEmpty() ? "" : parts.get(0);
            if ("65".equals(ret)) {
                state = State.EXPIRED;
                return null;
            }
            if ("67".equals(ret)) {
                if (state != State.SCANNED) state = State.SCANNED;
            } else if ("0".equals(ret)) {
                return parts.size() > 2 ? parts.get(2) : null;
            } else if (!"66".equals(ret) && !ret.isEmpty()) {
                DangoLogger.warn("QQ Music qr poll unexpected: {}", head(text, 120));
                state = State.ERROR;
                return null;
            }
            try {
                Thread.sleep(1500L);
            } catch (InterruptedException e) {
                return null;
            }
        }
        state = State.EXPIRED;
        return null;
    }

    // ---------------------------------------------------------------- 3. 授权

    private String consent(Map<String, String> jar) {
        String pskey = jar.get("p_skey");
        if (pskey == null || pskey.isEmpty()) return null;
        String ui = UUID.randomUUID().toString().replace("-", "");
        jar.put("ui", ui);
        StringBuilder form = new StringBuilder();
        add(form, "response_type", "code");
        add(form, "client_id", CLIENT_ID);
        add(form, "redirect_uri", REDIRECT);
        add(form, "scope", SCOPE);
        add(form, "state", "dango" + System.currentTimeMillis());
        add(form, "switch", "");
        add(form, "from_ptlogin", "1");
        add(form, "src", "1");
        add(form, "update_auth", "1");
        add(form, "openapi", OPENAPI);
        add(form, "g_tk", String.valueOf(hash33(pskey, 5381)));
        add(form, "auth_time", String.valueOf(System.currentTimeMillis()));
        add(form, "ui", ui);

        Reply r = request(jar, "POST",
                "https://graph.qq.com/oauth2.0/authorize?" + form,
                "https://graph.qq.com/oauth2.0/show?which=Login&display=pc",
                form.toString().getBytes(StandardCharsets.UTF_8), true);
        if (r != null && r.location != null) {
            String code = find(r.location, "[?&]code=([0-9A-Za-z]+)");
            if (code != null) return code;
            DangoLogger.warn("QQ Music authorize redirected without code: {}", head(r.location, 200));
        }
        return null;
    }

    // ---------------------------------------------------------------- 4. 换 key

    private String qqLogin(Map<String, String> jar, String code) {
        String uin = digits(jar.get("p_uin"));
        String req = "{\"comm\":{\"g_tk\":5381,\"platform\":\"yqq.json\",\"ct\":24,\"cv\":0,\"uin\":\""
                + uin + "\",\"format\":\"json\",\"needNewCode\":0},"
                + "\"req\":{\"module\":\"QQConnectLogin.LoginServer\",\"method\":\"QQLogin\","
                + "\"param\":{\"code\":\"" + code + "\"}}}";
        Reply r = request(jar, "POST", "https://u.y.qq.com/cgi-bin/musicu.fcg", "https://y.qq.com/",
                req.getBytes(StandardCharsets.UTF_8), false);
        if (r == null || r.body == null) return null;
        String text = new String(r.body, StandardCharsets.UTF_8);
        String musickey = jsonText(text, "musickey");
        if (musickey == null || musickey.isEmpty()) {
            DangoLogger.warn("QQ Music QQLogin failed: {}", head(text, 300));
            return null;
        }
        String musicid = jsonText(text, "musicid");
        if (musicid == null || musicid.isEmpty() || "0".equals(musicid)) musicid = uin;
        String refresh = jsonText(text, "refresh_token");

        StringBuilder cookie = new StringBuilder();
        cookie.append("uin=").append(musicid);
        cookie.append("; qqmusic_key=").append(musickey);
        cookie.append("; qm_keyst=").append(musickey);
        cookie.append("; psrf_musickey_createtime=").append(System.currentTimeMillis() / 1000L);
        if (refresh != null && !refresh.isEmpty()) cookie.append("; psrf_qqrefresh_token=").append(refresh);
        DangoLogger.info("QQ Music QR login got musickey ({} chars)", musickey.length());
        return cookie.toString();
    }

    // ---------------------------------------------------------------- HTTP

    private static class Reply {
        int code;
        String location;
        byte[] body;
    }

    private byte[] get(Map<String, String> jar, String url, String referer, String accept) {
        Reply r = request(jar, "GET", url, referer, null, false);
        return r == null ? null : r.body;
    }

    private Reply request(Map<String, String> jar, String method, String url, String referer,
                          byte[] body, boolean form) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setRequestMethod(method);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Accept", form ? "*/*"
                    : "text/html,application/xhtml+xml,application/json,*/*;q=0.8");
            conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
            if (referer != null) conn.setRequestProperty("Referer", referer);
            String ck = cookieHeader(jar);
            if (!ck.isEmpty()) conn.setRequestProperty("Cookie", ck);
            if (body != null) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type",
                        form ? "application/x-www-form-urlencoded" : "application/json;charset=UTF-8");
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body);
                }
            }
            Reply r = new Reply();
            r.code = conn.getResponseCode();
            r.location = conn.getHeaderField("Location");
            // 收 Cookie：空值一般是别的域的删除指令，不能覆盖已拿到的有效值
            for (Map.Entry<String, List<String>> e : conn.getHeaderFields().entrySet()) {
                if (e.getKey() == null || !e.getKey().equalsIgnoreCase("Set-Cookie")) continue;
                for (String v : e.getValue()) {
                    String first = v.split(";")[0];
                    int eq = first.indexOf('=');
                    if (eq <= 0) continue;
                    String name = first.substring(0, eq).trim();
                    String value = first.substring(eq + 1).trim();
                    if (value.isEmpty()) continue;
                    jar.put(name, value);
                }
            }
            InputStream in = r.code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (in != null) {
                try (InputStream is = in) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = is.read(buf)) > 0) out.write(buf, 0, n);
                    r.body = out.toByteArray();
                }
            }
            return r;
        } catch (Exception e) {
            DangoLogger.warn("QQ Music login request failed ({}): {}", head(url, 80), e.toString());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String cookieHeader(Map<String, String> jar) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : jar.entrySet()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- 小工具

    /** QQ 前端的 g_tk：从 5381 起累加 (h<<5)+c，32 位回绕后 & 0x7fffffff */
    private static long hash33(String s, int seed) {
        int h = seed;
        for (int i = 0; i < s.length(); i++) h += (h << 5) + s.charAt(i);
        return h & 0x7fffffffL;
    }

    private static List<String> quoted(String text) {
        List<String> out = new java.util.ArrayList<>();
        Matcher m = Pattern.compile("'([^']*)'").matcher(text);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    private static String find(String text, String regex) {
        if (text == null) return null;
        Matcher m = Pattern.compile(regex).matcher(text);
        return m.find() ? m.group(1) : null;
    }

    private static String jsonText(String json, String key) {
        return find(json, "\"" + key + "\"\\s*:\\s*\"([^\"]*)\"");
    }

    private static String digits(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') sb.append(c);
        }
        return sb.toString();
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return s;
        }
    }

    private static void add(StringBuilder sb, String k, String v) {
        if (sb.length() > 0) sb.append('&');
        sb.append(k).append('=').append(enc(v));
    }

    private static String head(String s, int n) {
        if (s == null) return "null";
        s = s.replace("\r", " ").replace("\n", " ");
        return s.length() <= n ? s : s.substring(0, n);
    }

    /** 供调试：把当前会话信息打到日志 */
    public void debugState() {
        DangoLogger.info("QQ Music QR login state={} detail={} qr={}", state,
                detail.isEmpty() ? "-" : detail,
                qrImage == null ? "none" : qrImage.getWidth() + "x" + qrImage.getHeight());
    }

    static File cookieFileForDebug() {
        return new File("config/dangomusic/qqmusic_cookie.txt");
    }
}
