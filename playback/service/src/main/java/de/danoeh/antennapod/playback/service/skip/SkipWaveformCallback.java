package de.danoeh.antennapod.playback.service.skip;

public interface SkipWaveformCallback {
    void onSuccess(SkipWaveform waveform);

    void onError(Throwable error);
}
