package de.danoeh.antennapod.playback.service.skip;

import android.media.MediaDataSource;
import android.net.Uri;
import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;
import androidx.media3.datasource.cache.Cache.CacheException;
import androidx.media3.datasource.cache.CacheDataSink;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.CacheSpan;
import androidx.media3.datasource.cache.ContentMetadata;
import androidx.media3.datasource.cache.SimpleCache;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@OptIn(markerClass = UnstableApi.class)
public final class SkipStreamingSource {
    private static final String SCHEME = "skip-cache";
    private static final long MAX_FETCH_BYTES = 8 * 1024 * 1024;
    private static final int HTTP_SKIP_BUFFER_SIZE = 4096;
    private static final long CACHE_FRAGMENT_SIZE = 64 * 1024;
    private static final int MIN_FETCH_SIZE = 64 * 1024;
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
        return register(cache, playbackUri, null);
    }

    public static Registration register(SimpleCache cache, Uri playbackUri,
                                        DataSource.Factory upstreamFactory) {
        synchronized (LOCK) {
            Source previous = PLAYBACK_SOURCES.get(playbackUri);
            if (previous != null && previous.cache == cache && previous.available) {
                if (upstreamFactory != null) {
                    previous.upstreamFactory = upstreamFactory;
                }
                return new Registration(previous.syntheticUri, previous.cacheKey);
            }
            if (previous != null) {
                previous.available = false;
                SYNTHETIC_SOURCES.remove(previous.syntheticUri);
            }
            String id = UUID.randomUUID().toString();
            Uri syntheticUri = new Uri.Builder().scheme(SCHEME).authority(id).build();
            Source source = new Source(cache, playbackUri, syntheticUri, "skip-cache:" + id, upstreamFactory);
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
        return open(syntheticUri, false);
    }

    static CachedMediaDataSource open(Uri syntheticUri, boolean fetchMissing) throws UnavailableException {
        return open(syntheticUri, fetchMissing, MAX_FETCH_BYTES);
    }

    public static MediaDataSource openForPlayback(Uri syntheticUri) throws UnavailableException {
        return open(syntheticUri, true, Long.MAX_VALUE);
    }

    private static CachedMediaDataSource open(Uri syntheticUri, boolean fetchMissing, long fetchBudget)
            throws UnavailableException {
        Source source;
        synchronized (LOCK) {
            source = SYNTHETIC_SOURCES.get(syntheticUri);
            if (source == null || !source.available) {
                throw new UnavailableException("Streaming cache source is unavailable");
            }
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
        if (fetchMissing && source.upstreamFactory == null) {
            throw new UnavailableException("Streaming source cannot fetch audio bytes");
        }
        return new CachedMediaDataSource(source, size, fetchMissing, fetchBudget, Thread.currentThread());
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
        private final boolean fetchMissing;
        private final long fetchBudget;
        private final Thread requestingThread;
        private volatile UnavailableException failure;
        private volatile boolean failureIsTailCacheMiss;
        private volatile boolean cacheMiss;
        private volatile boolean cacheMissAfterData;
        private volatile long missingPosition = -1;
        private volatile int missingLength;
        private volatile String missingPhase;
        private volatile String decoderPhase;
        private volatile boolean interrupted;
        private long fetchedBytes;
        private volatile boolean closed;

        private CachedMediaDataSource(Source source, long size, boolean fetchMissing, long fetchBudget,
                                      Thread requestingThread) {
            this.source = source;
            this.size = size;
            this.fetchMissing = fetchMissing;
            this.fetchBudget = fetchBudget;
            this.requestingThread = requestingThread;
        }

        @Override
        public int readAt(long position, byte[] buffer, int offset, int requestedSize) throws IOException {
            if (position < 0 || offset < 0 || requestedSize < 0 || offset + requestedSize > buffer.length) {
                throw new IllegalArgumentException("Invalid read range");
            }
            if (requestingThread.isInterrupted()) {
                interrupted = true;
                throw new InterruptedIOException("Audio clip capture was interrupted");
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
                try {
                    int read = readSpan(position + totalRead, buffer, offset + totalRead, readSize - totalRead);
                    totalRead += read;
                } catch (UnavailableException error) {
                    if (!cacheMiss) {
                        throw error;
                    }
                    if (totalRead == 0) {
                        return 0;
                    }
                    if (missingPosition == position + totalRead) {
                        cacheMissAfterData = true;
                    }
                    return totalRead;
                }
            }
            return totalRead;
        }

        private synchronized int readSpan(long position, byte[] buffer, int offset, int readSize)
                throws IOException {
            if (!source.available || closed) {
                throw unavailable("Streaming cache source is unavailable", null);
            }
            if (requestingThread.isInterrupted()) {
                interrupted = true;
                throw new InterruptedIOException("Audio clip capture was interrupted");
            }
            CacheSpan span;
            try {
                if (source.cache.getCachedLength(source.cacheKey, position, readSize) <= 0) {
                    if (fetchMissing) {
                        return fetchSpan(position, buffer, offset, readSize);
                    }
                    throw unavailableCacheMiss(position, readSize);
                }
                span = source.cache.startReadWriteNonBlocking(source.cacheKey, position, readSize);
            } catch (RuntimeException | CacheException e) {
                throw unavailable("Streaming cache is unavailable", e);
            }
            if (span == null || !span.isCached || span.file == null || position >= span.position + span.length) {
                if (span != null && span.isHoleSpan()) {
                    source.cache.releaseHoleSpan(span);
                }
                if (fetchMissing) {
                    return fetchSpan(position, buffer, offset, readSize);
                }
                throw unavailableCacheMiss(position, readSize);
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

        private int fetchSpan(long position, byte[] buffer, int offset, int readSize) throws IOException {
            if (fetchedBytes >= fetchBudget) {
                throw unavailable("Audio clip fetch limit exceeded", null);
            }
            long cachedLength;
            try {
                cachedLength = source.cache.getCachedLength(source.cacheKey, position, readSize);
            } catch (RuntimeException e) {
                throw unavailable("Streaming cache is unavailable", e);
            }
            if (cachedLength > 0) {
                return readSpan(position, buffer, offset, readSize);
            }
            if (cachedLength == 0) {
                throw unavailable("Requested audio bytes are unavailable", null);
            }
            int fetchSize = (int) Math.min(Math.min(Math.max(readSize, MIN_FETCH_SIZE),
                    Math.min(-cachedLength, size - position)), fetchBudget - fetchedBytes);
            BoundedUpstreamDataSource upstream = new BoundedUpstreamDataSource(
                    source.upstreamFactory.createDataSource(), fetchBudget - fetchedBytes);
            CacheDataSource dataSource = new CacheDataSource.Factory()
                    .setCache(source.cache)
                    .setCacheKeyFactory(dataSpec -> source.cacheKey)
                    .setCacheWriteDataSinkFactory(new CacheDataSink.Factory()
                            .setCache(source.cache)
                            .setFragmentSize(CACHE_FRAGMENT_SIZE))
                    .setUpstreamDataSourceFactory(() -> upstream)
                    .createDataSource();
            DataSpec dataSpec = new DataSpec.Builder()
                    .setUri(source.playbackUri)
                    .setPosition(position)
                    .setLength(fetchSize)
                    .setFlags(DataSpec.FLAG_ALLOW_CACHE_FRAGMENTATION)
                    .build();
            FetchCancellation cancellation = new FetchCancellation(upstream);
            try {
                cancellation.start();
                dataSource.open(dataSpec);
                byte[] fetched = new byte[fetchSize];
                int totalRead = 0;
                while (totalRead < fetchSize) {
                    int read = dataSource.read(fetched, totalRead, fetchSize - totalRead);
                    if (read < 0) {
                        break;
                    }
                    totalRead += read;
                }
                fetchedBytes += upstream.getTransferredBytes();
                if (upstream.isBudgetExceeded()) {
                    throw unavailable("Audio clip fetch limit exceeded", null);
                }
                if (totalRead == 0) {
                    throw unavailable("Requested audio bytes are unavailable", null);
                }
                if (!source.available) {
                    throw unavailable("Streaming cache source is unavailable", null);
                }
                if (requestingThread.isInterrupted()) {
                    interrupted = true;
                    throw new InterruptedIOException("Audio clip capture was interrupted");
                }
                int returned = Math.min(readSize, totalRead);
                System.arraycopy(fetched, 0, buffer, offset, returned);
                return returned;
            } catch (UnavailableException | InterruptedIOException e) {
                throw e;
            } catch (IOException | RuntimeException e) {
                fetchedBytes += upstream.takeTransferredBytes();
                if (upstream.isBudgetExceeded()) {
                    throw unavailable("Audio clip fetch limit exceeded", e);
                }
                if (cancellation.isCancelled()) {
                    if (cancellation.isInterrupted()) {
                        interrupted = true;
                        Thread.interrupted();
                        throw new InterruptedIOException("Audio clip capture was interrupted");
                    }
                    throw unavailable("Streaming cache source is unavailable", e);
                }
                throw unavailable("Unable to fetch audio bytes", e);
            } finally {
                cancellation.stop();
                try {
                    dataSource.close();
                } catch (IOException ignored) {
                }
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

        private final class FetchCancellation implements Runnable {
            private final DataSource upstream;
            private final Thread callbackThread;
            private boolean stopped;
            private boolean cancelling;
            private boolean cancelled;
            private boolean interrupted;
            private Thread thread;

            private FetchCancellation(DataSource upstream) {
                this.upstream = upstream;
                callbackThread = Thread.currentThread();
            }

            void start() {
                thread = new Thread(this, "skip-audio-fetch-cancellation");
                thread.setDaemon(true);
                thread.start();
            }

            @Override
            public void run() {
                while (true) {
                    boolean requestingThreadInterrupted = requestingThread.isInterrupted();
                    synchronized (this) {
                        if (stopped) {
                            return;
                        }
                        if (requestingThreadInterrupted || closed || !source.available) {
                            interrupted = requestingThreadInterrupted;
                            if (requestingThreadInterrupted) {
                                CachedMediaDataSource.this.interrupted = true;
                            }
                            cancelling = true;
                        } else {
                            try {
                                wait(10);
                            } catch (InterruptedException ignored) {
                                return;
                            }
                            continue;
                        }
                    }
                    callbackThread.interrupt();
                    try {
                        upstream.close();
                    } catch (IOException ignored) {
                    } finally {
                        synchronized (this) {
                            cancelled = true;
                            cancelling = false;
                            notifyAll();
                        }
                    }
                    return;
                }
            }

            synchronized void stop() {
                stopped = true;
                notifyAll();
                boolean restoreInterrupt = false;
                while (cancelling) {
                    try {
                        wait();
                    } catch (InterruptedException ignored) {
                        restoreInterrupt = true;
                    }
                }
                if (restoreInterrupt) {
                    Thread.currentThread().interrupt();
                }
            }

            synchronized boolean isCancelled() {
                return cancelling || cancelled;
            }

            synchronized boolean isInterrupted() {
                return interrupted;
            }
        }

        void acceptOptionalMp3TailProbe(String mime) throws UnavailableException {
            if (!source.available) {
                throw new UnavailableException("Streaming cache source is unavailable");
            }
            if (failure != null) {
                throw failure;
            }
            if (cacheMiss && (!"audio/mpeg".equals(mime)
                    || (!failureIsTailCacheMiss && !cacheMissAfterData))) {
                throw cacheMissException(missingPosition, missingLength, missingPhase);
            }
            if (cacheMiss) {
                failureIsTailCacheMiss = false;
                cacheMiss = false;
                cacheMissAfterData = false;
                missingPosition = -1;
                missingLength = 0;
                missingPhase = null;
            }
        }

        void setDecoderPhase(String decoderPhase) {
            this.decoderPhase = decoderPhase;
        }

        boolean isCacheMiss() {
            return cacheMiss;
        }

        void throwIfCacheMiss() throws UnavailableException {
            if (cacheMiss) {
                throw cacheMissException(missingPosition, missingLength, missingPhase);
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

        void throwIfInterrupted() throws InterruptedException {
            if (interrupted || requestingThread.isInterrupted()) {
                throw new InterruptedException();
            }
        }

        private UnavailableException unavailable(String message, Throwable cause) {
            UnavailableException exception = cause == null
                    ? new UnavailableException(message) : new UnavailableException(message, cause);
            if (failure == null) {
                failure = exception;
            }
            failureIsTailCacheMiss = false;
            cacheMiss = false;
            cacheMissAfterData = false;
            return exception;
        }

        private UnavailableException unavailableCacheMiss(long position, int length) {
            UnavailableException exception = cacheMissException(position, length, decoderPhase);
            if (!cacheMiss) {
                cacheMiss = true;
                failureIsTailCacheMiss = position >= Math.max(0, size - 128);
                missingPosition = position;
                missingLength = length;
                missingPhase = decoderPhase;
            }
            return exception;
        }

        private UnavailableException cacheMissException(long position, int length, String phase) {
            String message = "Requested audio bytes are not cached at byte " + position
                    + " (length " + length + ")";
            if (phase != null) {
                message += " during " + phase;
            }
            return new UnavailableException(message);
        }
    }

    private static final class BoundedUpstreamDataSource implements DataSource, TransferListener {
        private final DataSource delegate;
        private final long budget;
        private long transferredBytes;
        private long networkBytes;
        private boolean opening;
        private boolean openingPositionExceedsBudget;
        private boolean budgetExceeded;

        private BoundedUpstreamDataSource(DataSource delegate, long budget) {
            this.delegate = delegate;
            this.budget = budget;
            delegate.addTransferListener(this);
        }

        @Override
        public void addTransferListener(TransferListener transferListener) {
            delegate.addTransferListener(transferListener);
        }

        @Override
        public long open(DataSpec dataSpec) throws IOException {
            openingPositionExceedsBudget = dataSpec.position > budget;
            if (openingPositionExceedsBudget && budget < HTTP_SKIP_BUFFER_SIZE) {
                budgetExceeded = true;
                throw new IOException("Audio clip fetch limit exceeded");
            }
            opening = true;
            try {
                return delegate.open(dataSpec);
            } finally {
                opening = false;
            }
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            long remaining = budget - transferredBytes;
            if (remaining <= 0) {
                budgetExceeded = true;
                throw new IOException("Audio clip fetch limit exceeded");
            }
            long networkBefore = networkBytes;
            int read = delegate.read(buffer, offset, (int) Math.min(length, remaining));
            if (read > 0 && networkBytes == networkBefore) {
                transferredBytes += read;
            }
            return read;
        }

        @Override
        public Uri getUri() {
            return delegate.getUri();
        }

        @Override
        public Map<String, List<String>> getResponseHeaders() {
            return delegate.getResponseHeaders();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }

        @Override
        public void onTransferInitializing(DataSource source, DataSpec dataSpec, boolean isNetwork) {
        }

        @Override
        public void onTransferStart(DataSource source, DataSpec dataSpec, boolean isNetwork) {
        }

        @Override
        public void onBytesTransferred(DataSource source, DataSpec dataSpec, boolean isNetwork,
                                       int bytesTransferred) {
            if (!isNetwork) {
                return;
            }
            networkBytes += bytesTransferred;
            transferredBytes += bytesTransferred;
            if (opening && openingPositionExceedsBudget
                    && transferredBytes > budget - HTTP_SKIP_BUFFER_SIZE) {
                budgetExceeded = true;
                try {
                    delegate.close();
                } catch (IOException ignored) {
                }
            }
        }

        @Override
        public void onTransferEnd(DataSource source, DataSpec dataSpec, boolean isNetwork) {
        }

        long getTransferredBytes() {
            return transferredBytes;
        }

        long takeTransferredBytes() {
            long bytes = transferredBytes;
            transferredBytes = 0;
            return bytes;
        }

        boolean isBudgetExceeded() {
            return budgetExceeded;
        }
    }

    private static final class Source {
        final SimpleCache cache;
        final Uri playbackUri;
        final Uri syntheticUri;
        final String cacheKey;
        volatile DataSource.Factory upstreamFactory;
        volatile boolean available = true;

        private Source(SimpleCache cache, Uri playbackUri, Uri syntheticUri, String cacheKey,
                       DataSource.Factory upstreamFactory) {
            this.cache = cache;
            this.playbackUri = playbackUri;
            this.syntheticUri = syntheticUri;
            this.cacheKey = cacheKey;
            this.upstreamFactory = upstreamFactory;
        }
    }
}
