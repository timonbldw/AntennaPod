package de.danoeh.antennapod.playback.service.skip;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.cache.CacheSpan;
import androidx.media3.datasource.cache.ContentMetadataMutations;
import androidx.media3.datasource.cache.NoOpCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

@OptIn(markerClass = UnstableApi.class)
@LargeTest
@RunWith(AndroidJUnit4.class)
public class SkipStreamingDecoderInstrumentedTest {
    private static final int FIRST_MPEG_OFFSET = 1_474_029;
    private static final long CAPTURED_CONTENT_LENGTH = 0x67138c8L;
    private static final long CAPTURED_DURATION_MS = 5_404_000;
    private static final long CAPTURED_POSITION_MS = 2_226_051;
    private static final long CAPTURED_BUFFERED_POSITION_MS = 2_872_920;
    private final List<SimpleCache> caches = new ArrayList<>();
    private final List<File> cacheDirectories = new ArrayList<>();

    @After
    public void tearDown() {
        for (SimpleCache cache : caches) {
            SkipStreamingSource.release(cache);
            cache.release();
        }
        for (File directory : cacheDirectories) {
            delete(directory);
        }
    }

    @Test
    public void cacheHoleAfterMp3HeadersReturnsAccuratePartialDecode() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        GeneratedMedia fixture = generatedMedia();
        AtomicInteger upstreamAttempts = new AtomicInteger();
        SimpleCache sparseCache = newCache(context);
        SkipStreamingSource.Registration sparse = register(sparseCache, "generated-sparse", upstreamAttempts);
        setContentLength(sparseCache, sparse.cacheKey, fixture.bytes.length);
        writeCache(sparseCache, sparse.cacheKey, 0, fixture.bytes, 0, fixture.prefixLength);
        writeCache(sparseCache, sparse.cacheKey, fixture.laterStart, fixture.bytes, fixture.laterStart,
                fixture.laterEnd - fixture.laterStart);

        try (SkipStreamingSource.CachedMediaDataSource source = SkipStreamingSource.open(sparse.uri)) {
            byte[] boundary = new byte[8];
            assertEquals(4, source.readAt(fixture.prefixLength - 4L, boundary, 0, boundary.length));
            assertArrayEquals(Arrays.copyOfRange(fixture.bytes, fixture.prefixLength - 4, fixture.prefixLength),
                    Arrays.copyOf(boundary, 4));
        }

        SimpleCache fullCache = newCache(context);
        SkipStreamingSource.Registration full = register(fullCache, "generated-full", upstreamAttempts);
        setContentLength(fullCache, full.cacheKey, fixture.bytes.length);
        writeCache(fullCache, full.cacheKey, 0, fixture.bytes, 0, fixture.bytes.length);

        SkipAudioDecoder.DecodedAudio expected = SkipAudioDecoder.decode(context, full.uri, 10_000, 25_000, false);
        SkipAudioDecoder.DecodedAudio actual = SkipAudioDecoder.decode(context, sparse.uri, 10_000, 25_000, false);

