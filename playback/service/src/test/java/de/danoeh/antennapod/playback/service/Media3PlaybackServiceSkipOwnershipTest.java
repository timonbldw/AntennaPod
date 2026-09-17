package de.danoeh.antennapod.playback.service;

import android.net.Uri;
import androidx.media3.common.MediaItem;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.playback.service.skip.SkipAnalysisSnapshot;
import de.danoeh.antennapod.playback.service.skip.SkipAnalysisStatus;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Collections;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@RunWith(RobolectricTestRunner.class)
public class Media3PlaybackServiceSkipOwnershipTest {
    @Test
    public void streamingSourceRequiresMatchingCurrentMediaId() {
        FeedMedia media = mock(FeedMedia.class);
        when(media.getId()).thenReturn(42L);

        assertTrue(Media3PlaybackService.matchesMediaItem(media,
                new MediaItem.Builder().setMediaId("42").build()));
        assertFalse(Media3PlaybackService.matchesMediaItem(media,
                new MediaItem.Builder().setMediaId("43").build()));
    }

    @Test
    public void invalidationWithoutIdentityIsAcceptedButOrdinarySnapshotIsNot() {
        Uri source = Uri.parse("skip-cache://session");
        SkipAnalysisSnapshot invalidation = snapshot(SkipAnalysisStatus.NOT_ANALYZED, null);
        SkipAnalysisSnapshot staleReady = snapshot(SkipAnalysisStatus.WINDOW_READY, null);

        assertTrue(Media3PlaybackService.matchesSnapshotSource(source, invalidation));
        assertFalse(Media3PlaybackService.matchesSnapshotSource(source, staleReady));
        assertTrue(Media3PlaybackService.matchesSnapshotSource(source,
                snapshot(SkipAnalysisStatus.WINDOW_READY, source.toString())));
    }

    private static SkipAnalysisSnapshot snapshot(SkipAnalysisStatus status, String identity) {
        return new SkipAnalysisSnapshot("feed", "episode", status, 1, identity, 60_000,
                Collections.emptyList(), Collections.emptyList(), null, 1);
    }
}
