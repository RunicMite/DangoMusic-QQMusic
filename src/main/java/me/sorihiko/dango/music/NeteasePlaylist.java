package me.sorihiko.dango.music;

/**
 * QQ 音乐里的一个歌单 / 排行榜。
 * id 为正数是歌单（disstid），为负数是排行榜（-topId）。
 */
public final class NeteasePlaylist {

    private final long id;
    private final String name;
    private final int trackCount;
    private final String coverUrl;
    /** 自己的歌单（含"我喜欢"）还是别人/官方的 */
    private final boolean owned;

    public NeteasePlaylist(long id, String name, int trackCount, String coverUrl, boolean owned) {
        this.id = id;
        this.name = name == null ? "" : name;
        this.trackCount = trackCount;
        this.coverUrl = coverUrl == null ? "" : coverUrl;
        this.owned = owned;
    }

    public long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public int getTrackCount() {
        return trackCount;
    }

    public String getCoverUrl() {
        return coverUrl;
    }

    public boolean getOwned() {
        return owned;
    }

    @Override
    public String toString() {
        return name + " (" + id + ")";
    }
}
