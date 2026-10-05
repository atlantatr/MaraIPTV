package com.atlanta.maraplayer.data;

import com.atlanta.maraplayer.data.Models.Account;
import com.atlanta.maraplayer.data.Models.Category;
import com.atlanta.maraplayer.data.Models.Epg;
import com.atlanta.maraplayer.data.Models.Item;
import com.atlanta.maraplayer.data.Models.SeriesInfo;
import com.atlanta.maraplayer.data.Models.VodInfo;

import java.io.IOException;
import java.util.List;

/** Icerik kaynagi: Xtream Codes API veya M3U calma listesi. */
public interface Source {

    Account login() throws IOException;

    List<Category> categories(int type) throws IOException;

    /** categoryId == null ise tum liste */
    List<Item> items(int type, String categoryId) throws IOException;

    VodInfo vodInfo(String vodId) throws IOException;

    SeriesInfo seriesInfo(String seriesId) throws IOException;

    /** Simdi/sonra EPG. epgId: XMLTV kanal kimligi (tvg-id / epg_channel_id), bos olabilir */
    List<Epg> shortEpg(String streamId, String epgId, int limit) throws IOException;

    /** Tum gunun EPG'si */
    List<Epg> fullEpg(String streamId, String epgId) throws IOException;

    String liveUrl(String streamId, String format);

    String movieUrl(String streamId, String ext);

    String episodeUrl(String episodeId, String ext);

    String catchupUrl(String streamId, String startRaw, int durationMin, String format);

    /** Profilde XMLTV adresi yoksa kullanilacak varsayilan (Xtream: xmltv.php, M3U: url-tvg) */
    String defaultXmltvUrl();
}
