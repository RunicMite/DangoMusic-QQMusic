package me.sorihiko.dango.music;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.sorihiko.dango.DangoLogger;
import me.sorihiko.dango.DangoMusic;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * QQ 音乐接口（内置播放器用）。
 *
 * 这是 DangoMusic 的 QQ 音乐版：类名和公开成员沿用原来的 Netease* 名字，
 * 只是为了不改动界面层的调用点，内部全部走 QQ 音乐（y.qq.com / musicu.fcg）的接口。
 *
 * 登录：把 y.qq.com 网页版登录后的整段 Cookie（至少包含 uin=... 和各 psrf_*、qm_keystream/qqmusic_key）
 * 放进 config/dangomusic/qqmusic_cookie.txt。只保存在本地，只在请求 *.qq.com 时带上。
 */
public final class NeteaseApi {

    public static final NeteaseApi INSTANCE = new NeteaseApi();

    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";
    private static final String REFERER = "https://y.qq.com/";
    private static final String API = "https://u.y.qq.com/cgi-bin/musicu.fcg";
    private static final String FCG = "https://c.y.qq.com";
    private static final String COVER_PREFIX = "https://y.gtimg.cn/music/photo_new/T002R300x300M000";
    /** 拿不到播放地址时返回的地址：请求它会失败，播放器就会显示"没有播放权限" */
    private static final String DENIED_URL = "https://y.qq.com/n/ryqq/songDetail/denied.mp3";
    private static final int MAX_BYTES = 64 * 1024 * 1024;
    /** vkey 有效期约 2 小时，缓存 90 分钟 */
    private static final long VKEY_TTL_MS = 90L * 60L * 1000L;
    /** 设备 ID，随便一个稳定的 10 位数字 */
    private static final String GUID = "10" + (System.currentTimeMillis() % 100000000L);

    private NeteaseApi() {
    }

    // ---------------------------------------------------------------- 账号状态

    private volatile String nickname = "";
    private volatile long userId = 0L;
    private volatile String avatarUrl = "";

    private volatile String lastSearchError = "";

    private volatile String cookieCache = "";
    private volatile long cookieStamp = -1L;

    private final Map<Long, String> midCache = new ConcurrentHashMap<>();
    private final Map<Long, String> mediaMidCache = new ConcurrentHashMap<>();
    private final Map<String, String[]> urlCache = new ConcurrentHashMap<>(); // mid -> {url, expireAt}

    public String getNickname() {
        return nickname;
    }

    public long getUserId() {
        return userId;
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }

    public String getLastSearchError() {
        return lastSearchError;
    }

    // ---------------------------------------------------------------- Cookie

    private File cookieFile() {
        try {
            return DangoMusic.Companion.getInstance().getClientDir().resolve("qqmusic_cookie.txt").toFile();
        } catch (Throwable t) {
            return new File("config/dangomusic/qqmusic_cookie.txt");
        }
    }

