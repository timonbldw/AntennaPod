package de.danoeh.antennapod.playback.service.skip;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@RunWith(RobolectricTestRunner.class)
public class SkipStorageTest {
    @Test
    public void fingerprintsRoundTripWithoutDependingOnSourceEpisode() throws IOException {
        Context context = ApplicationProvider.getApplicationContext();
        SkipRuleStore store = new SkipRuleStore(context);
        String feedId = UUID.randomUUID().toString();
        int[] hashes = new int[61];
        Arrays.fill(hashes, 0x456789ab);
        AudioFingerprint fingerprint = new AudioFingerprint(8_000, 64, 32, hashes);
        SkipSample sample = new SkipSample("sample", SkipMarker.START, 2_000, 0, 42_000, fingerprint);
        SkipRule rule = new SkipRule("rule", "Intro", true, SkipRule.Type.FIXED, 0, 0,
                SkipRule.MissingEndBehavior.UNTOUCHED, 0, 5_000, 0, 0,
                Collections.singletonList(sample)).withUseStartAsEnd(true);
        store.write(feedId, new SkipRuleStore.RuleSet(7, Collections.singletonList(rule)));
        SkipRuleStore.RuleSet restored = new SkipRuleStore(context).read(feedId);
        assertEquals(7, restored.revision);
        assertEquals(fingerprint, restored.rules.get(0).samples.get(0).fingerprint);
        assertEquals(42_000, restored.rules.get(0).samples.get(0).sourcePositionMs);
        assertEquals("Intro", restored.rules.get(0).name);
        assertTrue(restored.rules.get(0).useStartAsEnd);
    }

    @Test
    public void malformedSavedRuleBecomesCheckedError() throws IOException {
        Context context = ApplicationProvider.getApplicationContext();
        SkipRuleStore store = new SkipRuleStore(context);
        String feedId = UUID.randomUUID().toString();
        File file = new File(new File(context.getFilesDir(), "skip-rules"), SkipStorageKey.digest(feedId) + ".json");
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write("{\"version\":2,\"rules\":[{\"type\":\"INVALID\"}]}".getBytes(StandardCharsets.UTF_8));
        }
        try {
            store.read(feedId);
            fail("Malformed rule was accepted");
        } catch (IOException error) {
            assertTrue(error.getMessage().contains("Cannot read"));
        }
    }

    @Test
    public void missingSharedMarkerFieldDefaultsToFalse() throws IOException {
        Context context = ApplicationProvider.getApplicationContext();
        SkipRuleStore store = new SkipRuleStore(context);
        String feedId = UUID.randomUUID().toString();
        int[] hashes = new int[61];
        Arrays.fill(hashes, 0x456789ab);
        StringBuilder hashesJson = new StringBuilder();
        for (int index = 0; index < hashes.length; index++) {
            if (index > 0) {
                hashesJson.append(',');
            }
            hashesJson.append(hashes[index]);
        }
        String sample = "{\"id\":\"%s\",\"marker\":\"%s\",\"durationMs\":2000,"
                + "\"sampleRate\":8000,\"frameMs\":64,\"hopMs\":32,\"hashes\":[" + hashesJson + "]}";
        String json = "{\"version\":2,\"rules\":[{\"id\":\"rule\",\"name\":\"Ads\","
                + "\"type\":\"BETWEEN\",\"samples\":["
                + String.format(sample, "start", "START") + ","
                + String.format(sample, "end", "END") + "]}]}";
        File file = new File(new File(context.getFilesDir(), "skip-rules"), SkipStorageKey.digest(feedId) + ".json");
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(json.getBytes(StandardCharsets.UTF_8));
        }
        assertTrue(!store.read(feedId).rules.get(0).useStartAsEnd);
    }

    @Test
    public void cachePreservesSourceRevisionCoverageAndMarkerSample() throws IOException {
        Context context = ApplicationProvider.getApplicationContext();
        SkipAnalysisCache cache = new SkipAnalysisCache(context.getFilesDir());
        String feedId = UUID.randomUUID().toString();
        SkipAnalysisCache.Entry entry = new SkipAnalysisCache.Entry("source:size:mtime", 12, 100_000,
                Arrays.asList(new SkipCoverage(0, 30_000), new SkipCoverage(60_000, 90_000)),
                Collections.singletonList(new SkipMarkerHit("rule", "alternate", SkipMarker.END, 65_000, 0.91f)));
        cache.write(feedId, "episode", entry);
        SkipAnalysisCache.Entry restored = cache.read(feedId, "episode");
        assertEquals(entry.sourceIdentity, restored.sourceIdentity);
        assertEquals(12, restored.rulesRevision);
        assertEquals(2, restored.coverage.size());
        assertEquals(60_000, restored.coverage.get(1).startMs);
        assertEquals("alternate", restored.hits.get(0).sampleId);
        assertEquals(65_000, restored.hits.get(0).timeMs);
    }

    @Test
    public void cacheCanBeDeletedForEpisode() throws IOException {
        Context context = ApplicationProvider.getApplicationContext();
        SkipAnalysisCache cache = new SkipAnalysisCache(context.getFilesDir());
        String feedId = UUID.randomUUID().toString();
        cache.write(feedId, "episode", new SkipAnalysisCache.Entry("source", 1, 100_000,
                Collections.singletonList(new SkipCoverage(0, 30_000)), Collections.emptyList()));
        cache.delete(feedId, "episode");
        assertNull(cache.read(feedId, "episode"));
    }
}
