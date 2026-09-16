package de.danoeh.antennapod.playback.service.skip;

public interface SkipSubscription extends AutoCloseable {
    @Override
    void close();
}
