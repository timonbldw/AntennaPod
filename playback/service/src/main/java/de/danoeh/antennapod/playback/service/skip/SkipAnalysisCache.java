package de.danoeh.antennapod.playback.service.skip;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class SkipAnalysisCache {
    private static final int VERSION = 4;
    private final File directory;

    SkipAnalysisCache(File cacheDirectory) {
        directory = new File(cacheDirectory, "skip-analysis");
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IllegalStateException("Cannot create skip analysis directory");
        }
    }

    synchronized Entry read(String feedId, String episodeId) throws IOException {
        File file = fileFor(feedId, episodeId);
        if (!file.exists()) {
            return null;
        }
        try {
            JSONObject object = new JSONObject(readFile(file));
            if (object.optInt("version", 0) != VERSION) {
                return null;
            }
            long durationMs = object.getLong("durationMs");
            if (durationMs <= 0) {
                return null;
            }
            List<SkipCoverage> coverage = new ArrayList<>();
            JSONArray coverageArray = object.optJSONArray("coverage");
            if (coverageArray != null) {
                for (int index = 0; index < coverageArray.length(); index++) {
                    JSONArray range = coverageArray.getJSONArray(index);
                    if (range.getLong(1) > durationMs) {
                        return null;
                    }
                    coverage.add(new SkipCoverage(range.getLong(0), range.getLong(1)));
                }
            }
            List<SkipMarkerHit> hits = new ArrayList<>();
            JSONArray hitArray = object.optJSONArray("hits");
            if (hitArray != null) {
                for (int index = 0; index < hitArray.length(); index++) {
                    JSONObject hit = hitArray.getJSONObject(index);
                    if (hit.getLong("timeMs") >= durationMs) {
                        return null;
                    }
                    hits.add(new SkipMarkerHit(hit.getString("ruleId"), hit.getString("sampleId"),
                            SkipMarker.valueOf(hit.getString("marker")), hit.getLong("timeMs"),
                            (float) hit.getDouble("score")));
                }
            }
            return new Entry(object.optString("sourceIdentity", null), object.optLong("rulesRevision", 0),
                    object.optLong("durationMs", 0), coverage, hits);
        } catch (JSONException | RuntimeException error) {
            throw new IOException("Cannot read skip analysis cache", error);
        }
    }

    synchronized void write(String feedId, String episodeId, Entry entry) throws IOException {
        JSONObject object = new JSONObject();
        JSONArray coverage = new JSONArray();
        JSONArray hits = new JSONArray();
        try {
            object.put("version", VERSION);
            object.put("sourceIdentity", entry.sourceIdentity);
            object.put("rulesRevision", entry.rulesRevision);
            object.put("durationMs", entry.durationMs);
            for (SkipCoverage range : entry.coverage) {
                JSONArray values = new JSONArray();
                values.put(range.startMs);
                values.put(range.endMs);
                coverage.put(values);
            }
            for (SkipMarkerHit hit : entry.hits) {
                JSONObject value = new JSONObject();
                value.put("ruleId", hit.ruleId);
                value.put("sampleId", hit.sampleId);
                value.put("marker", hit.marker.name());
                value.put("timeMs", hit.timeMs);
                value.put("score", hit.score);
                hits.put(value);
            }
            object.put("coverage", coverage);
            object.put("hits", hits);
        } catch (JSONException error) {
            throw new IOException("Cannot write skip analysis cache", error);
        }
        atomicWrite(fileFor(feedId, episodeId), object.toString());
    }

    private File fileFor(String feedId, String episodeId) {
        return new File(directory, SkipStorageKey.digest(feedId + "\n" + episodeId) + ".json");
    }

    private static String readFile(File file) throws IOException {
        if (file.length() > 16_777_216) {
            throw new IOException("Skip analysis cache exceeds size limit");
        }
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] bytes = new byte[(int) file.length()];
            int offset = 0;
            while (offset < bytes.length) {
                int count = input.read(bytes, offset, bytes.length - offset);
                if (count < 0) {
                    break;
                }
                offset += count;
            }
            return new String(bytes, 0, offset, StandardCharsets.UTF_8);
        }
    }

    private static void atomicWrite(File file, String content) throws IOException {
        File temporary = new File(file.getPath() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            output.write(content.getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
        if (!temporary.renameTo(file)) {
            if (!temporary.delete()) {
                throw new IOException("Cannot replace skip analysis cache");
            }
            throw new IOException("Cannot replace skip analysis cache");
        }
    }

    static final class Entry {
        final String sourceIdentity;
        final long rulesRevision;
        final long durationMs;
        final List<SkipCoverage> coverage;
        final List<SkipMarkerHit> hits;

        Entry(String sourceIdentity, long rulesRevision, long durationMs,
              List<SkipCoverage> coverage, List<SkipMarkerHit> hits) {
            this.sourceIdentity = sourceIdentity;
            this.rulesRevision = rulesRevision;
            this.durationMs = durationMs;
            this.coverage = Collections.unmodifiableList(new ArrayList<>(coverage));
            this.hits = Collections.unmodifiableList(new ArrayList<>(hits));
        }
    }
}
