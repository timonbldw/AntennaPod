package de.danoeh.antennapod.playback.service.skip;

import android.media.MediaDataSource;
import android.net.Uri;
import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.cache.Cache.CacheException;
import androidx.media3.datasource.cache.CacheSpan;
import androidx.media3.datasource.cache.ContentMetadata;
import androidx.media3.datasource.cache.SimpleCache;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@OptIn(markerClass = UnstableApi.class)
public final class SkipStreamingSource {
    private static final String SCHEME = "skip-cache";
    private static final Object LOCK = new Object();
    private static final Map<Uri, Source> PLAYBACK_SOURCES = new HashMap<>();
    private static final Map<Uri, Source> SYNTHETIC_SOURCES = new HashMap<>();

    private SkipStreamingSource() {
    }

    public static Uri getSource(Uri playbackUri) {
        synchronized (LOCK) {
            Source source = PLAYBACK_SOURCES.get(playbackUri);
            return source != null && source.available ? source.syntheticUri : null;
        }
    }

    public static boolean isStreaming(Uri uri) {
        return uri != null && SCHEME.equals(uri.getScheme());
    }

    public static boolean isAvailable(Uri syntheticUri) {
        synchronized (LOCK) {
            Source source = SYNTHETIC_SOURCES.get(syntheticUri);
            return source != null && source.available;
        }
    }

    public static Registration register(SimpleCache cache, Uri playbackUri) {
        synchronized (LOCK) {
            Source previous = PLAYBACK_SOURCES.get(playbackUri);
            if (previous != null && previous.cache == cache && previous.available) {
                return new Registration(previous.syntheticUri, previous.cacheKey);
            }
            if (previous != null) {
                previous.available = false;
                SYNTHETIC_SOURCES.remove(previous.syntheticUri);
            }
            String id = UUID.randomUUID().toString();
            Uri syntheticUri = new Uri.Builder().scheme(SCHEME).authority(id).build();
            Source source = new Source(cache, syntheticUri, "skip-cache:" + id);
            PLAYBACK_SOURCES.put(playbackUri, source);
            SYNTHETIC_SOURCES.put(syntheticUri, source);
            return new Registration(syntheticUri, source.cacheKey);
        }
    }

    public static void release(SimpleCache cache) {
        synchronized (LOCK) {
            invalidate(cache);
        }
    }

    private static void invalidate(SimpleCache cache) {
        PLAYBACK_SOURCES.values().removeIf(source -> {
            if (source.cache != cache) {
                return false;
            }
            source.available = false;
            SYNTHETIC_SOURCES.remove(source.syntheticUri);
            return true;
        });
    }

    static CachedMediaDataSource open(Uri syntheticUri) throws UnavailableException {
        synchronized (LOCK) {
            Source source = SYNTHETIC_SOURCES.get(syntheticUri);
            if (source == null || !source.available) {
                throw new UnavailableException("Streaming cache source is unavailable");
            }
            long size;
            try {
                size = ContentMetadata.getContentLength(source.cache.getContentMetadata(source.cacheKey));
            } catch (RuntimeException e) {
                throw new UnavailableException("Streaming cache is unavailable", e);
            }
            if (size < 0) {
                throw new UnavailableException("Streaming source size is unavailable");
            }
            return new CachedMediaDataSource(source, size);
        }
    }

    public static final class Registration {
        public final Uri uri;
        public final String cacheKey;

        private Registration(Uri uri, String cacheKey) {
            this.uri = uri;
            this.cacheKey = cacheKey;
        }
    }

    public static class UnavailableException extends IOException {
        public UnavailableException(String message) {
            super(message);
        }

