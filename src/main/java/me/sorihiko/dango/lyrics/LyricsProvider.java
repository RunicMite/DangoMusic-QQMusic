package me.sorihiko.dango.lyrics;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.sorihiko.dango.DangoLogger;
import me.sorihiko.dango.music.NeteaseApi;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 按 songid 取 QQ 音乐歌词（LRC）。
 *
 * 取词接口：c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid=...&nobase64=1
 * （必须带 Referer，否则 retcode=-1310）
 *
 * QQ 的逐字歌词（QRC）是加密的，这里不用；LRC 已经够驱动 3D 歌词和拆行显示。
 * 类名和方法名沿用原来的 LyricsProvider，界面层不用动。
 */
public final class LyricsProvider {

    public static final LyricsProvider INSTANCE = new LyricsProvider();

    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36 DangoMusic";

    private static final Pattern YRC_LINE = Pattern.compile("^\\[(\\d+),(\\d+)](.*)$");
    private static final Pattern YRC_WORD = Pattern.compile("\\((\\d+),(\\d+),\\d+\\)([^(]*)");
    private static final Pattern TIME_TAG = Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]");
    private static final Pattern CREDIT = Pattern.compile(
            "^\\s*(作词|作曲|编曲|制作人|制作|混音|母带|和声|吉他|贝斯|鼓|弦乐|录音|监制|出品|发行|统筹|词|曲|OP|SP|"
                    + "Lyricist|Lyrics by|Composer|Composed by|Producer|Produced by|Arranger|Arranged by)\\s*[:：]",
            Pattern.CASE_INSENSITIVE);

    private LyricsProvider() {
    }

    /** 按 songid 取歌词；拿不到返回 null */
    public Lyrics neteaseLyricById(long id) {
        try {
            String mid = NeteaseApi.INSTANCE.midOf(id);
            if (mid == null || mid.isEmpty()) return null;
            String url = "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid=" + mid
                    + "&format=json&nobase64=1&g_tk=5381";
            String body = httpGet(url, "https://y.qq.com/");
            if (body == null) return null;
            String text = lyricText(body);
            if (text == null || text.trim().isEmpty()) return null;
            List<LyricLine> lines = parseLrc(text);
            return lines.isEmpty() ? null : new Lyrics(lines, "QQ Music");
        } catch (Exception e) {
            DangoLogger.warn("QQ Music lyric request failed: {}", e.toString());
            return null;
        }
    }

    private String lyricText(String body) {
        String trimmed = body.trim();
        // 正常是 JSON；万一给的是纯 LRC 也认
        if (!trimmed.startsWith("{")) return unescape(trimmed);
        try {
            JsonElement root = JsonParser.parseString(trimmed);
            if (!root.isJsonObject()) return null;
            JsonObject o = root.getAsJsonObject();
            long retcode = o.has("retcode") ? o.get("retcode").getAsLong() : 0L;
            if (retcode != 0L) {
                DangoLogger.warn("QQ Music lyric retcode {}", retcode);
                return null;
            }
            JsonElement lyric = o.get("lyric");
            if (lyric == null || lyric.isJsonNull()) return null;
            return unescape(lyric.getAsString());
        } catch (Exception e) {
            return null;
        }
    }

    /** QQ 歌词里会带 HTML 实体（&#10; 之类），换成真字符 */
    private static String unescape(String s) {
        if (s == null) return null;
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '&') {
                int end = s.indexOf(';', i);
                if (end > i && end - i <= 8) {
                    String ent = s.substring(i + 1, end);
                    String rep = null;
                    if (ent.equals("amp")) rep = "&";
                    else if (ent.equals("lt")) rep = "<";
                    else if (ent.equals("gt")) rep = ">";
                    else if (ent.equals("quot")) rep = "\"";
                    else if (ent.equals("apos")) rep = "'";
                    else if (ent.startsWith("#")) {
                        try {
                            int cp = ent.startsWith("#x") || ent.startsWith("#X")
                                    ? Integer.parseInt(ent.substring(2), 16)
                                    : Integer.parseInt(ent.substring(1));
                            rep = new String(Character.toChars(cp));
                        } catch (Exception ignored) {
                        }
                    }
                    if (rep != null) {
                        out.append(rep);
                        i = end + 1;
                        continue;
                    }
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    // ---------------------------------------------------------------- 逐字（yrc 格式）

    public List<LyricLine> parseYrc(String text) {
        List<LyricLine> out = new ArrayList<>();
        if (text == null) return out;
        for (String raw : text.split("\n", -1)) {
            Matcher m = YRC_LINE.matcher(raw.trim());
            if (!m.find()) continue;
            long start = Long.parseLong(m.group(1));
            String body = m.group(3);
            List<LyricWord> words = new ArrayList<>();
            Matcher w = YRC_WORD.matcher(body);
            while (w.find()) {
                String t = w.group(3);
                if (t.isEmpty()) continue;
                words.add(new LyricWord(Long.parseLong(w.group(1)), Long.parseLong(w.group(2)), t));
            }
            if (words.isEmpty()) continue;
            StringBuilder line = new StringBuilder();
            for (LyricWord lw : words) line.append(lw.getText());
            String lineText = line.toString().trim();
            if (lineText.isEmpty() || CREDIT.matcher(lineText).find()) continue;
            out.add(new LyricLine(start, lineText, words));
        }
        out.sort(Comparator.comparingLong(LyricLine::getTimeMs));
        return out;
    }

    // ---------------------------------------------------------------- LRC

    public List<LyricLine> parseLrc(String text) {
        List<LyricLine> out = new ArrayList<>();
        if (text == null) return out;
        for (String raw : text.split("\n", -1)) {
            List<Long> tags = new ArrayList<>();
            String rest = raw.trim();
            while (true) {
                Matcher m = TIME_TAG.matcher(rest);
                if (!m.find() || m.start() != 0) break;
                long min = Long.parseLong(m.group(1));
                long sec = Long.parseLong(m.group(2));
                String fracStr = m.group(3) == null ? "" : m.group(3);
                long frac;
                if (fracStr.isEmpty()) frac = 0L;
                else if (fracStr.length() == 1) frac = Long.parseLong(fracStr) * 100L;
                else if (fracStr.length() == 2) frac = Long.parseLong(fracStr) * 10L;
                else frac = Long.parseLong(fracStr.substring(0, 3));
                tags.add(min * 60_000L + sec * 1000L + frac);
                rest = rest.substring(m.end());
            }
            if (tags.isEmpty()) continue;
            String lyric = rest.trim();
            if (CREDIT.matcher(lyric).find()) continue;
            for (long t : tags) out.add(new LyricLine(t, lyric, null));
        }
        out.sort(Comparator.comparingLong(LyricLine::getTimeMs));
        return out;
    }

    // ---------------------------------------------------------------- HTTP

    private String httpGet(String url, String referer) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(9000);
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Accept", "application/json");
            if (referer != null) conn.setRequestProperty("Referer", referer);
            String cookie = NeteaseApi.INSTANCE.cookie();
            if (!cookie.isEmpty()) conn.setRequestProperty("Cookie", cookie);
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) return null;
            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[16 * 1024];
                while (true) {
                    int n = in.read(buf);
                    if (n < 0) break;
                    out.write(buf, 0, n);
                    if (out.size() > 4 * 1024 * 1024) break;
                }
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            DangoLogger.warn("QQ Music lyric http failed: {}", e.toString());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // 保留给别处可能的调用
    @SuppressWarnings("unused")
    private static List<LyricLine> empty() {
        return Collections.emptyList();
    }
}
