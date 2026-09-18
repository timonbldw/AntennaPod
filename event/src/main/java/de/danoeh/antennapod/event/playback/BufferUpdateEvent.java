package de.danoeh.antennapod.event.playback;

public class BufferUpdateEvent {
    private static final float PROGRESS_STARTED = -1;
    private static final float PROGRESS_ENDED = -2;
    final float progress;
    final long bufferedPosition;
    final long duration;

    private BufferUpdateEvent(float progress) {
        this.progress = progress;
        bufferedPosition = -1;
        duration = -1;
    }

    private BufferUpdateEvent(long bufferedPosition, long duration) {
        progress = duration > 0 ? bufferedPosition / (float) duration : 0;
        this.bufferedPosition = bufferedPosition;
        this.duration = duration;
    }

    public static BufferUpdateEvent started() {
        return new BufferUpdateEvent(PROGRESS_STARTED);
    }

    public static BufferUpdateEvent ended() {
        return new BufferUpdateEvent(PROGRESS_ENDED);
    }

    public static BufferUpdateEvent progressUpdate(float progress) {
        return new BufferUpdateEvent(progress);
    }

    public static BufferUpdateEvent bufferedPositionUpdate(long bufferedPosition, long duration) {
        return new BufferUpdateEvent(bufferedPosition, duration);
    }

    public float getProgress() {
        return progress;
    }

    public long getBufferedPosition() {
        return bufferedPosition;
    }

    public long getDuration() {
        return duration;
    }

    public boolean hasBufferedPosition() {
        return bufferedPosition >= 0 && duration > 0;
    }

    public boolean hasStarted() {
        return progress == PROGRESS_STARTED;
    }

    public boolean hasEnded() {
        return progress == PROGRESS_ENDED;
    }
}