        public UnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    static final class CachedMediaDataSource extends MediaDataSource {
        private final Source source;
        private final long size;
        private volatile UnavailableException failure;
        private volatile boolean failureIsTailCacheMiss;
        private boolean closed;

        private CachedMediaDataSource(Source source, long size) {
            this.source = source;
            this.size = size;
        }

        @Override
        public int readAt(long position, byte[] buffer, int offset, int requestedSize) throws IOException {
            if (position < 0 || offset < 0 || requestedSize < 0 || offset + requestedSize > buffer.length) {
                throw new IllegalArgumentException("Invalid read range");
            }
            if (closed) {
                throw unavailable("Streaming cache source is closed", null);
            }
            if (!source.available) {
                throw unavailable("Streaming cache source is unavailable", null);
            }
            if (position >= size) {
                return -1;
            }
            int readSize = (int) Math.min(requestedSize, size - position);
            if (readSize == 0) {
                return 0;
            }
            int totalRead = 0;
            while (totalRead < readSize) {
                int read = readSpan(position + totalRead, buffer, offset + totalRead, readSize - totalRead);
                totalRead += read;
            }
            return totalRead;
        }

        private int readSpan(long position, byte[] buffer, int offset, int readSize) throws IOException {
            if (!source.available || closed) {
                throw unavailable("Streaming cache source is unavailable", null);
            }
            CacheSpan span;
            try {
                if (source.cache.getCachedLength(source.cacheKey, position, readSize) <= 0) {
                    throw unavailableCacheMiss(position);
                }
                span = source.cache.startReadWriteNonBlocking(source.cacheKey, position, readSize);
            } catch (RuntimeException | CacheException e) {
                throw unavailable("Streaming cache is unavailable", e);
            }
            if (span == null || !span.isCached || span.file == null || position >= span.position + span.length) {
                if (span != null && span.isHoleSpan()) {
                    source.cache.releaseHoleSpan(span);
                }
                throw unavailableCacheMiss(position);
            }
            int available = (int) Math.min(readSize, span.position + span.length - position);
            try (RandomAccessFile file = new RandomAccessFile(span.file, "r")) {
                file.seek(position - span.position);
                int read = file.read(buffer, offset, available);
                if (read <= 0) {
                    throw unavailable("Cached audio bytes are unavailable", null);
                }
                return read;
            } catch (UnavailableException e) {
                throw e;
            } catch (IOException | RuntimeException e) {
                throw unavailable("Cached audio bytes are unavailable", e);
            }
        }

        @Override
        public long getSize() {
            return size;
        }

        @Override
        public void close() {
            closed = true;
        }

        void acceptOptionalMp3TailProbe(String mime) throws UnavailableException {
            if (!source.available) {
                throw new UnavailableException("Streaming cache source is unavailable");
            }
            if (failure != null && (!"audio/mpeg".equals(mime) || !failureIsTailCacheMiss)) {
                throw failure;
            }
            if (failureIsTailCacheMiss) {
                failure = null;
                failureIsTailCacheMiss = false;
            }
        }

        void throwIfUnavailable() throws UnavailableException {
            if (failure != null) {
                throw failure;
            }
            if (!source.available) {
                throw new UnavailableException("Streaming cache source is unavailable");
            }
        }

        private UnavailableException unavailable(String message, Throwable cause) {
            UnavailableException exception = cause == null
                    ? new UnavailableException(message) : new UnavailableException(message, cause);
            failure = exception;
            failureIsTailCacheMiss = false;
            return exception;
        }

        private UnavailableException unavailableCacheMiss(long position) {
            UnavailableException exception = new UnavailableException("Requested audio bytes are not cached");
            boolean tailCacheMiss = position >= Math.max(0, size - 128);
            if (failure == null || failureIsTailCacheMiss) {
                failure = exception;
                failureIsTailCacheMiss = tailCacheMiss;
            }
            return exception;
        }
    }

    private static final class Source {
        final SimpleCache cache;
        final Uri syntheticUri;
        final String cacheKey;
        volatile boolean available = true;

        private Source(SimpleCache cache, Uri syntheticUri, String cacheKey) {
            this.cache = cache;
            this.syntheticUri = syntheticUri;
            this.cacheKey = cacheKey;
        }
    }
}
