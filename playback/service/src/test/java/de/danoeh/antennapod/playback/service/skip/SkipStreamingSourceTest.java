package de.danoeh.antennapod.playback.service.skip;

import android.content.Context;
import android.net.Uri;
import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.ByteArrayDataSource;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.TransferListener;
import androidx.media3.datasource.cache.CacheSpan;
import androidx.media3.datasource.cache.ContentMetadataMutations;
import androidx.media3.datasource.cache.NoOpCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.inspector.MediaExtractorCompat;
import androidx.test.core.app.ApplicationProvider;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@OptIn(markerClass = UnstableApi.class)
@RunWith(RobolectricTestRunner.class)
public class SkipStreamingSourceTest {
    private SimpleCache cache;
    private File directory;

    @Before
    public void setUp() {
        Context context = ApplicationProvider.getApplicationContext();
        directory = new File(context.getCacheDir(), "skip-streaming-" + UUID.randomUUID());
        cache = new SimpleCache(directory, new NoOpCacheEvictor(), new StandaloneDatabaseProvider(context));
    }

    @After
    public void tearDown() {
        if (cache != null) {
            SkipStreamingSource.release(cache);
            cache.release();
        }
    }

    @Test
    public void registrationUsesSessionSpecificSourceAndCacheKey() {
        Uri playbackUri = Uri.parse("https://example.com/audio.mp3");
        SkipStreamingSource.Registration first = SkipStreamingSource.register(cache, playbackUri);
        SkipStreamingSource.release(cache);
        SkipStreamingSource.Registration second = SkipStreamingSource.register(cache, playbackUri);

        assertNotEquals(first.uri, second.uri);
        assertNotEquals(first.cacheKey, second.cacheKey);
        assertFalse(SkipStreamingSource.isAvailable(first.uri));
        assertTrue(SkipStreamingSource.isAvailable(second.uri));
        assertEquals(second.uri, SkipStreamingSource.getSource(playbackUri));
        assertTrue(SkipStreamingSource.isStreaming(second.uri));
    }

    @Test
    public void reconstructionAndOtherSourcesPreserveSessionBytes() throws Exception {
        Uri playbackUri = Uri.parse("https://example.com/audio.mp3");
        SkipStreamingSource.Registration first = SkipStreamingSource.register(cache, playbackUri);
        writeCache(first.cacheKey, new byte[] {1, 2, 3, 4});
        SkipStreamingSource.register(cache, Uri.parse("https://example.com/other.mp3"));
        SkipStreamingSource.Registration reconstructed = SkipStreamingSource.register(cache, playbackUri);
        assertEquals(first.uri, reconstructed.uri);
        assertEquals(first.cacheKey, reconstructed.cacheKey);
        assertTrue(SkipStreamingSource.isAvailable(first.uri));
        assertTrue(cache.isCached(reconstructed.cacheKey, 0, 4));
    }

    @Test
    public void readFillsAcrossAdjacentFragmentsAndStopsOnlyAtRealEof() throws Exception {
        SkipStreamingSource.Registration registration = SkipStreamingSource.register(
                cache, Uri.parse("https://example.com/audio.mp3"));
        byte[] first = new byte[65_536];
        byte[] second = new byte[65_536];
        first[first.length - 1] = 11;
        second[0] = 12;
        second[1] = 13;
        cache.applyContentMetadataMutations(registration.cacheKey,
                ContentMetadataMutations.setContentLength(new ContentMetadataMutations(), 131_072));
        writeCache(registration.cacheKey, first);
        writeCache(registration.cacheKey, 65_536, second);
        try (SkipStreamingSource.CachedMediaDataSource source = SkipStreamingSource.open(registration.uri)) {
            byte[] result = new byte[3];
            assertEquals(3, source.readAt(65_535, result, 0, 3));
            assertArrayEquals(new byte[] {11, 12, 13}, result);
            assertEquals(1, source.readAt(131_071, result, 0, 3));
            assertEquals(-1, source.readAt(131_072, result, 0, 3));
        }
    }

    @Test
    public void repeatedPacketReadsReuseCachedFileWithinFragment() throws Exception {
        SkipStreamingSource.Registration registration = SkipStreamingSource.register(
                cache, Uri.parse("https://example.com/audio.mp3"));
        byte[] cached = new byte[65_536];
        cache.applyContentMetadataMutations(registration.cacheKey,
                ContentMetadataMutations.setContentLength(new ContentMetadataMutations(), cached.length));
        writeCache(registration.cacheKey, cached);
        CountingCachedFileOpener opener = new CountingCachedFileOpener();
        SkipStreamingSource.CachedMediaDataSource source = SkipStreamingSource.open(registration.uri);
        source.setCachedFileOpener(opener);

        byte[] packet = new byte[366];
        for (int position = 0; position + packet.length <= cached.length; position += packet.length) {
            assertEquals(packet.length, source.readAt(position, packet, 0, packet.length));
        }
        assertEquals(packet.length, source.readAt(1_000, packet, 0, packet.length));
        assertEquals(1, opener.opens.get());
        assertEquals(0, opener.closes.get());

        source.close();
        assertEquals(1, opener.closes.get());
    }