        assertTrue(expected.complete);
        assertFalse(expected.eof);
        assertTrue(actual.samples.length > 4_000);
        assertTrue(actual.durationMs > 500);
        assertTrue(actual.cacheMiss);
        assertFalse(actual.complete);
        assertFalse(actual.eof);
        assertTrue(Math.abs(expected.startMs - actual.startMs) <= 30);
        assertArrayEquals(Arrays.copyOf(expected.samples, actual.samples.length), actual.samples, 0.000001f);
        assertEquals(0, upstreamAttempts.get());
    }

    @Test
    public void copiedEmulatorCacheDecodesCapturedWaitingRange() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        AtomicInteger upstreamAttempts = new AtomicInteger();
        SkipStreamingSource.Registration registration = importCapturedCache(context, "captured", upstreamAttempts);

        SkipAudioDecoder.DecodedAudio decoded = SkipAudioDecoder.decode(
                context, registration.uri, CAPTURED_POSITION_MS, 2_258_000, false);

        assertTrue(decoded.samples.length > 4_000);
        assertTrue(decoded.durationMs > 500);
        assertTrue(decoded.startMs >= 2_226_000 && decoded.startMs <= 2_226_150);
        assertTrue(decoded.complete);
        assertFalse(decoded.eof);
        assertEquals(0, upstreamAttempts.get());
    }

    @Test
    @SuppressWarnings("try")
    public void copiedEmulatorCacheAdvancesPausedPlaybackCoverage() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        AtomicInteger upstreamAttempts = new AtomicInteger();
        SkipStreamingSource.Registration registration = importCapturedCache(
                context, "captured-manager", upstreamAttempts);
        SkipAudioDecoder.DecodedAudio sampleAudio = SkipAudioDecoder.decode(
                context, registration.uri, CAPTURED_POSITION_MS, CAPTURED_POSITION_MS + 2_000, false);
        assertTrue(SkipFingerprint.isUsable(sampleAudio.samples));
        AudioFingerprint fingerprint = SkipFingerprint.fromPcm(sampleAudio.samples, SkipFingerprint.SAMPLE_RATE);
        SkipManager manager = SkipManager.getInstance(context);
        String feedId = "instrumented-" + UUID.randomUUID();
        String episodeId = "paused-" + UUID.randomUUID();
        SkipRule draft = manager.createRule(feedId, "Captured cache coverage", SkipRule.Type.FIXED);
        SkipSample sample = new SkipSample("sample", SkipMarker.START, fingerprint.durationMs(), 0,
                sampleAudio.startMs, fingerprint);
        SkipRule rule = new SkipRule(draft.id, draft.name, true, draft.type, 0, 0,
                SkipRule.MissingEndBehavior.UNTOUCHED, 0, 5_000, 0, 0, Collections.singletonList(sample));
        SkipTask task = null;
        boolean saved = false;
        CountDownLatch reachedBuffer = new CountDownLatch(1);
        AtomicInteger advances = new AtomicInteger();
        AtomicLong furthestCoverage = new AtomicLong();
        AtomicReference<SkipAnalysisSnapshot> latest = new AtomicReference<>();
        try {
            manager.saveRule(feedId, rule);
            saved = true;
            try (SkipSubscription subscription = manager.observe(feedId, episodeId, snapshot -> {
                latest.set(snapshot);
                long end = coverageEnd(snapshot);
                long previous = furthestCoverage.getAndUpdate(value -> Math.max(value, end));
                if (end > previous) {
                    advances.incrementAndGet();
                }
                if (end >= CAPTURED_BUFFERED_POSITION_MS - 5_000 && advances.get() >= 3) {
                    reachedBuffer.countDown();
                }
            })) {
                task = manager.analyzeForPlayback(feedId, episodeId, registration.uri,
                        CAPTURED_DURATION_MS, CAPTURED_POSITION_MS, CAPTURED_BUFFERED_POSITION_MS,
                        SkipPriority.CURRENT_PLAYBACK);
                assertTrue("Paused cache analysis did not advance through multiple windows to the buffer",
                        reachedBuffer.await(60, TimeUnit.SECONDS));
                SkipAnalysisSnapshot snapshot = latest.get();
                long requested = CAPTURED_BUFFERED_POSITION_MS - CAPTURED_POSITION_MS;
                long covered = coveredDuration(snapshot, CAPTURED_POSITION_MS, CAPTURED_BUFFERED_POSITION_MS);
                assertTrue("Expected at least three forward coverage updates without a position change",
                        advances.get() >= 3);
                assertTrue("Coverage stopped too far before the unchanged buffered position",
                        coverageEnd(snapshot) >= CAPTURED_BUFFERED_POSITION_MS - 5_000);
                assertTrue("Expected small seek-rounding gaps, not a mostly unsearched buffered range",
                        covered >= requested * 95 / 100);
                assertTrue("Coverage must not claim the entire range when decoder starts leave gaps",
                        covered < requested);
                assertFalse("Paused cache analysis failed: " + snapshot.error,
                        snapshot.status == SkipAnalysisStatus.ERROR);
                assertEquals(0, upstreamAttempts.get());
            }
        } finally {
            if (task != null) {
                task.cancel();
            }
            if (saved) {
                manager.deleteRule(feedId, rule.id);
            }
        }
    }

    private SimpleCache newCache(Context context) {
        File directory = new File(context.getCacheDir(), "skip-decoder-instrumented-" + UUID.randomUUID());
        cacheDirectories.add(directory);
        SimpleCache cache = new SimpleCache(directory, new NoOpCacheEvictor(),
                new StandaloneDatabaseProvider(context));
        caches.add(cache);
        return cache;
    }

    private static SkipStreamingSource.Registration register(SimpleCache cache, String name,
                                                              AtomicInteger upstreamAttempts) {
        DataSource.Factory upstream = () -> {
            upstreamAttempts.incrementAndGet();
            throw new AssertionError("Cache-only decode attempted to create an upstream source");
        };
        return SkipStreamingSource.register(cache, Uri.parse("https://example.com/" + name + ".mp3"), upstream);
    }

    private static void setContentLength(SimpleCache cache, String key, long length) throws IOException {
        cache.applyContentMetadataMutations(key,
                ContentMetadataMutations.setContentLength(new ContentMetadataMutations(), length));
    }

    private SkipStreamingSource.Registration importCapturedCache(Context context, String name,
                                                                 AtomicInteger upstreamAttempts)
            throws Exception {
        Bundle arguments = InstrumentationRegistry.getArguments();
        String fixturePath = arguments.getString("skipCacheFixture");
        assumeTrue(fixturePath != null && !fixturePath.isEmpty());
        File fixture = new File(fixturePath);
        assumeTrue(fixture.isDirectory());
        SimpleCache cache = newCache(context);
        SkipStreamingSource.Registration registration = register(cache, name, upstreamAttempts);
        setContentLength(cache, registration.cacheKey, CAPTURED_CONTENT_LENGTH);
        List<File> spans = new ArrayList<>();
        collectCapturedSpans(fixture, spans);
        assumeTrue(!spans.isEmpty());
        spans.sort(Comparator.comparingLong(SkipStreamingDecoderInstrumentedTest::capturedOffset));
        for (File span : spans) {
            byte[] bytes = readFile(span);
            writeCache(cache, registration.cacheKey, capturedOffset(span), bytes, 0, bytes.length);
        }
        return registration;
    }

    private static long coverageEnd(SkipAnalysisSnapshot snapshot) {
        long end = 0;
        for (SkipCoverage coverage : snapshot.coverage) {
            end = Math.max(end, coverage.endMs);
        }
        return end;
    }

    private static long coveredDuration(SkipAnalysisSnapshot snapshot, long startMs, long endMs) {
        long duration = 0;
        for (SkipCoverage coverage : snapshot.coverage) {
            duration += Math.max(0, Math.min(endMs, coverage.endMs) - Math.max(startMs, coverage.startMs));
        }
        return duration;
    }

    private static void writeCache(SimpleCache cache, String key, long position,
                                   byte[] data, int offset, int length) throws Exception {
        CacheSpan hole = cache.startReadWrite(key, position, length);
        try {
            File file = cache.startFile(key, position, length);
            try (FileOutputStream output = new FileOutputStream(file)) {
                output.write(data, offset, length);
            }
            cache.commitFile(file, length);
        } finally {
            cache.releaseHoleSpan(hole);
        }
    }

    private static GeneratedMedia generatedMedia() throws IOException {
        byte[] mpeg;
        try (InputStream input = InstrumentationRegistry.getInstrumentation().getContext()
                .getAssets().open("30sec.mp3")) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8_192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
            }
            mpeg = output.toByteArray();
        }
        int fourthHeader = findFourthAudioFrameHeader(mpeg);
        assertEquals(729, fourthHeader);
        int laterStart = frameAtOrAfter(mpeg, 20_000);
        int laterEnd = frameAtOrAfter(mpeg, 80_000);
        byte[] media = new byte[FIRST_MPEG_OFFSET + mpeg.length];
        media[0] = 'I';
        media[1] = 'D';
        media[2] = '3';
        media[3] = 4;
        int tagSize = FIRST_MPEG_OFFSET - 10;
        media[6] = (byte) ((tagSize >> 21) & 0x7f);
        media[7] = (byte) ((tagSize >> 14) & 0x7f);
        media[8] = (byte) ((tagSize >> 7) & 0x7f);
        media[9] = (byte) (tagSize & 0x7f);
        System.arraycopy(mpeg, 0, media, FIRST_MPEG_OFFSET, mpeg.length);
        return new GeneratedMedia(media, FIRST_MPEG_OFFSET + fourthHeader + 4,
                FIRST_MPEG_OFFSET + laterStart, FIRST_MPEG_OFFSET + laterEnd);
    }

    private static int findFourthAudioFrameHeader(byte[] mpeg) throws IOException {
        for (int start = 0; start + 4 <= mpeg.length; start++) {
            int firstHeader = readInt(mpeg, start);
            int position = start;
            for (int frame = 0; frame < 5; frame++) {
                if (position + 4 > mpeg.length) {
                    break;
                }
                int header = readInt(mpeg, position);
                int length = mpegFrameLength(header);
                if (length < 0 || (header & 0xfffe0c00) != (firstHeader & 0xfffe0c00)) {
                    break;
                }
                if (frame == 4) {
                    return position;
                }
                position += length;
            }
        }
        throw new IOException("MP3 fixture has fewer than four matching frames");
    }

    private static int frameAtOrAfter(byte[] mpeg, int target) throws IOException {
        int position = 0;
        while (position < target) {
            int length = mpegFrameLength(readInt(mpeg, position));
            if (length < 0) {
                throw new IOException("MP3 fixture frame chain is invalid");
            }
            position += length;
        }
        return position;
    }

    private static int mpegFrameLength(int header) {
        if ((header & 0xffe60000) != 0xffe20000 || ((header >> 19) & 3) != 3
                || ((header >> 17) & 3) != 1) {
            return -1;
        }
        int bitrateIndex = (header >> 12) & 0xf;
        int sampleRateIndex = (header >> 10) & 3;
        int[] bitrates = {0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0};
        int[] sampleRates = {44_100, 48_000, 32_000, 0};
        if (bitrates[bitrateIndex] == 0 || sampleRates[sampleRateIndex] == 0) {
            return -1;
        }
        return 144_000 * bitrates[bitrateIndex] / sampleRates[sampleRateIndex] + ((header >> 9) & 1);
    }

    private static int readInt(byte[] data, int offset) {
        return (data[offset] & 0xff) << 24 | (data[offset + 1] & 0xff) << 16
                | (data[offset + 2] & 0xff) << 8 | data[offset + 3] & 0xff;
    }

    private static void collectCapturedSpans(File directory, List<File> spans) {
        File[] files = directory.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (file.isDirectory()) {
                collectCapturedSpans(file, spans);
            } else if (file.getName().matches("7\\.\\d+\\.\\d+\\.v3\\.exo")) {
                spans.add(file);
            }
        }
    }

    private static long capturedOffset(File span) {
        return Long.parseLong(span.getName().split("\\.")[1]);
    }

    private static byte[] readFile(File file) throws IOException {
        try (InputStream input = new FileInputStream(file)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream((int) file.length());
            byte[] buffer = new byte[8_192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static void delete(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                delete(child);
            }
        }
        file.delete();
    }

    private static final class GeneratedMedia {
        final byte[] bytes;
        final int prefixLength;
        final int laterStart;
        final int laterEnd;

        GeneratedMedia(byte[] bytes, int prefixLength, int laterStart, int laterEnd) {
            this.bytes = bytes;
            this.prefixLength = prefixLength;
            this.laterStart = laterStart;
            this.laterEnd = laterEnd;
        }
    }
}
