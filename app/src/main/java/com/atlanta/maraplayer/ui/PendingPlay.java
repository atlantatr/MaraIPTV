package com.atlanta.maraplayer.ui;

/** Uygulama kapaliyken gelen "mac saatinde kanali ac" istegi. */
public final class PendingPlay {

    private static long profileId = -1;
    private static String channelId;
    private static String channelName;
    private static String title;

    private PendingPlay() {
    }

    public static void set(long pid, String id, String name, String matchTitle) {
        profileId = pid;
        channelId = id;
        channelName = name;
        title = matchTitle;
    }

    /** {channelId, channelName, title} veya yoksa null. */
    public static String[] take(long currentProfile) {
        if (channelId == null || channelId.isEmpty()) return null;
        if (currentProfile != profileId) {
            clear();
            return null;
        }
        String[] out = new String[]{channelId, channelName == null ? "" : channelName,
                title == null ? "" : title};
        clear();
        return out;
    }

    public static void clear() {
        profileId = -1;
        channelId = null;
        channelName = null;
        title = null;
    }
}
