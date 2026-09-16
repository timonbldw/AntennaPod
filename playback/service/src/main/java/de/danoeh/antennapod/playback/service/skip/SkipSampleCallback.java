package de.danoeh.antennapod.playback.service.skip;

public interface SkipSampleCallback {
    void onSuccess(SkipSample sample);

    void onError(Throwable error);
}