    @Test
    public void fragmentTransitionClosesAndOpensCachedFile() throws Exception {
        SkipStreamingSource.Registration registration = SkipStreamingSource.register(
                cache, Uri.parse("https://example.com/audio.mp3"));
        cache.applyContentMetadataMutations(registration.cacheKey,
                ContentMetadataMutations.setContentLength(new ContentMetadataMutations(), 131_072));
        writeCache(registration.cacheKey, new byte[65_536]);
        writeCache(registration.cacheKey, 65_536, new byte[65_536]);
        CountingCachedFileOpener opener = new CountingCachedFileOpener();
        try (SkipStreamingSource.CachedMediaDataSource source = SkipStreamingSource.open(registration.uri)) {
            source.setCachedFileOpener(opener);
            assertEquals(1, source.readAt(0, new byte[1], 0, 1));
            assertEquals(1, source.readAt(65_536, new byte[1], 0, 1));
            assertEquals(2, opener.opens.get());
            assertEquals(1, opener.closes.get());
        }
        assertEquals(2, opener.closes.get());
    }

    @Test
    public void cacheHoleClosesFileAndLaterAvailableSpanReopens() throws Exception {
        SkipStreamingSource.Registration registration = SkipStreamingSource.register(
                cache, Uri.parse("https://example.com/audio.mp3"));
        cache.applyContentMetadataMutations(registration.cacheKey,
                ContentMetadataMutations.setContentLength(new ContentMetadataMutations(), 131_072));
        writeCache(registration.cacheKey, new byte[65_536]);
        CountingCachedFileOpener opener = new CountingCachedFileOpener();
        try (SkipStreamingSource.CachedMediaDataSource source = SkipStreamingSource.open(registration.uri)) {
            source.setCachedFileOpener(opener);
            assertEquals(1, source.readAt(0, new byte[1], 0, 1));
            assertEquals(0, source.readAt(65_536, new byte[1], 0, 1));
            assertEquals(1, opener.opens.get());
            assertEquals(1, opener.closes.get());

            writeCache(registration.cacheKey, 65_536, new byte[] {7});
            byte[] result = new byte[1];
            assertEquals(1, source.readAt(65_536, result, 0, 1));
            assertEquals(7, result[0]);
            assertEquals(2, opener.opens.get());
        }
        assertEquals(2, opener.closes.get());
    }

    @Test
    public void repeatedCloseClosesCachedFileOnce() throws Exception {
        SkipStreamingSource.Registration registration = SkipStreamingSource.register(
                cache, Uri.parse("https://example.com/audio.mp3"));
        cache.applyContentMetadataMutations(registration.cacheKey,
                ContentMetadataMutations.setContentLength(new ContentMetadataMutations(), 1));
        writeCache(registration.cacheKey, new byte[] {1});
        CountingCachedFileOpener opener = new CountingCachedFileOpener();
        SkipStreamingSource.CachedMediaDataSource source = SkipStreamingSource.open(registration.uri);
        source.setCachedFileOpener(opener);
        assertEquals(1, source.readAt(0, new byte[1], 0, 1));

        source.close();
        source.close();

        assertEquals(1, opener.opens.get());
        assertEquals(1, opener.closes.get());
    }

    @Test
    public void independentSourcesRetainIndependentCachedFiles() throws Exception {
        SkipStreamingSource.Registration registration = SkipStreamingSource.register(
                cache, Uri.parse("https://example.com/audio.mp3"));
        cache.applyContentMetadataMutations(registration.cacheKey,
                ContentMetadataMutations.setContentLength(new ContentMetadataMutations(), 1));
        writeCache(registration.cacheKey, new byte[] {1});
        CountingCachedFileOpener firstOpener = new CountingCachedFileOpener();
        CountingCachedFileOpener secondOpener = new CountingCachedFileOpener();
        SkipStreamingSource.CachedMediaDataSource first = SkipStreamingSource.open(registration.uri);
        SkipStreamingSource.CachedMediaDataSource second = SkipStreamingSource.open(registration.uri);
        first.setCachedFileOpener(firstOpener);
        second.setCachedFileOpener(secondOpener);

        assertEquals(1, first.readAt(0, new byte[1], 0, 1));
        assertEquals(1, second.readAt(0, new byte[1], 0, 1));
        first.close();
        assertEquals(1, firstOpener.closes.get());
        assertEquals(0, secondOpener.closes.get());
        assertEquals(1, second.readAt(0, new byte[1], 0, 1));

        second.close();
        assertEquals(1, secondOpener.closes.get());
    }

    @Test
    public void truncatedCachedFileClosesRetainedHandle() throws Exception {
        SkipStreamingSource.Registration registration = SkipStreamingSource.register(
                cache, Uri.parse("https://example.com/audio.mp3"));
        cache.applyContentMetadataMutations(registration.cacheKey,
                ContentMetadataMutations.setContentLength(new ContentMetadataMutations(), 4));
        writeCache(registration.cacheKey, new byte[] {1, 2, 3, 4});
        CountingCachedFileOpener opener = new CountingCachedFileOpener();
        SkipStreamingSource.CachedMediaDataSource source = SkipStreamingSource.open(registration.uri);
        source.setCachedFileOpener(opener);
        assertEquals(1, source.readAt(0, new byte[1], 0, 1));
        File cachedFile = cache.getCachedSpans(registration.cacheKey).first().file;
        try (RandomAccessFile truncated = new RandomAccessFile(cachedFile, "rw")) {
            truncated.setLength(1);
        }

        byte[] result = new byte[] {9};
        assertEquals(0, source.readAt(1, result, 0, 1));
        assertEquals(9, result[0]);
        assertTrue(source.isCacheMiss());
        try {
            source.throwIfCacheMiss();
            fail("Expected truncated cache to become a cache miss");
        } catch (SkipStreamingSource.CacheMissException expected) {
            assertEquals("Requested audio bytes are not cached at byte 1 (length 1)", expected.getMessage());
        }
        assertEquals(1, opener.opens.get());
        assertEquals(1, opener.closes.get());
        source.close();
        assertEquals(1, opener.closes.get());
    }

