package de.danoeh.antennapod.playback.service.skip;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.OptIn;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.TransferListener;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.SeekParameters;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.extractor.mp3.Mp3Extractor;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@OptIn(markerClass = UnstableApi.class)
public final class SkipPreviewPlayer {
    private SkipPreviewPlayer() {
    }

    public static Player create(Context context, Uri uri) {
        DefaultExtractorsFactory extractorsFactory = new DefaultExtractorsFactory();
        extractorsFactory.setConstantBitrateSeekingEnabled(true);
        extractorsFactory.setMp3ExtractorFlags(Mp3Extractor.FLAG_DISABLE_ID3_METADATA);
        ProgressiveMediaSource mediaSource = new ProgressiveMediaSource.Factory(
                createDataSourceFactory(context, uri), extractorsFactory)
                .createMediaSource(MediaItem.fromUri(uri));
        ExoPlayer player = new ExoPlayer.Builder(context)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                        .build(), false)
                .setSeekParameters(SeekParameters.EXACT)
                .build();
        player.setMediaSource(mediaSource);
        return player;
    }

    static DataSource.Factory createDataSourceFactory(Context context, Uri uri) {
        if (SkipStreamingSource.isStreaming(uri)) {
            return () -> new StreamingDataSource(uri);
        }
        return new DefaultDataSource.Factory(context);
    }

    public static long toSourcePosition(long episodePositionMs, long sourceStartMs) {
        return Math.max(0, episodePositionMs - sourceStartMs);
    }

    private static final class StreamingDataSource implements DataSource {
        private final Uri uri;
        private SkipStreamingSource.CachedMediaDataSource source;
        private long position;
        private long bytesRemaining;

        private StreamingDataSource(Uri uri) {
            this.uri = uri;
        }

        @Override
        public void addTransferListener(TransferListener transferListener) {
        }

        @Override
        public long open(DataSpec dataSpec) throws IOException {
            close();
            source = (SkipStreamingSource.CachedMediaDataSource) SkipStreamingSource.openForPlayback(uri);
            position = dataSpec.position;
            long available = Math.max(0, source.getSize() - position);
            bytesRemaining = dataSpec.length == C.LENGTH_UNSET
                    ? available : Math.min(available, dataSpec.length);
            return bytesRemaining;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            if (bytesRemaining == 0) {
                return C.RESULT_END_OF_INPUT;
            }
            int requested = (int) Math.min(length, bytesRemaining);
            int read = source.readAt(position, buffer, offset, requested);
            if (read == 0) {
                source.throwIfUnavailable();
                source.throwIfCacheMiss();
                throw new SkipStreamingSource.CacheMissException("Requested streaming preview audio is unavailable");
            }
            if (read < 0) {
                bytesRemaining = 0;
                return C.RESULT_END_OF_INPUT;
            }
            position += read;
            bytesRemaining -= read;
            return read;
        }

        @Override
        public Uri getUri() {
            return uri;
        }

        @Override
        public Map<String, List<String>> getResponseHeaders() {
            return Collections.emptyMap();
        }

        @Override
        public void close() throws IOException {
            if (source != null) {
                source.close();
                source = null;
            }
            bytesRemaining = 0;
        }
    }
}
