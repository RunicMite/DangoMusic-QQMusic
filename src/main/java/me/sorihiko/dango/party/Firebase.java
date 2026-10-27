package me.sorihiko.dango.party;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.sorihiko.dango.DangoLogger;
import me.sorihiko.dango.DangoMusic;
import me.sorihiko.dango.shadow.kotlin.Unit;
import me.sorihiko.dango.shadow.kotlin.jvm.functions.Function0;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Firebase Realtime Database 的极简 REST 客户端（「一起听」用）。
 *
 * 这是原版 Firebase 类的等价实现，行为保持一致（匿名登录 + REST + 3 秒轮询 + ETag 条件写），
 * 额外增加了三件原版没有的东西：
 *
 *   1. proxy / proxyHost+proxyPort / proxyType —— 走本地 HTTP / SOCKS 代理。
 *      Google 的 identitytoolkit.googleapis.com 在国内直连不通，没有这个就建不了房间。
 *   2. authBase / tokenBase —— 认证域名可以换成自己的中转，不必动代码。
 *   3. connectTimeout / readTimeout / pollMs —— 网络慢的时候可以放宽。
 *
 * 配置读 config/dangomusic/firebase.properties（不存在就用 jar 内置的默认值并生成一份模板）。
 */
public final class Firebase {

    public static final Firebase INSTANCE = new Firebase();

    private static final String DEFAULT_AUTH_BASE = "https://identitytoolkit.googleapis.com";
    private static final String DEFAULT_TOKEN_BASE = "https://securetoken.googleapis.com";
    private static final String AUTH_FILE = "firebase_auth.json";
    private static final String CONF_FILE = "firebase.properties";

    private static final String TEMPLATE =
            "# DangoMusic「一起听」使用的 Firebase 配置。\n"
            + "# 下面两项是作者的公共项目（原版内置的默认值），可以换成自己的 Firebase 项目。\n"
            + "apiKey=AIzaSyA6fgB5T0Yt4k7N3mUo1ywO8l_AANqQRXs\n"
            + "databaseUrl=https://imperialseal-9f8c2-default-rtdb.firebaseio.com\n"
            + "\n"
            + "# 国内直连 Google 不通，需要写一个本地代理（Clash / V2Ray 的 HTTP 端口）：\n"
            + "# proxy=127.0.0.1:7890\n"
            + "# proxyType=http\n"
            + "#\n"
            + "# 不要系统代理、只想用 TUN/全局模式时，上面 proxy 留空即可。\n"
            + "#\n"
            + "# 认证域名（一般不用改）：\n"
            + "# authBase=https://identitytoolkit.googleapis.com\n"
            + "# tokenBase=https://securetoken.googleapis.com\n"
            + "#\n"
            + "# 超时与轮询间隔（毫秒）：\n"
            + "# connectTimeout=10000\n"
            + "# readTimeout=20000\n"
            + "# pollMs=3000\n";

    private static volatile String uid = "";
    private static volatile String idToken = "";
    private static volatile String refreshToken = "";
    private static volatile long tokenExpiresAt;
    private static volatile long clockOffset;
    private static volatile int lastStatus;

    private static volatile Properties conf;
    private static volatile boolean authLoaded;

    private Firebase() {
    }

    // ---------------------------------------------------------------- 配置

    private static File clientFile(String name) {
        try {
            return DangoMusic.Companion.getInstance().getClientDir().resolve(name).toFile();
        } catch (Throwable t) {
            return new File("config/dangomusic/" + name);
        }
    }

    private static Properties conf() {
        Properties p = conf;
        if (p != null) return p;
        synchronized (Firebase.class) {
            if (conf != null) return conf;
            Properties merged = new Properties();
            // 1) jar 内置默认值
            try (InputStream in = Firebase.class.getResourceAsStream("/" + CONF_FILE)) {
                if (in != null) merged.load(in);
            } catch (Exception ignored) {
            }
            // 2) 用户配置覆盖（没有就写一份模板出来，方便直接改）
            File f = clientFile(CONF_FILE);
            if (!f.isFile()) {
                try {
                    File dir = f.getParentFile();
                    if (dir != null) dir.mkdirs();
                    Files.write(f.toPath(), TEMPLATE.getBytes(StandardCharsets.UTF_8));
                    DangoLogger.info("Created {} - fill in \"proxy\" if Google is unreachable", f);
                } catch (Exception e) {
                    DangoLogger.warn("Write firebase.properties failed: {}", e.toString());
                }
            }
            if (f.isFile()) {
                try (InputStream in = new FileInputStream(f)) {
                    merged.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
                } catch (Exception e) {
                    DangoLogger.warn("Read firebase.properties failed: {}", e.toString());
                }
            }
            conf = merged;
            return merged;
        }
    }