    @Test
    public void invalidationClosesCachedFileImmediately() throws Exception {
        SkipStreamingSource.Registration registration = SkipStreamingSource.register(
                cache, Uri.parse("https://example.com/audio.mp3"));
        cache.applyContentMetadataMutations(registration.cacheKey,
                ContentMetadataMutations.setContentLength(new ContentMetadataMutations(), 1));
        writeCache(registration.cacheKey, new byte[] {1});
        CountingCachedFileOpener opener = new CountingCachedFileOpener();
        SkipStreamingSource.CachedMediaDataSource source = SkipStreamingSource.open(registration.uri);
        source.setCachedFileOpener(opener);
        assertEquals(1, source.readAt(0, new byte[1], 0, 1));

        SkipStreamingSource.release(cache);
        assertEquals(1, opener.closes.get());
        try {
            source.readAt(0, new byte[1], 0, 1);
            fail("Expected invalidated source");
        } catch (SkipStreamingSource.UnavailableException expected) {
            assertEquals("Streaming cache source is unavailable", expected.getMessage());
        }
        source.close();
        assertEquals(1, opener.closes.get());
    }

    @Test
    public void readAcrossMissingFragmentReturnsAvailablePrefixAndRetainsMiss() throws Exception {
        SkipStreamingSource.Registration registration = SkipStreamingSource.register(
                cache, Uri.parse("https://example.com/audio.mp3"));
        cache.applyContentMetadataMutations(registration.cacheKey,
                ContentMetadataMutations.setContentLength(new ContentMetadataMutations(), 196_608));
        writeCache(registration.cacheKey, new byte[65_536]);
        writeCache(registration.cacheKey, 131_072, new byte[65_536]);
        try (SkipStreamingSource.CachedMediaDataSource source = SkipStreamingSource.open(registration.uri)) {
            source.setDecoderPhase("extractor initialization");
            byte[] result = new byte[] {9, 8, 7};
            assertEquals(1, source.readAt(65_535, result, 0, 3));
            assertArrayEquals(new byte[] {0, 8, 7}, result);
            source.throwIfUnavailable();
            source.setDecoderPhase("sample reading");
            try {
                source.throwIfCacheMiss();
                fail("Expected retained intervening fragment miss");
            } catch (SkipStreamingSource.UnavailableException expected) {
                assertEquals("Requested audio bytes are not cached at byte 65536 (length 2)"
                        + " during extractor initialization", expected.getMessage());
            }
        }
    }

    @Test
    public void hardFailureAfterShortReadIsNotClearedOrMasked() throws Exception {
        SkipStreamingSource.Registration registration = SkipStreamingSource.register(
                cache, Uri.parse("https://example.com/audio.mp3"));
        cache.applyContentMetadataMutations(registration.cacheKey,
                ContentMetadataMutations.setContentLength(new ContentMetadataMutations(), 8));
        writeCache(registration.cacheKey, new byte[] {1, 2, 3, 4});

        SkipStreamingSource.CachedMediaDataSource source = SkipStreamingSource.open(registration.uri);
        assertEquals(4, source.readAt(0, new byte[8], 0, 8));
        source.close();
        try {
            source.readAt(0, new byte[1], 0, 1);
            fail("Expected closed source failure");
        } catch (SkipStreamingSource.UnavailableException expected) {
            assertEquals("Streaming cache source is closed", expected.getMessage());
        }
        try {
            source.throwIfUnavailable();
            fail("Expected retained closed source failure");
        } catch (SkipStreamingSource.UnavailableException expected) {
            assertEquals("Streaming cache source is closed", expected.getMessage());
        }
    }

    @Test
    public void readsOnlyCachedSpanAndDoesNotTreatHoleAsEof() throws Exception {
        Uri playbackUri = Uri.parse("https://example.com/audio.mp3");
        SkipStreamingSource.Registration registration = SkipStreamingSource.register(cache, playbackUri);
        byte[] cached = new byte[] {10, 11, 12, 13};
        cache.applyContentMetadataMutations(registration.cacheKey,
                ContentMetadataMutations.setContentLength(new ContentMetadataMutations(), 8));
        writeCache(registration.cacheKey, cached);

        try (SkipStreamingSource.CachedMediaDataSource source = SkipStreamingSource.open(registration.uri)) {
            byte[] result = new byte[3];
            assertEquals(3, source.readAt(1, result, 0, result.length));
            assertArrayEquals(new byte[] {11, 12, 13}, result);
            assertEquals(0, source.readAt(4, result, 0, result.length));
            try {
                source.throwIfCacheMiss();
                fail("Expected unavailable cache hole side channel");
            } catch (SkipStreamingSource.UnavailableException expected) {
                assertEquals("Requested audio bytes are not cached at byte 4 (length 3)", expected.getMessage());
            }
        }
    }

