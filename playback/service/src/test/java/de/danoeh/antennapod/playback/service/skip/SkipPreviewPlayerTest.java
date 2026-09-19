package de.danoeh.antennapod.playback.service.skip;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.ByteArrayDataSource;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DefaultDataSource;
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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@OptIn(markerClass = UnstableApi.class)
@RunWith(RobolectricTestRunner.class)
public class SkipPreviewPlayerTest {
    private Context context;
    private SimpleCache cache;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        File directory = new File(context.getCacheDir(), "skip-preview-" + UUID.randomUUID());
        cache = new SimpleCache(directory, new NoOpCacheEvictor(), new StandaloneDatabaseProvider(context));
    }

    @After
    public void tearDown() {
        SkipStreamingSource.release(cache);
        cache.release();
    }

    @Test
    public void localUriUsesDefaultDataSource() {
        DataSource source = SkipPreviewPlayer.createDataSourceFactory(
                context, Uri.fromFile(new File(context.getCacheDir(), "sample.wav"))).createDataSource();

        assertTrue(source instanceof DefaultDataSource);
    }

    @Test
    public void streamingUriReadsThroughRegisteredCacheSource() throws Exception {
        byte[] audio = new byte[] {10, 11, 12, 13};
        AtomicInteger upstreamSources = new AtomicInteger();
        SkipStreamingSource.Registration registration = SkipStreamingSource.register(cache,
                Uri.parse("https://example.com/audio.mp3"), () -> {
                    upstreamSources.incrementAndGet();
                    return new ByteArrayDataSource(audio);
                });
        cache.applyContentMetadataMutations(registration.cacheKey,
                ContentMetadataMutations.setContentLength(new ContentMetadataMutations(), audio.length));

        DataSource source = SkipPreviewPlayer.createDataSourceFactory(context, registration.uri).createDataSource();
        assertFalse(source instanceof DefaultDataSource);
        assertEquals(3, source.open(new DataSpec.Builder()
                .setUri(registration.uri).setPosition(1).setLength(3).build()));
        byte[] result = new byte[3];
        assertEquals(3, source.read(result, 0, result.length));
        assertArrayEquals(new byte[] {11, 12, 13}, result);
        assertEquals(C.RESULT_END_OF_INPUT, source.read(result, 0, result.length));
        source.close();
        source.close();
        assertEquals(1, upstreamSources.get());
        assertTrue(cache.isCached(registration.cacheKey, 1, 3));
    }

    @Test
    public void sourcePositionIsRelativeToLocalClipStart() {
        assertEquals(2_500, SkipPreviewPlayer.toSourcePosition(12_500, 10_000));
        assertEquals(0, SkipPreviewPlayer.toSourcePosition(9_000, 10_000));
    }
}
