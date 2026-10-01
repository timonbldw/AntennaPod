package de.danoeh.antennapod.playback.service.internal;

import android.content.Context;
import android.net.Uri;
import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.datasource.ResolvingDataSource;
import androidx.media3.datasource.TransferListener;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.CacheDataSink;
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;
import de.danoeh.antennapod.net.common.RedirectChecker;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.SeekParameters;
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.extractor.mp3.Mp3Extractor;
import de.danoeh.antennapod.net.common.NetworkUtils;
import de.danoeh.antennapod.net.common.UserAgentInterceptor;
import de.danoeh.antennapod.playback.base.MediaItemAdapter;
import de.danoeh.antennapod.playback.service.R;
import de.danoeh.antennapod.playback.service.skip.SkipStreamingSource;
import de.danoeh.antennapod.storage.preferences.UserPreferences;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@OptIn(markerClass = UnstableApi.class)
public class ExoPlayerUtils {
    private static final long STREAMING_CACHE_FRAGMENT_SIZE = 64 * 1024;
    private static volatile SimpleCache simpleCache;

    @OptIn(markerClass = UnstableApi.class)
    public static ExoPlayer buildPlayer(Context context) {
        if (simpleCache == null) {
            simpleCache = new SimpleCache(new File(context.getCacheDir(), "streaming"),
                    new LeastRecentlyUsedCacheEvictor(100 * 1024 * 1024),
                    new StandaloneDatabaseProvider(context));
        }
        return new ExoPlayer.Builder(context)
                .setLoadControl(new DefaultLoadControl.Builder()
                        .setBufferDurationsMs(
                                (int) TimeUnit.HOURS.toMillis(1),
                                (int) TimeUnit.HOURS.toMillis(3),
                                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
                                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS)
                        .setBackBuffer((int) TimeUnit.MINUTES.toMillis(5), true)
                        .build())
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                        .build(), true)
                .setMediaSourceFactory(new ApMediaSourceFactory(context, simpleCache))
                .setSeekParameters(SeekParameters.EXACT)
                .setHandleAudioBecomingNoisy(UserPreferences.isPauseOnHeadsetDisconnect())
                .build();
    }

    public static void releaseCache() {
        if (simpleCache != null) {
            SkipStreamingSource.release(simpleCache);
            simpleCache.release();
            simpleCache = null;
        }
    }

    public static String translateErrorReason(@NonNull PlaybackException error, Context context) {
        if (NetworkUtils.wasDownloadBlocked(error)) {
            return context.getString(R.string.download_error_blocked);
        }

        Throwable cause = error.getCause();
        if (cause instanceof HttpDataSource.HttpDataSourceException) {
            if (cause.getCause() != null) {
                cause = cause.getCause();
            }
        }
        if (cause != null && "Source error".equals(cause.getMessage())) {
            cause = cause.getCause();
        }
        if (cause != null && cause.getMessage() != null) {
            return cause.getMessage();
        } else if (error.getMessage() != null && cause != null) {
            return error.getMessage() + ": " + cause.getClass().getSimpleName();
        } else {
            return "Unknown error";
        }
    }

    @OptIn(markerClass = UnstableApi.class)
    public static class ApMediaSourceFactory implements MediaSource.Factory {
        private LoadErrorHandlingPolicy loadErrorHandlingPolicy;
        private DrmSessionManagerProvider drmSessionManagerProvider;
        private final DefaultExtractorsFactory extractorsFactory;
        private final DefaultMediaSourceFactory defaultFactory;
        private final Context context;
        private final SimpleCache simpleCache;
        private final ConcurrentHashMap<String, String> redirectCache = new ConcurrentHashMap<>();

        public ApMediaSourceFactory(Context context, SimpleCache simpleCache) {
            super();
            this.context = context;
            this.simpleCache = simpleCache;
            this.extractorsFactory = new DefaultExtractorsFactory();
            this.extractorsFactory.setConstantBitrateSeekingEnabled(true);
            this.extractorsFactory.setMp3ExtractorFlags(Mp3Extractor.FLAG_DISABLE_ID3_METADATA);
            this.defaultFactory = new DefaultMediaSourceFactory(context, extractorsFactory);
        }

        @NonNull
        @Override
        public MediaSource.Factory setDrmSessionManagerProvider(
                @NonNull DrmSessionManagerProvider drmSessionManagerProvider) {
            this.drmSessionManagerProvider = drmSessionManagerProvider;
            return this;
        }

        @NonNull
        @Override
        public MediaSource.Factory setLoadErrorHandlingPolicy(@NonNull LoadErrorHandlingPolicy policy) {
            this.loadErrorHandlingPolicy = policy;
            return this;
        }

        @NonNull
        @Override
        public MediaSource createMediaSource(@NonNull MediaItem mediaItem) {
            defaultFactory.setDataSourceFactory(buildDataSourceFactory(mediaItem));
            if (loadErrorHandlingPolicy != null) {
                defaultFactory.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy);
            }
            if (drmSessionManagerProvider != null) {
                defaultFactory.setDrmSessionManagerProvider(drmSessionManagerProvider);
            }
            return defaultFactory.createMediaSource(mediaItem);
        }

        private DataSource.Factory buildDataSourceFactory(MediaItem mediaItem) {
            DefaultHttpDataSource.Factory httpDataSourceFactory =
                    new DefaultHttpDataSource.Factory();
            httpDataSourceFactory.setUserAgent(UserAgentInterceptor.USER_AGENT);
            httpDataSourceFactory.setAllowCrossProtocolRedirects(true);
            httpDataSourceFactory.setKeepPostFor302Redirects(true);
            String authHeader = mediaItem.requestMetadata.extras != null
                    ? mediaItem.requestMetadata.extras.getString(
                            MediaItemAdapter.KEY_AUTHORIZATION_HEADER)
                    : null;
            if (authHeader != null) {
                httpDataSourceFactory.setDefaultRequestProperties(
                        Collections.singletonMap("Authorization", authHeader));
            }
            DataSource.Factory dataSourceFactory = new DefaultDataSource.Factory(context, httpDataSourceFactory);
            DataSource.Factory resolvingFactory = new ResolvingDataSource.Factory(dataSourceFactory, dataSpec -> {
                String originalUrl = dataSpec.uri.toString();
                if (!originalUrl.startsWith("http")) {
                    return dataSpec;
                }
                String resolvedUrl = redirectCache.get(originalUrl);
                if (resolvedUrl == null) {
                    resolvedUrl = RedirectChecker.getFinalUrl(originalUrl);
                    redirectCache.putIfAbsent(originalUrl, resolvedUrl);
                }
                if (resolvedUrl.equals(originalUrl)) {
                    return dataSpec;
                }
                return dataSpec.withUri(Uri.parse(resolvedUrl));
            });
            String uri = mediaItem.localConfiguration != null
                    ? mediaItem.localConfiguration.uri.toString() : "";
            if (uri.startsWith("http")) {
                SkipStreamingSource.Registration source = SkipStreamingSource.register(
                        simpleCache, mediaItem.localConfiguration.uri,
                        new ResolvingDataSource.Factory(
                                () -> new CaptureHttpDataSource(authHeader), dataSpec -> {
                                    String originalUrl = dataSpec.uri.toString();
                                    String resolvedUrl = redirectCache.get(originalUrl);
                                    if (resolvedUrl == null) {
                                        resolvedUrl = RedirectChecker.getFinalUrl(originalUrl);
                                        redirectCache.putIfAbsent(originalUrl, resolvedUrl);
                                    }
                                    return resolvedUrl.equals(originalUrl)
                                            ? dataSpec : dataSpec.withUri(Uri.parse(resolvedUrl));
                                }));
                CacheDataSource.Factory cacheFactory = new CacheDataSource.Factory()
                        .setCache(simpleCache)
                        .setCacheKeyFactory(dataSpec -> source.cacheKey)
                        .setCacheWriteDataSinkFactory(new CacheDataSink.Factory()
                                .setCache(simpleCache)
                                .setFragmentSize(STREAMING_CACHE_FRAGMENT_SIZE))
                        .setUpstreamDataSourceFactory(resolvingFactory);
                return new ResolvingDataSource.Factory(cacheFactory, dataSpec -> dataSpec.buildUpon()
                        .setFlags(dataSpec.flags | DataSpec.FLAG_ALLOW_CACHE_FRAGMENTATION)
                        .build());
            }
            return resolvingFactory;
        }

        @NonNull
        @Override
        public int[] getSupportedTypes() {
            return defaultFactory.getSupportedTypes();
        }

        static final class CaptureHttpDataSource implements DataSource {
            private final String authHeader;
            private final ConnectionFactory connectionFactory;
            private volatile HttpURLConnection connection;
            private volatile InputStream input;
            private volatile boolean closed;
            private Uri uri;
            private long remaining;

            CaptureHttpDataSource(String authHeader) {
                this(authHeader, url -> (HttpURLConnection) url.openConnection());
            }

            CaptureHttpDataSource(String authHeader, ConnectionFactory connectionFactory) {
                this.authHeader = authHeader;
                this.connectionFactory = connectionFactory;
            }

            @Override
            public void addTransferListener(TransferListener transferListener) {
            }

            @Override
            public long open(DataSpec dataSpec) throws IOException {
                uri = dataSpec.uri;
                connection = connectionFactory.open(new URL(uri.toString()));
                if (closed || Thread.currentThread().isInterrupted()) {
                    connection.disconnect();
                    throw new InterruptedIOException("Audio clip capture was interrupted");
                }
                connection.setConnectTimeout(DefaultHttpDataSource.DEFAULT_CONNECT_TIMEOUT_MILLIS);
                connection.setReadTimeout(DefaultHttpDataSource.DEFAULT_READ_TIMEOUT_MILLIS);
                connection.setRequestProperty("User-Agent", UserAgentInterceptor.USER_AGENT);
                connection.setRequestProperty("Accept-Encoding", "identity");
                if (authHeader != null) {
                    connection.setRequestProperty("Authorization", authHeader);
                }
                if (dataSpec.position != 0 || dataSpec.length != C.LENGTH_UNSET) {
                    String end = dataSpec.length == C.LENGTH_UNSET ? ""
                            : Long.toString(dataSpec.position + dataSpec.length - 1);
                    connection.setRequestProperty("Range", "bytes=" + dataSpec.position + "-" + end);
                }
                connection.connect();
                if (closed || Thread.currentThread().isInterrupted()) {
                    close();
                    throw new InterruptedIOException("Audio clip capture was interrupted");
                }
                int responseCode = connection.getResponseCode();
                if (responseCode < 200 || responseCode > 299) {
                    close();
                    throw new IOException("HTTP response code: " + responseCode);
                }
                if (dataSpec.position > 0 && responseCode != HttpURLConnection.HTTP_PARTIAL) {
                    close();
                    throw new IOException("HTTP server does not support range requests");
                }
                input = connection.getInputStream();
                remaining = dataSpec.length;
                return remaining;
            }

            @Override
            public int read(byte[] buffer, int offset, int length) throws IOException {
                if (remaining == 0) {
                    return C.RESULT_END_OF_INPUT;
                }
                int read = input.read(buffer, offset,
                        remaining == C.LENGTH_UNSET ? length : (int) Math.min(length, remaining));
                if (read > 0 && remaining != C.LENGTH_UNSET) {
                    remaining -= read;
                }
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
            public synchronized void close() throws IOException {
                closed = true;
                InputStream closingInput = input;
                HttpURLConnection closingConnection = connection;
                input = null;
                connection = null;
                try {
                    try {
                        if (closingInput != null) {
                            closingInput.close();
                        }
                    } finally {
                        if (closingConnection != null) {
                            closingConnection.disconnect();
                        }
                    }
                } catch (RuntimeException e) {
                    throw new IOException("Unable to close audio clip connection", e);
                }
            }

            interface ConnectionFactory {
                HttpURLConnection open(URL url) throws IOException;
            }
        }
    }
}