    @Test
    public void extractorBridgeThrowsOnMissingSpan() throws Exception {
        SkipStreamingSource.Registration registration = SkipStreamingSource.register(
                cache, Uri.parse("https://example.com/audio.mp3"));
        cache.applyContentMetadataMutations(registration.cacheKey,
                ContentMetadataMutations.setContentLength(new ContentMetadataMutations(), 8));

        try (SkipStreamingSource.CachedMediaDataSource source = SkipStreamingSource.open(registration.uri)) {
            SkipAudioDecoder.ExtractorMediaDataSource extractorSource =
                    new SkipAudioDecoder.ExtractorMediaDataSource(source);
            try {
                extractorSource.readAt(4, new byte[1], 0, 1);
                fail("Expected extractor cache miss");
            } catch (SkipStreamingSource.CacheMissException expected) {
                assertEquals("Requested audio bytes are not cached at byte 4 (length 1)",
                        expected.getMessage());
            }
        }
    }

    @Test(timeout = 1_000)
    public void media3ExtractorInitializationStopsAtMissingSpan() throws Exception {
        SkipStreamingSource.Registration registration = SkipStreamingSource.register(
                cache, Uri.parse("https://example.com/audio.mp3"));
        cache.applyContentMetadataMutations(registration.cacheKey,
                ContentMetadataMutations.setContentLength(new ContentMetadataMutations(), 1024));

        try (SkipStreamingSource.CachedMediaDataSource source = SkipStreamingSource.open(registration.uri)) {
            MediaExtractorCompat extractor = new MediaExtractorCompat(new DefaultExtractorsFactory(),
                    new DefaultDataSource.Factory(ApplicationProvider.getApplicationContext()));
            try {
                extractor.setDataSource(new SkipAudioDecoder.ExtractorMediaDataSource(source));
                fail("Expected extractor initialization failure");
            } catch (IOException expected) {
                assertTrue(source.isCacheMiss());
            } finally {
                extractor.release();
            }
        }
    }

    @Test
    public void releaseInvalidatesSyntheticSource() throws Exception {
        Uri playbackUri = Uri.parse("https://example.com/audio.mp3");
        SkipStreamingSource.Registration registration = SkipStreamingSource.register(cache, playbackUri);

        SkipStreamingSource.release(cache);

        assertFalse(SkipStreamingSource.isAvailable(registration.uri));
        assertNull(SkipStreamingSource.getSource(playbackUri));
        try {
            SkipStreamingSource.open(registration.uri);
            fail("Expected unavailable released source");
        } catch (SkipStreamingSource.UnavailableException expected) {
            assertEquals("Streaming cache source is unavailable", expected.getMessage());
        }
    }

    @Test
    public void missingContentLengthIsUnavailable() throws Exception {
        SkipStreamingSource.Registration registration = SkipStreamingSource.register(
                cache, Uri.parse("https://example.com/audio.mp3"));

        try {
            SkipStreamingSource.open(registration.uri);
            fail("Expected unavailable metadata");
        } catch (SkipStreamingSource.UnavailableException expected) {
            assertEquals("Streaming source size is unavailable", expected.getMessage());
        }
    }

    @Test
    public void optionalMp3TailProbeDoesNotPoisonRequiredReads() throws Exception {
        Uri playbackUri = Uri.parse("https://example.com/audio.mp3");
        SkipStreamingSource.Registration registration = SkipStreamingSource.register(cache, playbackUri);
        byte[] cached = new byte[] {10, 11, 12, 13};
        cache.applyContentMetadataMutations(registration.cacheKey,
                ContentMetadataMutations.setContentLength(new ContentMetadataMutations(), 256));
        writeCache(registration.cacheKey, cached);

        try (SkipStreamingSource.CachedMediaDataSource source = SkipStreamingSource.open(registration.uri)) {
            assertEquals(0, source.readAt(128, new byte[1], 0, 1));
            source.acceptOptionalMp3TailProbe("audio/mpeg");
            byte[] result = new byte[4];
            assertEquals(4, source.readAt(0, result, 0, result.length));
            source.throwIfUnavailable();
            assertArrayEquals(cached, result);
            assertEquals(0, source.readAt(4, new byte[1], 0, 1));
            try {
                source.throwIfCacheMiss();
                fail("Expected required read failure to remain visible");
            } catch (SkipStreamingSource.UnavailableException unavailable) {
                assertEquals("Requested audio bytes are not cached at byte 4 (length 1)",
                        unavailable.getMessage());
            }
        }
    }

