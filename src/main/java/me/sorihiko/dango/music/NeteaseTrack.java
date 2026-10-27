package me.sorihiko.dango.music;

import java.util.Collections;
import java.util.List;

/**
 * QQ 音乐里的一首歌。
 *
 * 类名沿用原来的 NeteaseTrack（界面层到处在用），字段是 QQ 音乐的数据：
 *  - id          数字 songid（QQ 音乐也用它当唯一键，歌单同步、缓存都用它）
 *  - mid         songmid（拿播放地址要用，字符串）
 *  - durationMs  interval 秒 × 1000
 *  - coverUrl    专辑封面 T002R300x300M000&lt;albumMid&gt;.jpg
 */
public final class NeteaseTrack {

    private final long id;
    private final String name;
    private final List<String> artists;
    private final long durationMs;
    private final String coverUrl;
    private final String mid;

    public NeteaseTrack(long id, String name, List<String> artists, long durationMs, String coverUrl) {
        this(id, name, artists, durationMs, coverUrl, "");
    }

    public NeteaseTrack(long id, String name, List<String> artists, long durationMs, String coverUrl, String mid) {
        this.id = id;
        this.name = name == null ? "" : name;
        this.artists = artists == null ? Collections.<String>emptyList() : artists;
        this.durationMs = durationMs;
        this.coverUrl = coverUrl == null ? "" : coverUrl;
        this.mid = mid == null ? "" : mid;
    }

    public long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public List<String> getArtists() {
        return artists;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public String getCoverUrl() {
        return coverUrl;
    }

    /** songmid（QQ 音乐播放地址要用）；空字符串表示还没查到 */
    public String getMid() {
        return mid;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof NeteaseTrack)) return false;
        return ((NeteaseTrack) o).id == id;
    }

    @Override
    public int hashCode() {
        return (int) (id ^ (id >>> 32));
    }

    @Override
    public String toString() {
        return name + " - " + String.join("/", artists) + " (" + id + ")";
    }
}
