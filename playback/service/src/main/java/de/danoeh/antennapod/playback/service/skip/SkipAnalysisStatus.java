package de.danoeh.antennapod.playback.service.skip;

public enum SkipAnalysisStatus {
    NOT_ANALYZED,
    ANALYZING,
    WAITING_FOR_AUDIO,
    WINDOW_READY,
    DOWNLOAD_REQUIRED,
    READY,
    NO_MATCHES,
    ERROR
}