    @Test
    public void identifiedMp3CanAcceptShortInitializationRead() throws Exception {
        SkipStreamingSource.Registration registration = SkipStreamingSource.register(
                cache, Uri.parse("https://example.com/audio.mp3"));
        cache.applyContentMetadataMutations(registration.cacheKey,
                ContentMetadataMutations.setContentLength(new ContentMetadataMutations(), 256));
        writeCache(registration.cacheKey, new byte[] {10, 11, 12, 13});

        try (SkipStreamingSource.CachedMediaDataSource source = SkipStreamingSource.open(registration.uri)) {
            source.setDecoderPhase("extractor initialization");
            byte[] result = new byte[8];
            assertEquals(4, source.readAt(0, result, 0, result.length));
            try {
                source.throwIfCacheMiss();
                fail("Expected retained initialization miss");
            } catch (SkipStreamingSource.UnavailableException expected) {
                assertEquals("Requested audio bytes are not cached at byte 4 (length 4)"
                        + " during extractor initialization", expected.getMessage());
            }
            source.acceptOptionalMp3TailProbe("audio/mpeg");
            source.throwIfUnavailable();
            source.throwIfCacheMiss();
        }
    }

    @Test
    public void prefixGapCannotBeForgivenAsMp3TailProbe() throws Exception {
        SkipStreamingSource.Registration registration = SkipStreamingSource.register(
                cache, Uri.parse("https://example.com/audio.mp3"));
        cache.applyContentMetadataMutations(registration.cacheKey,
                ContentMetadataMutations.setContentLength(new ContentMetadataMutations(), 256));

        try (SkipStreamingSource.CachedMediaDataSource source = SkipStreamingSource.open(registration.uri)) {
            assertEquals(0, source.readAt(0, new byte[1], 0, 1));
            assertEquals(0, source.readAt(128, new byte[1], 0, 1));
            try {
                source.acceptOptionalMp3TailProbe("audio/mpeg");
                fail("Expected prefix miss to remain visible");
            } catch (SkipStreamingSource.UnavailableException unavailable) {
                assertEquals("Requested audio bytes are not cached at byte 0 (length 1)",
                        unavailable.getMessage());
            }
        }
    }

    @Test
    public void tailProbeIsNotForgivenForNonMp3() throws Exception {
        SkipStreamingSource.Registration registration = SkipStreamingSource.register(
                cache, Uri.parse("https://example.com/audio.aac"));
        cache.applyContentMetadataMutations(registration.cacheKey,
                ContentMetadataMutations.setContentLength(new ContentMetadataMutations(), 256));

        try (SkipStreamingSource.CachedMediaDataSource source = SkipStreamingSource.open(registration.uri)) {
            assertEquals(0, source.readAt(128, new byte[1], 0, 1));
            try {
                source.acceptOptionalMp3TailProbe("audio/aac");
                fail("Expected non-MP3 miss to remain visible");
            } catch (SkipStreamingSource.UnavailableException unavailable) {
                assertEquals("Requested audio bytes are not cached at byte 128 (length 1)",
                        unavailable.getMessage());
            }
        }
    }

    @Test
    public void fetchesRequestedBytesAndReusesCache() throws Exception {
        byte[] upstream = new byte[] {10, 11, 12, 13, 14, 15};
        AtomicInteger sources = new AtomicInteger();
        DataSource.Factory upstreamFactory = () -> {
            sources.incrementAndGet();
            return new ByteArrayDataSource(upstream);
        };
        SkipStreamingSource.Registration registration = SkipStreamingSource.register(cache,
                Uri.parse("https://example.com/audio.mp3"), upstreamFactory);
        cache.applyContentMetadataMutations(registration.cacheKey,
                ContentMetadataMutations.setContentLength(new ContentMetadataMutations(), upstream.length));

        try (SkipStreamingSource.CachedMediaDataSource source = SkipStreamingSource.open(registration.uri, true)) {
            byte[] result = new byte[3];
            assertEquals(3, source.readAt(2, result, 0, result.length));
            assertArrayEquals(new byte[] {12, 13, 14}, result);
        }
        assertEquals(1, sources.get());
        try (SkipStreamingSource.CachedMediaDataSource source = SkipStreamingSource.open(registration.uri)) {
            byte[] result = new byte[3];
            assertEquals(3, source.readAt(2, result, 0, result.length));
            assertArrayEquals(new byte[] {12, 13, 14}, result);
        }
        assertEquals(1, sources.get());
    }

    @Test
    public void cacheOnlyOpenDoesNotUseConfiguredUpstream() throws Exception {
        AtomicInteger sources = new AtomicInteger();
        SkipStreamingSource.Registration registration = SkipStreamingSource.register(cache,
                Uri.parse("https://example.com/audio.mp3"), () -> {
                    sources.incrementAndGet();
                    return new ByteArrayDataSource(new byte[] {1});
                });
        cache.applyContentMetadataMutations(registration.cacheKey,
                ContentMetadataMutations.setContentLength(new ContentMetadataMutations(), 1));

        try (SkipStreamingSource.CachedMediaDataSource source = SkipStreamingSource.open(registration.uri)) {
            assertEquals(0, source.readAt(0, new byte[1], 0, 1));
            assertTrue(source.isCacheMiss());
        }
        assertEquals(0, sources.get());
    }