    /** 重新读配置文件（改了代理不用重启游戏，进一次房间前调用即可） */
    public void reload() {
        synchronized (Firebase.class) {
            conf = null;
        }
    }

    private static String prop(String key, String def) {
        String v = conf().getProperty(key);
        return v == null || v.trim().isEmpty() ? def : v.trim();
    }

    private static int num(String key, int def) {
        try {
            String v = conf().getProperty(key);
            return v == null || v.trim().isEmpty() ? def : Integer.parseInt(v.trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static String dbBase() {
        String base = prop("databaseUrl", "");
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base;
    }

    private static String authBase() {
        String base = prop("authBase", DEFAULT_AUTH_BASE);
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base;
    }

    private static String tokenBase() {
        String base = prop("tokenBase", DEFAULT_TOKEN_BASE);
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base;
    }

    /** 从配置里解析代理，没配就是直连 */
    private static Proxy proxy() {
        String raw = prop("proxy", "");
        String scheme = prop("proxyType", "");
        String host = prop("proxyHost", "");
        int port = num("proxyPort", 0);
        if (!raw.isEmpty()) {
            String value = raw;
            int sep = value.indexOf("://");
            if (sep > 0) {
                scheme = value.substring(0, sep);
                value = value.substring(sep + 3);
            }
            if (value.indexOf('@') >= 0) value = value.substring(value.indexOf('@') + 1);
            int colon = value.lastIndexOf(':');
            if (colon > 0) {
                host = value.substring(0, colon);
                try {
                    port = Integer.parseInt(value.substring(colon + 1).trim());
                } catch (Exception e) {
                    port = 0;
                }
            } else {
                host = value;
            }
        }
        if (host == null || host.trim().isEmpty() || port <= 0) return Proxy.NO_PROXY;
        boolean socks = scheme.toLowerCase(java.util.Locale.ROOT).startsWith("socks");
        try {
            return new Proxy(socks ? Proxy.Type.SOCKS : Proxy.Type.HTTP,
                    new InetSocketAddress(host.trim(), port));
        } catch (Exception e) {
            DangoLogger.warn("Bad proxy {}:{} - {}", host, port, e.toString());
            return Proxy.NO_PROXY;
        }
    }

    private static HttpURLConnection open(String url) throws Exception {
        Proxy px = proxy();
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection(px);
        conn.setConnectTimeout(num("connectTimeout", 10000));
        conn.setReadTimeout(num("readTimeout", 20000));
        conn.setInstanceFollowRedirects(false);
        conn.setRequestProperty("User-Agent", "DangoMusic/1.5.1");
        conn.setRequestProperty("Accept", "application/json, text/plain, */*");
        return conn;
    }

    // ---------------------------------------------------------------- 状态

    public boolean getConfigured() {
        return !prop("apiKey", "").isEmpty() && !dbBase().isEmpty();
    }

    public String getUid() {
        return uid;
    }

    public int getLastStatus() {
        return lastStatus;
    }

    public long serverNow() {
        return System.currentTimeMillis() + clockOffset;
    }

    // ---------------------------------------------------------------- 认证

    private static File authFile() {
        return clientFile(AUTH_FILE);
    }

    private static void loadSaved() {
        if (authLoaded) return;
        authLoaded = true;
        File f = authFile();
        if (!f.isFile()) return;
        try {
            String text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            if (text.trim().isEmpty()) return;
            JsonObject o = JsonParser.parseString(text).getAsJsonObject();
            uid = str(o, "uid");
            idToken = str(o, "idToken");
            refreshToken = str(o, "refreshToken");
            tokenExpiresAt = o.has("expiresAt") ? o.get("expiresAt").getAsLong() : 0L;
        } catch (Exception e) {
            DangoLogger.warn("Read {} failed: {}", AUTH_FILE, e.toString());
        }
    }

    private static void save() {
        try {
            JsonObject o = new JsonObject();
            o.addProperty("uid", uid);
            o.addProperty("idToken", idToken);
            o.addProperty("refreshToken", refreshToken);
            o.addProperty("expiresAt", tokenExpiresAt);
            File f = authFile();
            File dir = f.getParentFile();
            if (dir != null) dir.mkdirs();
            Files.write(f.toPath(), o.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            DangoLogger.warn("Save {} failed: {}", AUTH_FILE, e.toString());
        }
    }

    public synchronized boolean ensureAuth() {
        if (!getConfigured()) return false;
        loadSaved();
        long now = System.currentTimeMillis();
        if (!idToken.isEmpty() && now < tokenExpiresAt - 60000L) return true;
        if (!refreshToken.isEmpty() && refresh()) return true;
        return signUp();
    }

    private boolean signUp() {
        String url = authBase() + "/v1/accounts:signUp?key=" + prop("apiKey", "");
        Response r = request("POST", url, "{\"returnSecureToken\":true}", "application/json");
        if (r == null || r.json == null || !r.json.isJsonObject()) {
            DangoLogger.warn("Firebase anonymous sign-in failed (status {})", lastStatus);
            return false;
        }
        JsonObject o = r.json.getAsJsonObject();
        if (!o.has("idToken")) {
            DangoLogger.warn("Firebase sign-in error: {}", o);
            return false;
        }
        uid = str(o, "localId");
        idToken = str(o, "idToken");
        refreshToken = str(o, "refreshToken");
        long expires = o.has("expiresIn") ? parseLong(str(o, "expiresIn"), 3600L) : 3600L;
        tokenExpiresAt = System.currentTimeMillis() + expires * 1000L;
        save();
        DangoLogger.info("Firebase anonymous login ok, uid={}", uid);
        return !uid.isEmpty();
    }

    private boolean refresh() {
        String url = tokenBase() + "/v1/token?key=" + prop("apiKey", "");
        String form = "grant_type=refresh_token&refresh_token=" + enc(refreshToken);
        Response r = request("POST", url, form, "application/x-www-form-urlencoded");
        if (r == null || r.json == null || !r.json.isJsonObject()) return false;
        JsonObject o = r.json.getAsJsonObject();
        String token = o.has("id_token") ? str(o, "id_token") : str(o, "idToken");
        if (token.isEmpty()) {
            DangoLogger.warn("Firebase token refresh failed: {}", o);
            return false;
        }
        idToken = token;
        String rt = o.has("refresh_token") ? str(o, "refresh_token") : str(o, "refreshToken");
        if (!rt.isEmpty()) refreshToken = rt;
        long expires = parseLong(o.has("expires_in") ? str(o, "expires_in") : "3600", 3600L);
        tokenExpiresAt = System.currentTimeMillis() + expires * 1000L;
        save();
        return true;
    }

    /** 用一次写入-读回校准服务器时间，纠偏全靠它 */
    public void syncClock() {
        if (!ensureAuth()) return;
        try {
            long before = System.currentTimeMillis();
            JsonElement ts = put("clock/" + uid, JsonParser.parseString("{\".sv\":\"timestamp\"}"));
            long after = System.currentTimeMillis();
            if (ts == null || !ts.isJsonPrimitive()) return;
            long server = ts.getAsLong();
            clockOffset = server - (before + after) / 2;
            DangoLogger.info("Firebase clock offset {} ms", clockOffset);
        } catch (Exception e) {
            DangoLogger.warn("Firebase clock sync failed: {}", e.toString());
        }
    }

    // ---------------------------------------------------------------- 数据

    private String url(String path) {
        String p = path == null ? "" : path.trim();
        if (!p.startsWith("/")) p = "/" + p;
        return dbBase() + p + ".json?auth=" + idToken;
    }

    public JsonElement get(String path) {
        if (!ensureAuth()) return null;
        Response r = request("GET", url(path), null, null);
        return r == null ? null : r.json;
    }

    public Snapshot getWithEtag(String path) {
        if (!ensureAuth()) return new Snapshot(null, null);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-Firebase-ETag", "true");
        Response r = request("GET", url(path), null, null, headers);
        if (r == null) return new Snapshot(null, null);
        return new Snapshot(r.json, r.etag);
    }

    public JsonElement put(String path, JsonElement value) {
        if (!ensureAuth()) return null;
        Response r = request("PUT", url(path), text(value), "application/json");
        return r == null ? null : r.json;
    }

    public JsonElement putIfUnchanged(String path, JsonElement value, String etag) {
        if (!ensureAuth()) return null;
        Map<String, String> headers = new LinkedHashMap<>();
        if (etag != null && !etag.isEmpty()) headers.put("if-match", etag);
        Response r = request("PUT", url(path), text(value), "application/json", headers);
        return r == null ? null : r.json;
    }

    public JsonElement patch(String path, JsonElement value) {
        if (!ensureAuth()) return null;
        Response r = request("PATCH", url(path), text(value), "application/json");
        return r == null ? null : r.json;
    }

    public boolean delete(String path) {
        if (!ensureAuth()) return false;
        Response r = request("DELETE", url(path), null, null);
        return r != null && lastStatus == 200;
    }

    /**
     * 每 pollMs 拉一次 path，内容变了就回调 onChange；返回一个取消句柄。
     */
    public Function0<Unit> listen(String path, Function0<Unit> onChange) {
        final java.util.concurrent.atomic.AtomicBoolean stopped =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        final java.util.concurrent.atomic.AtomicReference<String> last =
                new java.util.concurrent.atomic.AtomicReference<>("\u0000");
        final String p = path;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                while (!stopped.get()) {
                    if (ensureAuth()) {
                        JsonElement snap = get(p);
                        String now = snap == null ? "null" : snap.toString();
                        if (!now.equals(last.get())) {
                            last.set(now);
                            if (!stopped.get() && onChange != null) {
                                try {
                                    onChange.invoke();
                                } catch (Throwable e) {
                                    DangoLogger.warn("Firebase listener failed: {}", e.toString());
                                }
                            }
                        }
                    }
                    try {
                        Thread.sleep(num("pollMs", 3000));
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        }, "Dango-PartyListen");
        t.setDaemon(true);
        t.start();
        return new Function0<Unit>() {
            @Override
            public Unit invoke() {
                stopped.set(true);
                return Unit.INSTANCE;
            }
        };
    }

    // ---------------------------------------------------------------- HTTP

    public static final class Snapshot {
        private final JsonElement value;
        private final String etag;

        public Snapshot(JsonElement value, String etag) {
            this.value = value;
            this.etag = etag;
        }

        public JsonElement getValue() {
            return value;
        }

        public String getEtag() {
            return etag;
        }
    }

    static final class Response {
        private final JsonElement json;
        private final String etag;

        Response(JsonElement json, String etag) {
            this.json = json;
            this.etag = etag;
        }

        public JsonElement getJson() {
            return json;
        }

        public String getEtag() {
            return etag;
        }
    }

    private Response request(String method, String url, String body, String contentType) {
        return request(method, url, body, contentType, null);
    }

    private Response request(String method, String url, String body, String contentType,
                             Map<String, String> extra) {
        HttpURLConnection conn = null;
        try {
            conn = open(url);
            try {
                conn.setRequestMethod(method);
            } catch (java.net.ProtocolException e) {
                // HttpURLConnection 只认 GET/POST/HEAD/OPTIONS/PUT/DELETE/TRACE，PATCH 会抛异常。
                // Firebase REST 支持 POST + X-HTTP-Method-Override 等价于 PATCH。
                conn.setRequestMethod("POST");
                conn.setRequestProperty("X-HTTP-Method-Override", method);
            }
            if (contentType != null) conn.setRequestProperty("Content-Type", contentType);
            if (extra != null) {
                for (Map.Entry<String, String> e : extra.entrySet()) {
                    conn.setRequestProperty(e.getKey(), e.getValue());
                }
            }
            if (body != null) {
                conn.setDoOutput(true);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }
            lastStatus = conn.getResponseCode();
            String etag = conn.getHeaderField("ETag");
            InputStream in = lastStatus >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String text = "";
            if (in != null) {
                try (InputStream is = in) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = is.read(buf)) > 0) out.write(buf, 0, n);
                    text = new String(out.toByteArray(), StandardCharsets.UTF_8);
                }
            }
            JsonElement json = null;
            if (!text.trim().isEmpty()) {
                try {
                    json = JsonParser.parseString(text);
                } catch (Exception ignored) {
                }
            }
            if (lastStatus >= 400) {
                DangoLogger.warn("Firebase {} {} -> {} {}", method, path(url), lastStatus, head(text, 160));
                if (lastStatus == 401) {
                    // token 失效，丢掉重新匿名登录
                    idToken = "";
                    tokenExpiresAt = 0L;
                }
            }
            return new Response(json, etag);
        } catch (Exception e) {
            lastStatus = -1;
            DangoLogger.warn("Firebase {} {} failed: {}", method, path(url), e.toString());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ---------------------------------------------------------------- 小工具

    private static String path(String url) {
        int i = url.indexOf(".json");
        return i > 0 ? url.substring(0, i) : url;
    }

    private static String text(JsonElement e) {
        return e == null ? "null" : e.toString();
    }

    private static String str(JsonObject o, String key) {
        if (o == null || !o.has(key)) return "";
        try {
            JsonElement e = o.get(key);
            return e.isJsonPrimitive() ? e.getAsString() : e.toString();
        } catch (Exception ex) {
            return "";
        }
    }

    private static long parseLong(String s, long def) {
        try {
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static String enc(String s) {
        try {
            return java.net.URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return s;
        }
    }

    private static String head(String s, int n) {
        if (s == null) return "null";
        s = s.replace("\r", " ").replace("\n", " ");
        return s.length() <= n ? s : s.substring(0, n);
    }

    /** 给界面用的一句话说明（原版没有，方便排查代理问题） */
    public String getStatusHint() {
        if (!getConfigured()) return "firebase.properties 缺 apiKey / databaseUrl";
        Proxy p = proxy();
        if (p == Proxy.NO_PROXY) return "直连（如需代理请在 firebase.properties 里写 proxy=127.0.0.1:7890）";
        InetSocketAddress a = (InetSocketAddress) p.address();
        return "经代理 " + a.getHostString() + ":" + a.getPort() + "（HTTP）";
    }
}
