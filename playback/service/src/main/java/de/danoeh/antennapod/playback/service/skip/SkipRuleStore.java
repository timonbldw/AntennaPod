package de.danoeh.antennapod.playback.service.skip;

import android.content.Context;

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

final class SkipRuleStore {
    private static final int VERSION = 2;
    private final File directory;

    SkipRuleStore(Context context) {
        directory = new File(context.getFilesDir(), "skip-rules");
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IllegalStateException("Cannot create skip rule directory");
        }
    }

    synchronized RuleSet read(String feedId) throws IOException {
        File file = fileFor(feedId);
        if (!file.exists()) {
            return new RuleSet(0, Collections.emptyList());
        }
        try {
            String json = readFile(file);
            JSONObject object = new JSONObject(json);
            if (object.optInt("version", 0) != VERSION) {
                throw new IOException("Unsupported skip rule fingerprint version; recreate audio samples");
            }
            JSONArray array = object.optJSONArray("rules");
            List<SkipRule> rules = new ArrayList<>();
            if (array != null) {
                for (int index = 0; index < array.length(); index++) {
                    SkipRule rule = readRule(array.getJSONObject(index));
                    rule.validate();
                    rules.add(rule);
                }
            }
            return new RuleSet(object.optLong("revision", 0), rules);
        } catch (JSONException | RuntimeException error) {
            throw new IOException("Cannot read skip rules", error);
        }
    }

    synchronized void write(String feedId, RuleSet ruleSet) throws IOException {
        JSONObject object = new JSONObject();
        JSONArray array = new JSONArray();
        try {
            object.put("version", VERSION);
            object.put("revision", ruleSet.revision);
            for (SkipRule rule : ruleSet.rules) {
                rule.validate();
                array.put(writeRule(rule));
            }
            object.put("rules", array);
        } catch (JSONException error) {
            throw new IOException("Cannot write skip rules", error);
        }
        atomicWrite(fileFor(feedId), object.toString());
    }

    private static JSONObject writeRule(SkipRule rule) throws JSONException {
        JSONObject object = new JSONObject();
        object.put("id", rule.id);
        object.put("name", rule.name);
        object.put("enabled", rule.enabled);
        object.put("type", rule.type.name());
        object.put("minDurationMs", rule.minDurationMs);
        object.put("maxDurationMs", rule.maxDurationMs);
        object.put("missingEndBehavior", rule.missingEndBehavior.name());
        object.put("missingEndDurationMs", rule.missingEndDurationMs);
        object.put("fixedDurationMs", rule.fixedDurationMs);
        object.put("firstRegionMs", rule.firstRegionMs);
        object.put("lastRegionMs", rule.lastRegionMs);
        JSONArray samples = new JSONArray();
        for (SkipSample sample : rule.samples) {
            JSONObject sampleObject = new JSONObject();
            sampleObject.put("id", sample.id);
            sampleObject.put("marker", sample.marker.name());
            sampleObject.put("durationMs", sample.durationMs);
            sampleObject.put("markerOffsetMs", sample.markerOffsetMs);
            sampleObject.put("sampleRate", sample.fingerprint.sampleRate);
            sampleObject.put("frameMs", sample.fingerprint.frameMs);
            sampleObject.put("hopMs", sample.fingerprint.hopMs);
            JSONArray hashes = new JSONArray();
            for (int hash : sample.fingerprint.hashes) {
                hashes.put(hash);
            }
            sampleObject.put("hashes", hashes);
            samples.put(sampleObject);
        }
        object.put("samples", samples);
        return object;
    }

    private static SkipRule readRule(JSONObject object) throws JSONException {
        JSONArray samples = object.optJSONArray("samples");
        List<SkipSample> parsedSamples = new ArrayList<>();
        if (samples != null) {
            for (int index = 0; index < samples.length(); index++) {
                JSONObject sample = samples.getJSONObject(index);
                JSONArray hashes = sample.getJSONArray("hashes");
                int[] parsedHashes = new int[hashes.length()];
                for (int hashIndex = 0; hashIndex < hashes.length(); hashIndex++) {
                    parsedHashes[hashIndex] = hashes.getInt(hashIndex);
                }
                parsedSamples.add(new SkipSample(sample.getString("id"),
                        SkipMarker.valueOf(sample.getString("marker")), sample.getLong("durationMs"),
                        sample.optLong("markerOffsetMs", 0), new AudioFingerprint(
                        sample.getInt("sampleRate"), sample.getInt("frameMs"),
                        sample.getInt("hopMs"), parsedHashes)));
            }
        }
        return new SkipRule(object.getString("id"), object.getString("name"),
                object.optBoolean("enabled", true), SkipRule.Type.valueOf(object.getString("type")),
                object.optLong("minDurationMs", 0), object.optLong("maxDurationMs", 0),
                SkipRule.MissingEndBehavior.valueOf(object.optString("missingEndBehavior",
                        SkipRule.MissingEndBehavior.UNTOUCHED.name())),
                object.optLong("missingEndDurationMs", 0), object.optLong("fixedDurationMs",
                        object.optLong("missingEndDurationMs", 0)), object.optLong("firstRegionMs", 0),
                object.optLong("lastRegionMs", 0), parsedSamples);
    }

    private File fileFor(String feedId) {
        return new File(directory, SkipStorageKey.digest(feedId) + ".json");
    }

    private static String readFile(File file) throws IOException {
        if (file.length() > 16_777_216) {
            throw new IOException("Skip rules exceed size limit");
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
                throw new IOException("Cannot replace skip rule file");
            }
            throw new IOException("Cannot replace skip rule file");
        }
    }

    static final class RuleSet {
        final long revision;
        final List<SkipRule> rules;

        RuleSet(long revision, List<SkipRule> rules) {
            this.revision = revision;
            this.rules = Collections.unmodifiableList(new ArrayList<>(rules));
        }
    }
}