    @Test
    public void fetchBudgetStopsAdditionalUpstreamBytes() throws Exception {
        byte[] upstream = new byte[8 * 1024 * 1024 + 1];
        SkipStreamingSource.Registration registration = SkipStreamingSource.register(cache,
                Uri.parse("https://example.com/audio.mp3"), () -> new ByteArrayDataSource(upstream));
        cache.applyContentMetadataMutations(registration.cacheKey,
                ContentMetadataMutations.setContentLength(new ContentMetadataMutations(), upstream.length));

        try (SkipStreamingSource.CachedMediaDataSource source = SkipStreamingSource.open(registration.uri, true)) {
            assertEquals(8 * 1024 * 1024, source.readAt(0, upstream, 0, 8 * 1024 * 1024));
            try {
                source.readAt(8 * 1024 * 1024, upstream, 0, 1);
                fail("Expected fetch budget failure");
            } catch (SkipStreamingSource.UnavailableException expected) {
                assertEquals("Audio clip fetch limit exceeded", expected.getMessage());
            }
        }
    }

    @Test
    public void ignoredRangeBytesConsumedDuringOpenCountAgainstBudget() throws Exception {
        IgnoredRangeDataSource upstream = new IgnoredRangeDataSource();
        long position = 8 * 1024 * 1024 + 1L;
        SkipStreamingSource.Registration registration = SkipStreamingSource.register(cache,
                Uri.parse("https://example.com/audio.mp3"), () -> upstream);
        cache.applyContentMetadataMutations(registration.cacheKey,
                ContentMetadataMutations.setContentLength(new ContentMetadataMutations(), position + 1));

        try (SkipStreamingSource.CachedMediaDataSource source = SkipStreamingSource.open(registration.uri, true)) {
            try {
                source.readAt(position, new byte[1], 0, 1);
                fail("Expected fetch budget failure");
            } catch (SkipStreamingSource.UnavailableException expected) {
                assertEquals("Audio clip fetch limit exceeded", expected.getMessage());
            }
        }
        assertTrue(upstream.transferredBytes.get() <= 8 * 1024 * 1024);
    }

    @Test
    public void interruptingReadingThreadClosesInFlightUpstream() throws Exception {
        BlockingDataSource upstream = new BlockingDataSource();
        SkipStreamingSource.Registration registration = SkipStreamingSource.register(cache,
                Uri.parse("https://example.com/audio.mp3"), () -> upstream);
        cache.applyContentMetadataMutations(registration.cacheKey,
                ContentMetadataMutations.setContentLength(new ContentMetadataMutations(), 1));
        AtomicReference<SkipStreamingSource.CachedMediaDataSource> source = new AtomicReference<>();
        CountDownLatch sourceReady = new CountDownLatch(1);
        CountDownLatch releaseOwner = new CountDownLatch(1);
        Thread owner = new Thread(() -> {
            try {
                source.set(SkipStreamingSource.open(registration.uri, true));
                sourceReady.countDown();
                try {
                    releaseOwner.await();
                } catch (InterruptedException expected) {
                    Thread.currentThread().interrupt();
                    while (releaseOwner.getCount() > 0) {
                        Thread.yield();
                    }
                }
            } catch (Exception ignored) {
            }
        });
        owner.start();
        Thread callback = null;
        try {
            assertTrue(sourceReady.await(1, TimeUnit.SECONDS));
            AtomicReference<Throwable> failure = new AtomicReference<>();
            callback = new Thread(() -> {
                try {
                    source.get().readAt(0, new byte[1], 0, 1);
                } catch (Throwable error) {
                    failure.set(error);
                }
            });
            callback.start();
            assertTrue(upstream.opened.await(1, TimeUnit.SECONDS));
            callback.interrupt();
            callback.join(1_000);
            assertFalse(callback.isAlive());
            assertTrue(upstream.closed.get());
            assertTrue(failure.get() instanceof InterruptedIOException);
        } finally {
            releaseOwner.countDown();
            owner.join(1_000);
            if (callback != null && callback.isAlive()) {
                callback.interrupt();
                callback.join(1_000);
            }
            if (source.get() != null) {
                source.get().close();
            }
        }
    }