    /**
     * 把粘进来的东西整理成一段 Cookie 值。支持：
     *  - 纯 Cookie 值：uin=...; qm_keystream=...;
     *  - 带 "Cookie: " 前缀的一行
     *  - 浏览器里"Copy as cURL"整段命令（自动从 -H 'cookie: ...' 里抠出来）
     *  - 只粘了一个 key 的值（当成 qqmusic_key）
     */
    private static String extractCookie(String raw) {
        if (raw == null) return "";
        String text = raw.replace("\uFEFF", "");
        int idx = text.toLowerCase(Locale.ROOT).indexOf("cookie:");
        if (idx >= 0) text = text.substring(idx + 7);
        text = text.trim();
        if (!text.isEmpty()) {
            char q = text.charAt(0);
            if (q == '\'' || q == '"') {
                int end = text.indexOf(q, 1);
                text = (end > 0 ? text.substring(1, end) : text.substring(1));
            }
        }
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\n")) {
            String s = line.trim();
            if (s.isEmpty()) continue;
            if (s.startsWith("-") || s.startsWith("'") || s.startsWith("\"")) break;
            if (!s.contains("=")) break;
            while (s.endsWith("\\") || s.endsWith(";")) s = s.substring(0, s.length() - 1).trim();
            if (s.isEmpty()) continue;
            if (sb.length() > 0) sb.append("; ");
            sb.append(s);
        }
        String out = sb.length() > 0 ? sb.toString() : text.trim();
        out = out.replaceAll("\\s{2,}", " ").trim();
        for (String stop : new String[]{"'", "\"", "`"}) {
            int i = out.indexOf(stop);
            if (i > 0) out = out.substring(0, i).trim();
        }
        while (out.endsWith(";")) out = out.substring(0, out.length() - 1).trim();
        if (!out.isEmpty() && !out.contains("=")) out = "qqmusic_key=" + out;
        return out;
    }

    /** 读 cookie 文件内容（带 mtime 缓存） */
    public String cookie() {
        File f = cookieFile();
        long stamp = f.isFile() ? f.lastModified() : -1L;
        if (stamp == cookieStamp) return cookieCache;
        cookieStamp = stamp;
        String text = "";
        try {
            if (stamp >= 0) {
                text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
                text = extractCookie(text);
            }
        } catch (Exception e) {
            DangoLogger.warn("Read QQ Music cookie failed: {}", e.toString());
            text = "";
        }
        cookieCache = text;
        return text;
    }

    public boolean getLoggedIn() {
        String c = cookie();
        if (c.length() <= 8) return false;
        return c.contains("qqmusic_key=") || c.contains("qm_keystream=") || c.contains("psrf_musickey")
                || c.contains("skey=") || c.contains("p_skey=");
    }

    /** cookie 里的 uin（web 端一般是 o 开头的 openid 形式） */
    private String uinRaw() {
        String c = cookie();
        for (String key : new String[]{"uin", "wxuin", "p_uin", "euin"}) {
            String v = cookieValue(c, key);
            if (!v.isEmpty()) return v;
        }
        return "";
    }

    /** 数字 uin（musicu 的 comm.uin 用这个） */
    private String uinNumeric() {
        String raw = uinRaw();
        if (raw.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (ch >= '0' && ch <= '9') sb.append(ch);
        }
        String digits = sb.toString().replaceFirst("^0+", "");
        return digits;
    }

    /** 音乐 key（musicu 的 comm.authst 用它换会员播放地址） */
    private String musicKey() {
        String c = cookie();
        for (String key : new String[]{"qm_keystream", "qqmusic_key", "qqmusic_key_", "psrf_musickey", "p_skey", "skey"}) {
            String v = cookieValue(c, key);
            if (!v.isEmpty()) return v;
        }
        return "";
    }

    /** 往 comm 里填公共字段 + 登录态 */
    private void addAuth(JsonObject comm) {
        String uin = uinNumeric();
        comm.addProperty("uin", uin.isEmpty() ? "0" : uin);
        comm.addProperty("format", "json");
        comm.addProperty("ct", 24);
        comm.addProperty("cv", 0);
        String key = musicKey();
        if (!key.isEmpty()) {
            comm.addProperty("authst", key);
            String type = cookieValue(cookie(), "tmeLoginType");
            comm.addProperty("tmeLoginType", type.isEmpty() ? "1" : type);
        }
    }

    private static String cookieValue(String cookie, String key) {
        if (cookie == null || cookie.isEmpty()) return "";
        for (String part : cookie.split(";")) {
            String p = part.trim();
            int eq = p.indexOf('=');
            if (eq <= 0) continue;
            if (p.substring(0, eq).trim().equalsIgnoreCase(key)) return p.substring(eq + 1).trim();
        }
        return "";
    }

    private String cookieHeader() {
        String c = cookie();
        if (c.isEmpty()) return "";
        return c;
    }

    /** 扫码/粘贴后保存 Cookie */
    public void saveCookie(String cookie) {
        try {
            File f = cookieFile();
            File dir = f.getParentFile();
            if (dir != null) dir.mkdirs();
            Files.write(f.toPath(), (cookie == null ? "" : cookie).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            DangoLogger.warn("Save QQ Music cookie failed: {}", e.toString());
        }
        resetUser();
    }

    public void logout() {
        saveCookie("");
        resetUser();
    }

    public void resetUser() {
        userId = 0L;
        nickname = "";
        avatarUrl = "";
        urlCache.clear();
    }

    /** 第一次运行时创建 cookie 文件，方便用户找到位置 */
    public void ensureCookieFile() {
        File f = cookieFile();
        if (f.exists()) return;
        try {
            File dir = f.getParentFile();
            if (dir != null) dir.mkdirs();
            Files.write(f.toPath(), new byte[0]);
        } catch (Exception ignored) {
        }
    }

    // ---------------------------------------------------------------- HTTP

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private static String head(String s, int n) {
        if (s == null) return "null";
        s = s.replace('\n', ' ');
        return s.length() <= n ? s : s.substring(0, n) + "...";
    }

    private static boolean isQqHost(String host) {
        return host != null && (host.endsWith("qq.com") || host.endsWith("qqmusic.qq.com") || host.endsWith("gtimg.cn") || host.endsWith("qpic.cn"));
    }

    public byte[] request(String url, String accept, int maxBytes) {
        if (url == null || url.trim().isEmpty()) return null;
        String current = url;
        for (int attempt = 0; attempt < 6; attempt++) {
            HttpURLConnection conn = null;
            try {
                URL u = new URL(current);
                conn = (HttpURLConnection) u.openConnection();
                conn.setInstanceFollowRedirects(false);
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(20000);
                conn.setRequestProperty("User-Agent", UA);
                conn.setRequestProperty("Accept", accept == null ? "*/*" : accept);
                if (isQqHost(u.getHost())) {
                    conn.setRequestProperty("Referer", REFERER);
                    String c = cookieHeader();
                    if (!c.isEmpty()) conn.setRequestProperty("Cookie", c);
                }
                int code = conn.getResponseCode();
                if (code >= 300 && code < 400) {
                    String loc = conn.getHeaderField("Location");
                    if (loc == null) return null;
                    current = new URL(u, loc).toString();
                    continue;
                }
                if (code < 200 || code >= 300) return null;
                try (InputStream input = conn.getInputStream()) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buf = new byte[64 * 1024];
                    while (true) {
                        int n = input.read(buf);
                        if (n < 0) break;
                        out.write(buf, 0, n);
                        if (out.size() > maxBytes) return null;
                    }
                    return out.toByteArray();
                }
            } catch (Exception e) {
                DangoLogger.warn("QQ Music request failed ({}): {}", current, e.toString());
                return null;
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        return null;
    }

    private String getText(String url) {
        byte[] b = request(url, "application/json, text/plain, */*", 8 * 1024 * 1024);
        return b == null ? null : new String(b, StandardCharsets.UTF_8);
    }

    private JsonObject getJson(String url) {
        String text = getText(url);
        if (text == null || text.isEmpty()) return null;
        try {
            JsonElement e = JsonParser.parseString(text);
            return e.isJsonObject() ? e.getAsJsonObject() : null;
        } catch (Exception ex) {
            DangoLogger.warn("QQ Music bad json from {}: {}", url, head(text, 160));
            return null;
        }
    }

    private JsonObject postJson(String url, String body) {
        HttpURLConnection conn = null;
        try {
            URL u = new URL(url);
            conn = (HttpURLConnection) u.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(20000);
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json, text/plain, */*");
            conn.setRequestProperty("Referer", REFERER);
            String c = cookieHeader();
            if (!c.isEmpty()) conn.setRequestProperty("Cookie", c);
            conn.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                DangoLogger.warn("QQ Music POST {} -> HTTP {}", url, code);
                return null;
            }
            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[32 * 1024];
                while (true) {
                    int n = in.read(buf);
                    if (n < 0) break;
                    out.write(buf, 0, n);
                    if (out.size() > 16 * 1024 * 1024) break;
                }
                String text = new String(out.toByteArray(), StandardCharsets.UTF_8);
                JsonElement e = JsonParser.parseString(text);
                return e.isJsonObject() ? e.getAsJsonObject() : null;
            }
        } catch (Exception e) {
            DangoLogger.warn("QQ Music POST failed: {}", e.toString());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** musicu.fcg 通用调用：{"comm":..., "req":{module,method,param}}，返回 req.data */
    private JsonObject musicu(String module, String method, JsonObject param) {
        JsonObject req = new JsonObject();
        req.addProperty("module", module);
        req.addProperty("method", method);
        req.add("param", param == null ? new JsonObject() : param);

        JsonObject comm = new JsonObject();
        addAuth(comm);

        JsonObject root = new JsonObject();
        root.add("comm", comm);
        root.add("req", req);

        JsonObject resp = postJson(API, root.toString());
        if (resp == null) return null;
        JsonObject r = path(resp, "req");
        if (r == null) {
            DangoLogger.warn("QQ Music {} / {} no req in response: {}", module, method, head(resp.toString(), 200));
            return null;
        }
        long code = l(r, "code");
        if (code != 0) {
            DangoLogger.warn("QQ Music {} / {} -> code {} (subcode {})", module, method, code,
                    r.has("subcode") ? r.get("subcode").toString() : "-");
        }
        return path(r, "data");
    }

    // ---------------------------------------------------------------- JSON 小工具

    private static JsonObject asObj(JsonElement e) {
        return (e != null && e.isJsonObject()) ? e.getAsJsonObject() : null;
    }

    private static JsonArray asArr(JsonElement e) {
        return (e != null && e.isJsonArray()) ? e.getAsJsonArray() : null;
    }

    private static String s(JsonObject o, String key) {
        if (o == null) return "";
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return "";
        try {
            return e.getAsString();
        } catch (Exception ex) {
            return "";
        }
    }

    private static long l(JsonObject o, String key) {
        if (o == null) return 0L;
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return 0L;
        try {
            return e.getAsLong();
        } catch (Exception ex) {
            try {
                String v = e.getAsString().trim();
                return v.isEmpty() ? 0L : Long.parseLong(v);
            } catch (Exception e2) {
                return 0L;
            }
        }
    }

    private static String first(JsonObject o, String... keys) {
        for (String k : keys) {
            String v = s(o, k);
            if (!v.isEmpty()) return v;
        }
        return "";
    }

    private static long firstLong(JsonObject o, String... keys) {
        for (String k : keys) {
            long v = l(o, k);
            if (v != 0L) return v;
        }
        return 0L;
    }

    private static JsonObject path(JsonObject o, String... keys) {
        JsonObject cur = o;
        for (String k : keys) {
            if (cur == null) return null;
            cur = asObj(cur.get(k));
        }
        return cur;
    }

    private static JsonArray arrPath(JsonObject o, String... keys) {
        if (o == null) return null;
        JsonObject cur = o;
        for (int i = 0; i < keys.length - 1; i++) {
            cur = asObj(cur.get(keys[i]));
            if (cur == null) return null;
        }
        return asArr(cur.get(keys[keys.length - 1]));
    }

    private static String coverOf(String albumMid) {
        return albumMid == null || albumMid.isEmpty() ? "" : COVER_PREFIX + albumMid + ".jpg";
    }

    // ---------------------------------------------------------------- 解析歌曲

    private NeteaseTrack trackOf(JsonObject raw) {
        JsonObject o = raw;
        if (o == null) return null;
        // 榜单接口会把歌曲包在 data 里
        JsonObject inner = asObj(o.get("data"));
        if (inner != null && (inner.has("songmid") || inner.has("mid") || inner.has("songname"))) o = inner;

        long id = firstLong(o, "id", "songid", "songId", "song_id");
        String mid = first(o, "mid", "songmid", "songMid", "song_mid");
        if (id == 0L && mid.isEmpty()) return null;

        String name = first(o, "name", "songname", "songName", "title");
        List<String> artists = new ArrayList<>();
        JsonArray singers = asArr(o.get("singer"));
        if (singers == null) singers = asArr(o.get("singers"));
        if (singers != null) {
            for (JsonElement el : singers) {
                JsonObject so = asObj(el);
                if (so == null) continue;
                String n = first(so, "name", "title");
                if (!n.isEmpty()) artists.add(n);
            }
        }
        if (artists.isEmpty()) {
            String one = first(o, "singerName", "singername");
            if (!one.isEmpty()) {
                for (String p : one.split("[/&]")) {
                    String t = p.trim();
                    if (!t.isEmpty()) artists.add(t);
                }
            }
        }

        long dur = l(o, "interval") * 1000L;
        if (dur == 0L) {
            long d = l(o, "duration");
            if (d > 0L) dur = d < 100000L ? d * 1000L : d;
        }
        if (dur == 0L) dur = l(o, "durationMs");

        JsonObject album = asObj(o.get("album"));
        String albumMid = album == null ? "" : first(album, "mid", "pmid");
        if (albumMid.isEmpty()) albumMid = first(o, "albummid", "albumMid");
        String cover = coverOf(albumMid);
        if (cover.isEmpty()) cover = first(o, "cover", "picUrl", "imgurl");

        String mediaMid = first(o, "strMediaMid", "media_mid");
        JsonObject file = asObj(o.get("file"));
        if (file != null) {
            String mm = first(file, "media_mid", "mediaMid");
            if (!mm.isEmpty()) mediaMid = mm;
        }

        if (id != 0L && !mid.isEmpty()) midCache.put(id, mid);
        if (id != 0L && !mediaMid.isEmpty()) mediaMidCache.put(id, mediaMid);
        return new NeteaseTrack(id, name, artists, dur, cover, mid);
    }

    private List<NeteaseTrack> tracksOf(JsonArray arr) {
        List<NeteaseTrack> out = new ArrayList<>();
        if (arr == null) return out;
        for (JsonElement el : arr) {
            NeteaseTrack t = trackOf(asObj(el));
            if (t != null) out.add(t);
        }
        return out;
    }

    // ---------------------------------------------------------------- 账号

    public long resolveUser() {
        if (userId != 0L) return userId;
        if (!getLoggedIn()) return 0L;

        try {
            JsonObject data = musicu("music.UserInfo.userInfoServer", "GetLoginUserInfo", new JsonObject());
            if (data == null) {
                DangoLogger.warn("QQ Music user info unavailable（cookie 可能已过期，或 QQ 号与 key 不配对）");
            }
            if (data != null) {
                // 实际返回结构：req.data.info = { nick, logo, ... }
                JsonObject info = asObj(data.get("info"));
                if (info == null) info = asObj(data.get("creator"));
                if (info == null) info = asObj(data.get("baseinfo"));
                if (info == null) info = asObj(data.get("userinfo"));
                if (info == null) info = data;
                long uin = firstLong(info, "uin", "uid", "encrypt_uin_num");
                String nick = first(info, "nick", "nickname", "name");
                String head = first(info, "logo", "headpic", "headurl", "avatar", "avatarUrl");
                if (uin != 0L) userId = uin;
                if (!nick.isEmpty()) nickname = nick;
                if (!head.isEmpty()) avatarUrl = head;
            }
        } catch (Exception e) {
            DangoLogger.warn("QQ Music account lookup failed: {}", e.toString());
        }

        if (userId == 0L) {
            // 退一步：cookie 里的 uin 就是 QQ 号
            String n = uinNumeric();
            if (!n.isEmpty()) {
                try {
                    userId = Long.parseLong(n);
                } catch (Exception ignored) {
                }
            }
        }
        if (nickname.isEmpty() && userId != 0L) nickname = "QQ " + userId;
        DangoLogger.info("QQ Music account: {} (uin {})", nickname, userId);
        return userId;
    }

    /** 检查 cookie 是否真的能用（登录弹窗用） */
    public boolean validateLogin() {
        if (!getLoggedIn()) return false;
        resetUser();
        long uid = resolveUser();
        return uid != 0L;
    }

    // ---------------------------------------------------------------- 歌单

    /** 用户自己的歌单 */
    public List<NeteasePlaylist> userPlaylists() {
        List<NeteasePlaylist> out = new ArrayList<>();
        String uin = uinNumeric();
        if (uin.isEmpty()) return out;
        long me = resolveUser();
        Map<Long, NeteasePlaylist> seen = new LinkedHashMap<>();

        // 1) musicu: PlaylistBaseRead / GetPlaylistByUin
        try {
            JsonObject p = new JsonObject();
            p.addProperty("uin", uin);
            p.addProperty("sin", 0);
            p.addProperty("size", 200);
            JsonObject data = musicu("music.musicasset.PlaylistBaseRead", "GetPlaylistByUin", p);
            JsonArray list = null;
            if (data != null) {
                list = asArr(data.get("v_playlist"));
                if (list == null) list = asArr(data.get("playlist"));
                if (list == null) list = asArr(data.get("list"));
            }
            if (list != null) {
                for (JsonElement el : list) {
                    NeteasePlaylist pl = playlistOf(asObj(el), me, true);
                    if (pl != null && !seen.containsKey(pl.getId())) seen.put(pl.getId(), pl);
                }
            }
        } catch (Exception e) {
            DangoLogger.warn("QQ Music GetPlaylistByUin failed: {}", e.toString());
        }

        // 2) 老接口：个人主页（能拿到"我喜欢"和自己的歌单）
        if (seen.isEmpty()) {
            try {
                String url = FCG + "/splcloud/fcgi-bin/fcg_get_profile_homepage.fcg?cid=205360838&userid=" + enc(uin)
                        + "&reqfrom=1&g_tk=5381&loginUin=" + enc(uinRaw()) + "&hostUin=0&format=json&inCharset=utf8"
                        + "&outCharset=utf-8&notice=0&platform=yqq.json&needNewCode=0";
                JsonObject root = getJson(url);
                JsonArray mydiss = arrPath(root, "data", "mydiss", "list");
                if (mydiss != null) {
                    for (JsonElement el : mydiss) {
                        NeteasePlaylist pl = playlistOf(asObj(el), me, true);
                        if (pl != null && !seen.containsKey(pl.getId())) seen.put(pl.getId(), pl);
                    }
                }
                JsonObject data = path(root, "data");
                long likedId = firstLong(data, "disstid");
                if (likedId != 0L && !seen.containsKey(likedId)) {
                    seen.put(likedId, new NeteasePlaylist(likedId, first(data, "disstname", "dissname").isEmpty()
                            ? "我喜欢" : first(data, "disstname", "dissname"),
                            0, "", true));
                }
            } catch (Exception e) {
                DangoLogger.warn("QQ Music profile homepage failed: {}", e.toString());
            }
        }

        // 3) 还是空：user_created_diss
        if (seen.isEmpty()) {
            try {
                String url = FCG + "/rsc/fcgi-bin/fcg_user_created_diss?hostuin=" + enc(uin) + "&sin=0&size=200&g_tk=5381"
                        + "&loginUin=" + enc(uinRaw()) + "&hostUin=0&format=json&inCharset=utf8&outCharset=utf-8"
                        + "&notice=0&platform=yqq.json&needNewCode=0";
                JsonObject root = getJson(url);
                JsonArray list = arrPath(root, "data", "disslist");
                if (list != null) {
                    for (JsonElement el : list) {
                        NeteasePlaylist pl = playlistOf(asObj(el), me, true);
                        if (pl != null && !seen.containsKey(pl.getId())) seen.put(pl.getId(), pl);
                    }
                }
            } catch (Exception e) {
                DangoLogger.warn("QQ Music user_created_diss failed: {}", e.toString());
            }
        }

        out.addAll(seen.values());
        return out;
    }

    private NeteasePlaylist playlistOf(JsonObject o, long me, boolean ownedByDefault) {
        if (o == null) return null;
        long id = firstLong(o, "tid", "dissid", "dirId", "dirid", "disstid", "id");
        if (id == 0L) return null;
        String name = first(o, "dirName", "dissname", "dissName", "title", "name");
        long count = firstLong(o, "songNum", "songnum", "song_cnt", "total_song_num");
        String cover = first(o, "logo", "picUrl", "picurl", "imgurl", "cover", "headurl");
        boolean owned = ownedByDefault;
        JsonObject creator = asObj(o.get("creator"));
        long creatorUin = creator == null ? 0L : firstLong(creator, "uin", "qq");
        if (creatorUin == 0L) creatorUin = firstLong(o, "host_uin", "hostuin");
        if (me != 0L && creatorUin != 0L) owned = creatorUin == me;
        return new NeteasePlaylist(id, name.isEmpty() ? "歌单" : name, (int) count, cover, owned);
    }

    /**
     * 歌单里的歌。id > 0 是普通歌单（disstid），id < 0 是排行榜（-topId）。
     */
    public List<NeteasePlaylist> recommendPlaylists() {
        List<NeteasePlaylist> out = new ArrayList<>();
        try {
            String url = FCG + "/splcloud/fcgi-bin/fcg_get_diss_by_tag.fcg?picmid=1&rnd=0.1&g_tk=5381&loginUin=0"
                    + "&hostUin=0&format=json&inCharset=utf8&outCharset=utf-8&notice=0&platform=yqq.json&needNewCode=0"
                    + "&categoryId=10000000&sortId=5&sin=0&ein=29";
            JsonObject root = getJson(url);
            JsonArray list = arrPath(root, "data", "list");
            if (list != null) {
                for (JsonElement el : list) {
                    JsonObject o = asObj(el);
                    if (o == null) continue;
                    long id = firstLong(o, "dissid");
                    if (id == 0L) continue;
                    out.add(new NeteasePlaylist(id, first(o, "dissname", "dissName"), 0,
                            first(o, "imgurl", "logo", "picurl"), false));
                }
            }
        } catch (Exception e) {
            DangoLogger.warn("QQ Music recommend playlists failed: {}", e.toString());
        }
        if (out.isEmpty()) {
            // 兜底：用排行榜当推荐
            return toplists();
        }
        return out;
    }

    /** 排行榜（飙升榜、热歌榜……）：id 用负数表示，取歌时还原成 topId */
    public List<NeteasePlaylist> toplists() {
        List<NeteasePlaylist> out = new ArrayList<>();
        try {
            JsonObject data = musicu("music.musicToplist.Toplist", "GetAll", new JsonObject());
            JsonArray groups = data == null ? null : asArr(data.get("group"));
            if (groups != null) {
                for (JsonElement gel : groups) {
                    JsonObject g = asObj(gel);
                    if (g == null) continue;
                    JsonArray tops = asArr(g.get("toplist"));
                    if (tops == null) continue;
                    for (JsonElement tel : tops) {
                        JsonObject o = asObj(tel);
                        if (o == null) continue;
                        long topId = firstLong(o, "topId", "topid");
                        if (topId == 0L) continue;
                        String title = first(o, "title", "ListName");
                        long total = firstLong(o, "totalNum", "total_song_num");
                        String cover = "";
                        JsonArray songs = asArr(o.get("song"));
                        if (songs != null && songs.size() > 0) {
                            JsonObject firstSong = asObj(songs.get(0));
                            if (firstSong != null) {
                                JsonObject album = asObj(firstSong.get("album"));
                                String albumMid = album == null ? "" : first(album, "mid");
                                if (albumMid.isEmpty()) albumMid = first(firstSong, "albummid", "albumMid", "pmid");
                                cover = coverOf(albumMid);
                            }
                        }
                        out.add(new NeteasePlaylist(-topId, title.isEmpty() ? "榜单" : title, (int) total, cover, false));
                    }
                }
            }
        } catch (Exception e) {
            DangoLogger.warn("QQ Music toplists failed: {}", e.toString());
        }
        return out;
    }

    /** 歌单 / 排行榜里的全部歌曲 */
    public List<NeteaseTrack> playlistTracks(long playlistId) {
        if (playlistId < 0L) return toplistTracks(-playlistId);
        List<NeteaseTrack> out = new ArrayList<>();
        try {
            int begin = 0;
            int total = -1;
            for (int guard = 0; guard < 20; guard++) {
                JsonObject p = new JsonObject();
                p.addProperty("disstid", playlistId);
                p.addProperty("dirid", 0);
                p.addProperty("tag", 1);
                p.addProperty("song_begin", begin);
                p.addProperty("song_num", 50);
                p.addProperty("userinfo", 1);
                p.addProperty("order", 1);
                JsonObject data = musicu("music.srfDissInfo.DissInfo", "CgiGetDiss", p);
                if (data == null) break;
                JsonArray songs = asArr(data.get("songlist"));
                if (songs == null || songs.size() == 0) break;
                out.addAll(tracksOf(songs));
                begin += songs.size();
                if (total < 0) {
                    JsonObject dirinfo = asObj(data.get("dirinfo"));
                    total = (int) firstLong(dirinfo, "songnum", "songNum", "song_cnt");
                }
                if (songs.size() < 50) break;
                if (total > 0 && begin >= total) break;
            }
        } catch (Exception e) {
            DangoLogger.warn("QQ Music playlist tracks failed: {}", e.toString());
        }
        return out;
    }

    /** 排行榜曲目：v8 老接口直接给 songmid */
    private List<NeteaseTrack> toplistTracks(long topId) {
        List<NeteaseTrack> out = new ArrayList<>();
        try {
            int begin = 0;
            for (int guard = 0; guard < 10; guard++) {
                String url = FCG + "/v8/fcg-bin/fcg_v8_toplist_cp.fcg?tpl=3&page=detail&date=&topid=" + topId
                        + "&type=top&song_begin=" + begin + "&song_num=100&g_tk=5381&loginUin=0&hostUin=0&format=json"
                        + "&inCharset=utf8&outCharset=utf-8&notice=0&platform=yqq.json&needNewCode=0";
                JsonObject root = getJson(url);
                JsonArray songlist = root == null ? null : asArr(root.get("songlist"));
                if (songlist == null || songlist.size() == 0) break;
                int before = out.size();
                out.addAll(tracksOf(songlist));
                begin += songlist.size();
                if (songlist.size() < 100 || out.size() == before) break;
            }
        } catch (Exception e) {
            DangoLogger.warn("QQ Music toplist tracks failed: {}", e.toString());
        }
        return out;
    }

    // ---------------------------------------------------------------- 搜索

    public List<NeteaseTrack> search(String keyword, int limit) {
        List<NeteaseTrack> empty = new ArrayList<>();
        if (keyword == null || keyword.trim().isEmpty()) return empty;
        String q = keyword.trim();
        int n = limit <= 0 ? 50 : Math.min(limit, 50);
        List<String> errors = new ArrayList<>();

        // 1) client_search_cp（最稳，直接带封面、时长）
        try {
            String url = FCG + "/soso/fcgi-bin/client_search_cp?w=" + enc(q) + "&p=1&n=" + n + "&format=json&new_json=1";
            JsonObject root = getJson(url);
            List<NeteaseTrack> list = tracksOf(arrPath(root, "data", "song", "list"));
            if (!list.isEmpty()) {
                lastSearchError = "";
                return list;
            }
            errors.add("search " + (root == null ? "no response" : s(root, "code")));
        } catch (Exception e) {
            errors.add("search " + e.getClass().getSimpleName());
        }

        // 2) musicu 搜索
        try {
            JsonObject p = new JsonObject();
            p.addProperty("query", q);
            p.addProperty("num_per_page", n);
            p.addProperty("page_num", 1);
            p.addProperty("search_type", 0);
            JsonObject data = musicu("music.search.SearchCgiService", "DoSearchForQQMusicDesktop", p);
            List<NeteaseTrack> list = tracksOf(arrPath(data, "body", "song", "list"));
            if (!list.isEmpty()) {
                lastSearchError = "";
                return list;
            }
            errors.add("musicu");
        } catch (Exception e) {
            errors.add("musicu " + e.getClass().getSimpleName());
        }

        lastSearchError = errors.isEmpty() ? "" : String.join(", ", errors);
        if (!lastSearchError.isEmpty()) DangoLogger.warn("QQ Music search failed: {}", lastSearchError);
        return empty;
    }

    public static List search$default(NeteaseApi self, String keyword, int limit, int mask, Object marker) {
        if ((mask & 2) != 0) limit = 50;
        return self.search(keyword, limit);
    }

    // ---------------------------------------------------------------- 播放地址

    public static final class SongUrl {
        private final String url;
        private final boolean trialOnly;

        public SongUrl(String url, boolean trialOnly) {
            this.url = url;
            this.trialOnly = trialOnly;
        }

        public String getUrl() {
            return url;
        }

        public boolean getTrialOnly() {
            return trialOnly;
        }
    }

    /** songId -> songmid（缓存 + music.pf_song_detail_svr 兜底） */
    public String midOf(long songId) {
        String cached = midCache.get(songId);
        if (cached != null && !cached.isEmpty()) return cached;
        try {
            JsonObject p = new JsonObject();
            p.addProperty("song_id", songId);
            p.addProperty("song_type", 0);
            JsonObject data = musicu("music.pf_song_detail_svr", "get_song_detail", p);
            if (data == null) data = musicu("music.pf_song_detail_svr", "get_song_detail_yqq", p);
            JsonObject info = data == null ? null : asObj(data.get("track_info"));
            if (info != null) {
                String mid = first(info, "mid", "songmid");
                if (!mid.isEmpty()) {
                    midCache.put(songId, mid);
                    JsonObject file = asObj(info.get("file"));
                    String mediaMid = file == null ? "" : first(file, "media_mid");
                    if (!mediaMid.isEmpty()) mediaMidCache.put(songId, mediaMid);
                    return mid;
                }
            }
        } catch (Exception e) {
            DangoLogger.warn("QQ Music song detail failed: {}", e.toString());
        }
        return null;
    }

    private String cachedUrl(String mid) {
        String[] e = urlCache.get(mid);
        if (e == null) return null;
        try {
            if (Long.parseLong(e[1]) > System.currentTimeMillis()) return e[0];
        } catch (Exception ignored) {
        }
        urlCache.remove(mid);
        return null;
    }

    /** 请求 vkey；返回 {purl, sipBase} 或 null */
    private String[] vkey(String mid, String filename) {
        try {
            JsonObject p = new JsonObject();
            p.addProperty("guid", GUID);
            JsonArray mids = new JsonArray();
            mids.add(mid);
            p.add("songmid", mids);
            JsonArray types = new JsonArray();
            types.add(0);
            p.add("songtype", types);
            String uin = uinNumeric();
            p.addProperty("uin", uin.isEmpty() ? "0" : uin);
            p.addProperty("loginflag", 1);
            p.addProperty("platform", "20");
            JsonArray names = new JsonArray();
            names.add(filename);
            p.add("filename", names);

            JsonObject req = new JsonObject();
            req.addProperty("module", "vkey.GetVkeyServer");
            req.addProperty("method", "CgiGetVkey");
            req.add("param", p);

            JsonObject comm = new JsonObject();
            addAuth(comm);

            JsonObject root = new JsonObject();
            root.add("comm", comm);
            root.add("req", req);

            JsonObject resp = postJson(API, root.toString());
            JsonObject r = resp == null ? null : path(resp, "req");
            JsonObject data = r == null ? null : asObj(r.get("data"));
            if (data == null) return null;
            JsonArray info = asArr(data.get("midurlinfo"));
            if (info == null || info.size() == 0) return null;
            JsonObject firstInfo = asObj(info.get(0));
            String purl = first(firstInfo, "purl");
            long result = firstLong(firstInfo, "result");
            String sip = "";
            JsonArray sipArr = asArr(data.get("sip"));
            if (sipArr != null) {
                for (JsonElement el : sipArr) {
                    String v = el.isJsonPrimitive() ? el.getAsString() : "";
                    if (v.contains("ws.stream.qqmusic.qq.com")) {
                        sip = v;
                        break;
                    }
                    if (sip.isEmpty() && !v.isEmpty()) sip = v;
                }
            }
            if (purl.isEmpty()) {
                DangoLogger.info("QQ Music vkey for {} ({}) result={}", mid, filename, result);
                return null;
            }
            return new String[]{purl, sip};
        } catch (Exception e) {
            DangoLogger.warn("QQ Music vkey failed: {}", e.toString());
            return null;
        }
    }

    private static String streamUrl(String purl, String sipBase) {
        if (purl.startsWith("http://") || purl.startsWith("https://")) return purl;
        String base = (sipBase == null || sipBase.isEmpty()) ? "http://ws.stream.qqmusic.qq.com/" : sipBase;
        base = base.replace("http://", "https://");
        if (!base.endsWith("/")) base = base + "/";
        return base + purl;
    }

    /** 抓一小段字节，看是不是真的 MP3（会员曲有时给的是加密文件，JLayer 解不了，得换一个音质再试） */
    private boolean looksLikeMp3(String url) {
        HttpURLConnection conn = null;
        try {
            URL u = new URL(url);
            conn = (HttpURLConnection) u.openConnection();
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Referer", REFERER);
            conn.setRequestProperty("Range", "bytes=0-4095");
            String c = cookieHeader();
            if (!c.isEmpty()) conn.setRequestProperty("Cookie", c);
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) return false;
            try (InputStream in = conn.getInputStream()) {
                byte[] buf = new byte[4096];
                int n = 0;
                while (n < buf.length) {
                    int r = in.read(buf, n, buf.length - n);
                    if (r < 0) break;
                    n += r;
                }
                if (n < 100) return false;
                // ID3 标签
                if (buf[0] == 'I' && buf[1] == 'D' && buf[2] == '3') return true;
                // MP3 帧同步字
                if ((buf[0] & 0xFF) == 0xFF && (buf[1] & 0xE0) == 0xE0) return true;
                String snip = new String(buf, 0, Math.min(n, 200), StandardCharsets.ISO_8859_1).trim();
                DangoLogger.info("QQ Music stream rejected: {}", head(snip, 80));
                return false;
            }
        } catch (Exception e) {
            DangoLogger.warn("QQ Music stream probe failed: {}", e.toString());
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * 播放地址。优先 320k MP3（会员），退 128k MP3。
     * 内置解码器只吃 MP3，所以不用 m4a/flac。
     * 拿到地址后会先抓 4KB 确认真的是 MP3（会员曲目偶尔给加密文件），不行就换下一个音质。
     * 全部拿不到时返回一个会失败的地址，播放器就会显示"这首歌没有播放权限"。
     */
    public SongUrl songUrl(long id) {
        try {
            String cached = midCache.get(id);
            String mid = (cached != null && !cached.isEmpty()) ? cached : midOf(id);
            if (mid == null || mid.isEmpty()) return new SongUrl(DENIED_URL, false);

            String ready = cachedUrl(mid);
            if (ready != null) return new SongUrl(ready, false);

            String mediaMid = mediaMidCache.getOrDefault(id, "");
            List<String> candidates = new ArrayList<>();
            if (!mediaMid.isEmpty()) candidates.add(mediaMid);
            if (!candidates.contains(mid)) candidates.add(mid);

            for (String prefix : new String[]{"M800", "M500"}) {
                for (String mm : candidates) {
                    String[] vk = vkey(mid, prefix + mm + ".mp3");
                    if (vk == null) continue;
                    String url = streamUrl(vk[0], vk[1]);
                    if (!looksLikeMp3(url)) continue;
                    urlCache.put(mid, new String[]{url, String.valueOf(System.currentTimeMillis() + VKEY_TTL_MS)});
                    return new SongUrl(url, false);
                }
            }
        } catch (Exception e) {
            DangoLogger.warn("QQ Music song url failed: {}", e.toString());
        }
        return new SongUrl(DENIED_URL, false);
    }

    public static byte[] request$default(NeteaseApi self, String url, String accept, int maxBytes, int mask, Object marker) {
        if ((mask & 2) != 0) accept = "*/*";
        if ((mask & 4) != 0) maxBytes = MAX_BYTES;
        return self.request(url, accept, maxBytes);
    }
}
