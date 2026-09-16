package de.danoeh.antennapod.playback.service.skip;

public enum SkipPriority {
    CURRENT_PLAYBACK(0),
    HIGH(1),
    BACKGROUND(2);

    final int value;

    SkipPriority(int value) {
        this.value = value;
    }
}