    @Test
    public void invalidatingSessionClosesInFlightUpstream() throws Exception {
        BlockingDataSource upstream = new BlockingDataSource();
        SkipStreamingSource.Registration registration = SkipStreamingSource.register(cache,
                Uri.parse("https://example.com/audio.mp3"), () -> upstream);
        cache.applyContentMetadataMutations(registration.cacheKey,
                ContentMetadataMutations.setContentLength(new ContentMetadataMutations(), 1));
        SkipStreamingSource.CachedMediaDataSource source = SkipStreamingSource.open(registration.uri, true);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread callback = new Thread(() -> {
            try {
                source.readAt(0, new byte[1], 0, 1);
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        callback.start();
        assertTrue(upstream.opened.await(1, TimeUnit.SECONDS));
        SkipStreamingSource.release(cache);
        callback.join(1_000);
        assertFalse(callback.isAlive());
        assertTrue(upstream.closed.get());
        assertTrue(failure.get() instanceof SkipStreamingSource.UnavailableException);
        assertEquals("Streaming cache source is unavailable", failure.get().getMessage());
        source.close();
    }

    @Test
    public void closingSourceClosesInFlightUpstreamWithoutInterruptOrInvalidation() throws Exception {
        BlockingDataSource upstream = new BlockingDataSource();
        SkipStreamingSource.Registration registration = SkipStreamingSource.register(cache,
                Uri.parse("https://example.com/audio.mp3"), () -> upstream);
        cache.applyContentMetadataMutations(registration.cacheKey,
                ContentMetadataMutations.setContentLength(new ContentMetadataMutations(), 1));
        SkipStreamingSource.CachedMediaDataSource source = SkipStreamingSource.open(registration.uri, true);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread reader = new Thread(() -> {
            try {
                source.readAt(0, new byte[1], 0, 1);
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        reader.start();
        assertTrue(upstream.opened.await(1, TimeUnit.SECONDS));

        Thread closer = new Thread(source::close);
        closer.start();
        reader.join(1_000);
        closer.join(1_000);

        assertFalse(reader.isAlive());
        assertFalse(closer.isAlive());
        assertTrue(upstream.closed.get());
        assertTrue(failure.get() instanceof SkipStreamingSource.UnavailableException);
        assertEquals("Streaming cache source is unavailable", failure.get().getMessage());
    }

    @Test
    public void interruptedFetchWaitsForCommittedCancellation() throws Exception {
        BlockingCloseDataSource upstream = new BlockingCloseDataSource();
        SkipStreamingSource.Registration registration = SkipStreamingSource.register(cache,
                Uri.parse("https://example.com/audio.mp3"), () -> upstream);
        cache.applyContentMetadataMutations(registration.cacheKey,
                ContentMetadataMutations.setContentLength(new ContentMetadataMutations(), 1));
        AtomicReference<SkipStreamingSource.CachedMediaDataSource> source = new AtomicReference<>();
        CountDownLatch sourceReady = new CountDownLatch(1);
        CountDownLatch releaseOwner = new CountDownLatch(1);
        Thread owner = new Thread(() -> {
            try {
                source.set(SkipStreamingSource.open(registration.uri, true));
                sourceReady.countDown();
                try {
                    releaseOwner.await();
                } catch (InterruptedException expected) {
                    Thread.currentThread().interrupt();
                    while (releaseOwner.getCount() > 0) {
                        Thread.yield();
                    }
                }
            } catch (Exception ignored) {
            }
        });
        owner.start();
        Thread callback = null;
        try {
            assertTrue(sourceReady.await(1, TimeUnit.SECONDS));
            callback = new Thread(() -> {
                try {
                    source.get().readAt(0, new byte[1], 0, 1);
                } catch (IOException ignored) {
                }
            });
            callback.start();
            assertTrue(upstream.reading.await(1, TimeUnit.SECONDS));
            callback.interrupt();
            assertTrue(upstream.closing.await(1, TimeUnit.SECONDS));
            upstream.finishRead.countDown();
            Thread.sleep(20);
            assertTrue(callback.isAlive());
            upstream.finishClose.countDown();
            callback.join(1_000);
            assertFalse(callback.isAlive());
        } finally {
            upstream.finishRead.countDown();
            upstream.finishClose.countDown();
            releaseOwner.countDown();
            owner.join(1_000);
            if (callback != null && callback.isAlive()) {
                callback.interrupt();
                callback.join(1_000);
            }
            if (source.get() != null) {
                source.get().close();
            }
        }
    }

    @Test
    public void readRebindsCancellationFromOpeningThread() throws Exception {
        byte[] upstream = new byte[] {1};
        SkipStreamingSource.Registration registration = SkipStreamingSource.register(cache,
                Uri.parse("https://example.com/audio.mp3"), () -> new ByteArrayDataSource(upstream));
        cache.applyContentMetadataMutations(registration.cacheKey,
                ContentMetadataMutations.setContentLength(new ContentMetadataMutations(), upstream.length));
        SkipStreamingSource.CachedMediaDataSource source = SkipStreamingSource.open(registration.uri, true);
        try {
            AtomicInteger completed = new AtomicInteger();
            AtomicBoolean start = new AtomicBoolean();
            Thread callback = new Thread(() -> {
                while (!start.get()) {
                    Thread.yield();
                }
                try {
                    if (source.readAt(0, new byte[1], 0, 1) == 1) {
                        completed.incrementAndGet();
                    }
                } catch (Exception ignored) {
                }
            });
            callback.start();
            Thread.currentThread().interrupt();
            start.set(true);
            while (callback.isAlive()) {
                Thread.yield();
            }
            assertEquals(1, completed.get());
        } finally {
            Thread.interrupted();
            source.close();
        }
    }

    @Test
    public void rebindingIgnoresInterruptedPreviousOwner() throws Exception {
        SkipStreamingSource.Registration registration = SkipStreamingSource.register(
                cache, Uri.parse("https://example.com/audio.mp3"));
        cache.applyContentMetadataMutations(registration.cacheKey,
                ContentMetadataMutations.setContentLength(new ContentMetadataMutations(), 1));
        writeCache(registration.cacheKey, new byte[] {7});
        AtomicReference<SkipStreamingSource.CachedMediaDataSource> source = new AtomicReference<>();
        Thread previousOwner = new Thread(() -> {
            try {
                source.set(SkipStreamingSource.open(registration.uri));
                Thread.currentThread().interrupt();
            } catch (IOException ignored) {
            }
        });
        previousOwner.start();
        previousOwner.join();

        try (SkipStreamingSource.CachedMediaDataSource rebound = source.get()) {
            rebound.bindToCurrentThread();
            byte[] result = new byte[1];
            assertEquals(1, rebound.readAt(0, result, 0, 1));
            assertEquals(7, result[0]);
            rebound.throwIfInterrupted();
        }
    }

    @Test
    public void invalidationPreventsFetchFromExistingOpenSource() throws Exception {
        byte[] upstream = new byte[] {1};
        AtomicInteger sources = new AtomicInteger();
        SkipStreamingSource.Registration registration = SkipStreamingSource.register(cache,
                Uri.parse("https://example.com/audio.mp3"), () -> {
                    sources.incrementAndGet();
                    return new ByteArrayDataSource(upstream);
                });
        cache.applyContentMetadataMutations(registration.cacheKey,
                ContentMetadataMutations.setContentLength(new ContentMetadataMutations(), upstream.length));
        try (SkipStreamingSource.CachedMediaDataSource source = SkipStreamingSource.open(registration.uri, true)) {
            SkipStreamingSource.release(cache);
            try {
                source.readAt(0, new byte[1], 0, 1);
                fail("Expected invalidated source");
            } catch (SkipStreamingSource.UnavailableException expected) {
                assertEquals("Streaming cache source is unavailable", expected.getMessage());
            }
        }
        assertEquals(0, sources.get());
    }

    private void writeCache(String key, byte[] data) throws Exception {
        writeCache(key, 0, data);
    }

    private void writeCache(String key, long position, byte[] data) throws Exception {
        CacheSpan hole = cache.startReadWrite(key, position, data.length);
        try {
            File file = cache.startFile(key, position, data.length);
            try (FileOutputStream output = new FileOutputStream(file)) {
                output.write(data);
            }
            cache.commitFile(file, data.length);
        } finally {
            cache.releaseHoleSpan(hole);
        }
    }

    private static final class CountingCachedFileOpener implements SkipStreamingSource.CachedFileOpener {
        final AtomicInteger opens = new AtomicInteger();
        final AtomicInteger closes = new AtomicInteger();

        @Override
        public SkipStreamingSource.CachedFile open(File source) throws IOException {
            opens.incrementAndGet();
            RandomAccessFile file = new RandomAccessFile(source, "r");
            return new SkipStreamingSource.CachedFile() {
                @Override
                public void seek(long position) throws IOException {
                    file.seek(position);
                }

                @Override
                public int read(byte[] buffer, int offset, int length) throws IOException {
                    return file.read(buffer, offset, length);
                }

                @Override
                public void close() throws IOException {
                    file.close();
                    closes.incrementAndGet();
                }
            };
        }
    }

    private static final class IgnoredRangeDataSource implements DataSource {
        private final List<TransferListener> listeners = new ArrayList<>();
        final AtomicInteger transferredBytes = new AtomicInteger();
        private volatile boolean closed;

        @Override
        public void addTransferListener(TransferListener transferListener) {
            listeners.add(transferListener);
        }

        @Override
        public long open(DataSpec dataSpec) throws IOException {
            for (TransferListener listener : listeners) {
                listener.onTransferStart(this, dataSpec, true);
            }
            long remaining = dataSpec.position;
            while (remaining > 0 && !closed) {
                int bytes = (int) Math.min(4096, remaining);
                transferredBytes.addAndGet(bytes);
                for (TransferListener listener : listeners) {
                    listener.onBytesTransferred(this, dataSpec, true, bytes);
                }
                remaining -= bytes;
            }
            if (closed) {
                throw new IOException("closed");
            }
            return dataSpec.length;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            buffer[offset] = 1;
            return 1;
        }

        @Override
        public Uri getUri() {
            return Uri.parse("https://example.com/audio.mp3");
        }

        @Override
        public Map<String, List<String>> getResponseHeaders() {
            return Collections.emptyMap();
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static final class BlockingDataSource implements DataSource {
        final CountDownLatch opened = new CountDownLatch(1);
        final AtomicBoolean closed = new AtomicBoolean();

        @Override
        public void addTransferListener(TransferListener transferListener) {
        }

        @Override
        public long open(DataSpec dataSpec) throws IOException {
            opened.countDown();
            while (!closed.get()) {
                Thread.yield();
            }
            throw new IOException("closed");
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            return -1;
        }

        @Override
        public Uri getUri() {
            return Uri.parse("https://example.com/audio.mp3");
        }

        @Override
        public Map<String, List<String>> getResponseHeaders() {
            return Collections.emptyMap();
        }

        @Override
        public void close() {
            closed.set(true);
        }
    }

    private static final class BlockingCloseDataSource implements DataSource {
        final CountDownLatch reading = new CountDownLatch(1);
        final CountDownLatch finishRead = new CountDownLatch(1);
        final CountDownLatch closing = new CountDownLatch(1);
        final CountDownLatch finishClose = new CountDownLatch(1);

        @Override
        public void addTransferListener(TransferListener transferListener) {
        }

        @Override
        public long open(DataSpec dataSpec) {
            return dataSpec.length;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            reading.countDown();
            while (finishRead.getCount() > 0) {
                Thread.yield();
            }
            buffer[offset] = 1;
            return 1;
        }

        @Override
        public Uri getUri() {
            return Uri.parse("https://example.com/audio.mp3");
        }

        @Override
        public Map<String, List<String>> getResponseHeaders() {
            return Collections.emptyMap();
        }

        @Override
        public void close() {
            closing.countDown();
            while (finishClose.getCount() > 0) {
                Thread.yield();
            }
        }
    }
}
