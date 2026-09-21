package de.danoeh.antennapod.playback.base;

import android.content.Context;

public abstract class SkipAnalysisScheduler {
    private static SkipAnalysisScheduler instance;

    public static SkipAnalysisScheduler get() {
        return instance;
    }

    public static void setImpl(SkipAnalysisScheduler scheduler) {
        instance = scheduler;
    }

    public abstract void enqueue(Context context, long mediaId);

    public abstract void remove(Context context, long mediaId, String feedId, String episodeId);
}
