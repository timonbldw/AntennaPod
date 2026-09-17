package de.danoeh.antennapod.playback.service.skip;

import android.content.Context;
import android.net.Uri;
import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.cache.CacheSpan;
import androidx.media3.datasource.cache.ContentMetadataMutations;
import androidx.media3.datasource.cache.NoOpCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;
import androidx.test.core.app.ApplicationProvider;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.io.FileOutputStream;
import java.util.UUID;

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
    public void readAcrossMissingFragmentThrowsInsteadOfReturningPartialData() throws Exception {
        SkipStreamingSource.Registration registration = SkipStreamingSource.register(
                cache, Uri.parse("https://example.com/audio.mp3"));
        cache.applyContentMetadataMutations(registration.cacheKey,
                ContentMetadataMutations.setContentLength(new ContentMetadataMutations(), 196_608));
        writeCache(registration.cacheKey, new byte[65_536]);
        writeCache(registration.cacheKey, 131_072, new byte[65_536]);
        try (SkipStreamingSource.CachedMediaDataSource source = SkipStreamingSource.open(registration.uri)) {
            try {
                source.readAt(65_535, new byte[3], 0, 3);
                fail("Expected unavailable intervening fragment");
            } catch (SkipStreamingSource.UnavailableException expected) {
                assertEquals("Requested audio bytes are not cached", expected.getMessage());
            }
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
            try {
                source.readAt(4, result, 0, result.length);
                fail("Expected unavailable cache hole");
            } catch (SkipStreamingSource.UnavailableException expected) {
                assertEquals("Requested audio bytes are not cached", expected.getMessage());
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
            try {
                source.readAt(128, new byte[1], 0, 1);
                fail("Expected unavailable optional probe");
            } catch (SkipStreamingSource.UnavailableException expected) {
                source.acceptOptionalMp3TailProbe("audio/mpeg");
            }
            byte[] result = new byte[4];
            assertEquals(4, source.readAt(0, result, 0, result.length));
            source.throwIfUnavailable();
            assertArrayEquals(cached, result);
            try {
                source.readAt(4, new byte[1], 0, 1);
                fail("Expected unavailable required read");
            } catch (SkipStreamingSource.UnavailableException expected) {
                try {
                    source.throwIfUnavailable();
                    fail("Expected required read failure to remain visible");
                } catch (SkipStreamingSource.UnavailableException unavailable) {
                    assertEquals("Requested audio bytes are not cached", unavailable.getMessage());
                }
            }
        }
    }

    @Test
    public void prefixGapCannotBeForgivenAsMp3TailProbe() throws Exception {
        SkipStreamingSource.Registration registration = SkipStreamingSource.register(
                cache, Uri.parse("https://example.com/audio.mp3"));
        cache.applyContentMetadataMutations(registration.cacheKey,
                ContentMetadataMutations.setContentLength(new ContentMetadataMutations(), 256));

        try (SkipStreamingSource.CachedMediaDataSource source = SkipStreamingSource.open(registration.uri)) {
            try {
                source.readAt(0, new byte[1], 0, 1);
                fail("Expected unavailable prefix");
            } catch (SkipStreamingSource.UnavailableException expected) {
                try {
                    source.readAt(128, new byte[1], 0, 1);
                    fail("Expected unavailable tail probe");
                } catch (SkipStreamingSource.UnavailableException tailProbe) {
                    assertEquals("Requested audio bytes are not cached", tailProbe.getMessage());
                }
                try {
                    source.acceptOptionalMp3TailProbe("audio/mpeg");
                    fail("Expected prefix miss to remain visible");
                } catch (SkipStreamingSource.UnavailableException unavailable) {
                    assertEquals("Requested audio bytes are not cached", unavailable.getMessage());
                }
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
            try {
                source.readAt(128, new byte[1], 0, 1);
                fail("Expected unavailable tail");
            } catch (SkipStreamingSource.UnavailableException expected) {
                try {
                    source.acceptOptionalMp3TailProbe("audio/aac");
                    fail("Expected non-MP3 miss to remain visible");
                } catch (SkipStreamingSource.UnavailableException unavailable) {
                    assertEquals("Requested audio bytes are not cached", unavailable.getMessage());
                }
            }
        }
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
}
