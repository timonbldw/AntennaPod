package de.danoeh.antennapod.playback.service.skip;

import android.content.Context;
import android.net.Uri;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;

public final class SkipManager {
    private static final long WINDOW_MS = 30_000;
    private static volatile SkipManager instance;

    private final Context context;
    private final SkipRuleStore ruleStore;
    private final SkipAnalysisCache analysisCache;
    private final SkipPriorityExecutor executor = new SkipPriorityExecutor();
    private final Map<String, SkipAnalysisSnapshot> snapshots = new HashMap<>();
    private final Map<String, List<SkipAnalysisCallback>> observers = new HashMap<>();
    private final Map<String, AnalysisJob> jobs = new HashMap<>();

    private SkipManager(Context context) {
        this.context = context.getApplicationContext();
        ruleStore = new SkipRuleStore(this.context);
        analysisCache = new SkipAnalysisCache(this.context.getCacheDir());
    }

    public static SkipManager getInstance(Context context) {
        if (instance == null) {
            synchronized (SkipManager.class) {
                if (instance == null) {
                    instance = new SkipManager(context);
                }
            }
        }
        return instance;
    }

    public synchronized List<SkipRule> getRules(String feedId) {
        try {
            return ruleStore.read(requireId(feedId)).rules;
        } catch (IOException error) {
            return Collections.emptyList();
        }
    }

    public synchronized List<SkipRule> getRulesOrThrow(String feedId) throws IOException {
        return ruleStore.read(requireId(feedId)).rules;
    }

    public SkipRule createRule(String feedId, String name, SkipRule.Type type) throws IOException {
        requireId(feedId);
        return new SkipRule(UUID.randomUUID().toString(), name, true, type, 0, 0,
                SkipRule.MissingEndBehavior.UNTOUCHED, 0, 0, 0, 0, Collections.emptyList());
    }

    public synchronized void saveRule(String feedId, SkipRule rule) throws IOException {
        String validFeedId = requireId(feedId);
        if (rule == null) {
            throw new IllegalArgumentException("Rule is required");
        }
        rule.validate();
        SkipRuleStore.RuleSet current = ruleStore.read(validFeedId);
        List<SkipRule> rules = new ArrayList<>();
        boolean replaced = false;
        for (SkipRule existing : current.rules) {
            if (existing.id.equals(rule.id)) {
                rules.add(rule);
                replaced = true;
            } else {
                rules.add(existing);
            }
        }
        if (!replaced) {
            rules.add(rule);
        }
        ruleStore.write(validFeedId, new SkipRuleStore.RuleSet(current.revision + 1, rules));
        invalidateFeed(validFeedId);
    }

    public synchronized void deleteRule(String feedId, String ruleId) throws IOException {
        String validFeedId = requireId(feedId);
        SkipRuleStore.RuleSet current = ruleStore.read(validFeedId);
        List<SkipRule> rules = new ArrayList<>();
        for (SkipRule rule : current.rules) {
            if (!rule.id.equals(ruleId)) {
                rules.add(rule);
            }
        }
        ruleStore.write(validFeedId, new SkipRuleStore.RuleSet(current.revision + 1, rules));
        invalidateFeed(validFeedId);
    }

    public synchronized SkipSubscription observe(String feedId, String episodeId, SkipAnalysisCallback callback) {
        String key = key(requireId(feedId), requireId(episodeId));
        if (callback == null) {
            throw new IllegalArgumentException("Callback is required");
        }
        observers.computeIfAbsent(key, ignored -> new ArrayList<>()).add(callback);
        SkipAnalysisSnapshot snapshot = snapshots.get(key);
        if (snapshot != null) {
            notifyCallback(callback, snapshot);
        }
        return () -> {
            synchronized (SkipManager.this) {
                List<SkipAnalysisCallback> callbacks = observers.get(key);
                if (callbacks != null) {
                    callbacks.remove(callback);
                    if (callbacks.isEmpty()) {
                        observers.remove(key);
                    }
                }
            }
        };
    }

    public synchronized SkipAnalysisSnapshot getSnapshot(String feedId, String episodeId) {
        SkipAnalysisSnapshot snapshot = snapshots.get(key(requireId(feedId), requireId(episodeId)));
        return snapshot == null ? SkipAnalysisSnapshot.notAnalyzed(feedId, episodeId) : snapshot;
    }

    public synchronized SkipTask analyze(String feedId, String episodeId, Uri audioUri, long durationMs,
                                          long positionMs, SkipPriority priority) {
        String validFeedId = requireId(feedId);
        String validEpisodeId = requireId(episodeId);
        validateAnalysis(audioUri, durationMs, positionMs);
        String key = key(validFeedId, validEpisodeId);
        AnalysisJob existing = jobs.get(key);
        if (existing != null && existing.noEnabledRules && !existing.task.isCancelled()
                && existing.audioUri.equals(audioUri) && existing.requestedDurationMs == durationMs) {
            return existing.task;
        }
        if (existing != null && !existing.task.isDone() && reusableSource(audioUri)
                && existing.audioUri.equals(audioUri)
                && existing.requestedDurationMs == durationMs) {
            existing.positionMs = positionMs;
            existing.priority = priority == null ? SkipPriority.BACKGROUND : priority;
            executor.reprioritize(existing.task, existing.priority);
            return existing.task;
        }
        if (existing != null) {
            existing.task.cancel();
        }
        AnalysisJob job = new AnalysisJob(validFeedId, validEpisodeId, audioUri, durationMs,
                positionMs, priority, null, null);
        jobs.put(key, job);
        snapshots.put(key, new SkipAnalysisSnapshot(feedId, episodeId, SkipAnalysisStatus.ANALYZING,
                0, null, durationMs, Collections.emptyList(), Collections.emptyList(), null,
                System.currentTimeMillis()));
        job.enqueue();
        return job.task;
    }

    public synchronized boolean reprioritize(String feedId, String episodeId, long positionMs,
                                               SkipPriority priority) {
        if (positionMs < 0) {
            throw new IllegalArgumentException("Playback position must not be negative");
        }
        AnalysisJob job = jobs.get(key(requireId(feedId), requireId(episodeId)));
        if (job == null || job.task.isDone()) {
            return false;
        }
        job.positionMs = positionMs;
        job.priority = priority == null ? SkipPriority.BACKGROUND : priority;
        executor.reprioritize(job.task, job.priority);
        return true;
    }

    public SkipTask extractSample(String feedId, String episodeId, Uri audioUri, SkipMarker marker,
                                  long startMs, long endMs, long markerOffsetMs,
                                  SkipSampleCallback callback) {
        requireId(feedId);
        requireId(episodeId);
        validateRange(startMs, endMs);
        if (audioUri == null || marker == null || callback == null
                || markerOffsetMs < 0 || markerOffsetMs > endMs - startMs) {
            throw new IllegalArgumentException("Invalid sample request");
        }
        return executor.execute(SkipPriority.CURRENT_PLAYBACK, () -> {
            try {
                SkipAudioDecoder.DecodedAudio decoded = SkipAudioDecoder.decode(context, audioUri, startMs, endMs);
                if (!decoded.complete || decoded.startMs > startMs
                        || decoded.durationMs < endMs - startMs - 1) {
                    throw new IOException("The complete sample range could not be decoded");
                }
                if (!SkipFingerprint.isUsable(decoded.samples)) {
                    throw new IllegalArgumentException("Sample is silent or too weak");
                }
                AudioFingerprint fingerprint = SkipFingerprint.fromPcm(decoded.samples, SkipFingerprint.SAMPLE_RATE);
                if (!SkipFingerprint.isUsable(fingerprint)) {
                    throw new IllegalArgumentException("Sample is silent or too weak");
                }
                if (!Thread.currentThread().isInterrupted()) {
                    callback.onSuccess(new SkipSample(UUID.randomUUID().toString(), marker,
                            endMs - startMs, markerOffsetMs, fingerprint));
                }
            } catch (Exception error) {
                if (!Thread.currentThread().isInterrupted() && !(error instanceof InterruptedException)) {
                    callback.onError(error);
                }
            }
        });
    }

    public SkipTask extractSample(Uri audioUri, long startMs, long endMs, SkipMarker marker,
                                  long markerOffsetMs, SkipSampleCallback callback) {
        return extractSample("unsaved", "sample", audioUri, marker, startMs, endMs, markerOffsetMs, callback);
    }

    public SkipTask extractWaveform(Uri audioUri, long startMs, long endMs, int pointCount,
                                    SkipWaveformCallback callback) {
        validateRange(startMs, endMs);
        if (audioUri == null || pointCount <= 0 || pointCount > 4_096 || callback == null) {
            throw new IllegalArgumentException("Invalid waveform request");
        }
        return executor.execute(SkipPriority.CURRENT_PLAYBACK, () -> {
            try {
                SkipAudioDecoder.DecodedAudio decoded = SkipAudioDecoder.decode(context, audioUri, startMs, endMs);
                float[] minimum = new float[pointCount];
                float[] maximum = new float[pointCount];
                for (int point = 0; point < pointCount; point++) {
                    int from = (int) ((long) point * decoded.samples.length / pointCount);
                    int to = (int) ((long) (point + 1) * decoded.samples.length / pointCount);
                    float min = 0;
                    float max = 0;
                    for (int index = from; index < to; index++) {
                        min = Math.min(min, decoded.samples[index]);
                        max = Math.max(max, decoded.samples[index]);
                    }
                    minimum[point] = min;
                    maximum[point] = max;
                }
                if (!Thread.currentThread().isInterrupted()) {
                    callback.onSuccess(new SkipWaveform(decoded.startMs, decoded.startMs + decoded.durationMs,
                            minimum, maximum));
                }
            } catch (Exception error) {
                if (!Thread.currentThread().isInterrupted() && !(error instanceof InterruptedException)) {
                    callback.onError(error);
                }
            }
        });
    }

    public SkipTask testRule(SkipRule rule, Uri audioUri, long durationMs, SkipAnalysisCallback callback) {
        validateAnalysis(audioUri, durationMs, 0);
        if (rule == null || callback == null) {
            throw new IllegalArgumentException("Invalid test request");
        }
        rule.validate();
        AnalysisJob job = new AnalysisJob("unsaved", "unsaved", audioUri, durationMs, 0,
                SkipPriority.HIGH, Collections.singletonList(rule.withEnabled(true)), callback);
        job.enqueue();
        return job.task;
    }

    private final class AnalysisJob implements Runnable {
        private final String feedId;
        private final String episodeId;
        private final String key;
        private final Uri audioUri;
        private final long requestedDurationMs;
        private final SkipAnalysisCallback callback;
        private final SkipTask task = new SkipTask();
        private volatile long positionMs;
        private volatile SkipPriority priority;
        private List<SkipRule> rules;
        private long revision;
        private long durationMs;
        private String identity;
        private boolean noEnabledRules;
        private List<SkipCoverage> coverage = new ArrayList<>();
        private List<SkipMarkerHit> hits = new ArrayList<>();

        AnalysisJob(String feedId, String episodeId, Uri audioUri, long durationMs, long positionMs,
                    SkipPriority priority, List<SkipRule> rules, SkipAnalysisCallback callback) {
            this.feedId = feedId;
            this.episodeId = episodeId;
            key = key(feedId, episodeId);
            this.audioUri = audioUri;
            this.durationMs = durationMs;
            requestedDurationMs = durationMs;
            this.positionMs = positionMs;
            this.priority = priority == null ? SkipPriority.BACKGROUND : priority;
            this.rules = rules;
            this.callback = callback;
        }

        void enqueue() {
            executor.execute(priority, task, this);
        }

        @Override
        public synchronized void run() {
            try {
                checkCancelled();
                if (identity == null) {
                    initialize();
                    emit(SkipAnalysisStatus.ANALYZING, null);
                }
                checkCancelled();
                if (reusableSource(audioUri) && !identity.equals(sourceIdentity(audioUri))) {
                    throw new IOException("Audio source changed during analysis");
                }
                if (!rules.isEmpty() && !isFullyCovered(coverage, durationMs)) {
                    long startMs = chooseWindowStart(positionMs, durationMs, coverage);
                    startMs -= startMs % SkipFingerprint.HOP_MS;
                    analyzeWindow(startMs, Math.min(durationMs, startMs + WINDOW_MS));
                    checkCancelled();
                    if (callback == null && reusableSource(audioUri)) {
                        try {
                            analysisCache.write(feedId, episodeId,
                                    new SkipAnalysisCache.Entry(identity, revision, durationMs, coverage, hits));
                        } catch (IOException ignored) {
                        }
                    }
                }
                boolean done = rules.isEmpty() || isFullyCovered(coverage, durationMs);
                SkipAnalysisStatus status = done ? (resolveAll(rules, hits, durationMs, coverage).isEmpty()
                        ? SkipAnalysisStatus.NO_MATCHES : SkipAnalysisStatus.READY) : SkipAnalysisStatus.ANALYZING;
                emit(status, null);
                if (!done && !task.isCancellationRequested()) {
                    enqueue();
                }
            } catch (InterruptedException | CancellationException ignored) {
                task.cancel();
            } catch (Exception error) {
                emit(SkipAnalysisStatus.ERROR, error.toString());
            }
        }

        private void initialize() throws IOException, InterruptedException {
            identity = sourceIdentity(audioUri);
            synchronized (SkipManager.this) {
                checkCancelled();
                if (rules == null) {
                    SkipRuleStore.RuleSet saved = ruleStore.read(feedId);
                    revision = saved.revision;
                    rules = new ArrayList<>();
                    for (SkipRule rule : saved.rules) {
                        rule.validate();
                        if (rule.enabled) {
                            rules.add(rule);
                        }
                    }
                }
            }
            if (callback == null && reusableSource(audioUri)) {
                try {
                    SkipAnalysisCache.Entry cached = analysisCache.read(feedId, episodeId);
                    if (cached != null && identity.equals(cached.sourceIdentity)
                            && cached.rulesRevision == revision && cached.durationMs == durationMs) {
                        coverage = new ArrayList<>(cached.coverage);
                        hits = new ArrayList<>(cached.hits);
                    }
                } catch (IOException ignored) {
                }
            }
        }

        private void analyzeWindow(long startMs, long endMs) throws IOException, InterruptedException {
            long overlapMs = 0;
            for (SkipRule rule : rules) {
                for (SkipSample sample : rule.samples) {
                    overlapMs = Math.max(overlapMs, sample.durationMs);
                }
            }
            long decodeStart = Math.max(0, startMs - overlapMs);
            decodeStart -= decodeStart % SkipFingerprint.HOP_MS;
            long decodeEnd = Math.min(durationMs, endMs + overlapMs + SkipFingerprint.FRAME_MS);
            SkipAudioDecoder.DecodedAudio decoded = SkipAudioDecoder.decode(context, audioUri, decodeStart, decodeEnd);
            checkCancelled();
            if (reusableSource(audioUri) && !identity.equals(sourceIdentity(audioUri))) {
                throw new IOException("Audio source changed during decoding");
            }
            if (!decoded.complete || decoded.startMs > startMs) {
                throw new IOException("Audio window has incomplete decoder coverage");
            }
            long actualEnd = decoded.startMs + decoded.durationMs;
            if (decoded.eof) {
                durationMs = Math.min(durationMs, actualEnd);
                List<SkipCoverage> trimmed = new ArrayList<>();
                for (SkipCoverage range : coverage) {
                    if (range.startMs < durationMs) {
                        trimmed.add(new SkipCoverage(range.startMs, Math.min(range.endMs, durationMs)));
                    }
                }
                coverage = trimmed;
                if (startMs >= durationMs) {
                    return;
                }
            }
            long safeEnd = decoded.eof ? Math.min(endMs, durationMs)
                    : Math.min(endMs, actualEnd - overlapMs - SkipFingerprint.FRAME_MS);
            if (decoded.complete && actualEnd >= decodeEnd - 1) {
                safeEnd = Math.min(endMs, durationMs);
            }
            if (safeEnd <= startMs) {
                throw new IOException("Audio window made no analysis progress");
            }
            if (decoded.samples.length >= SkipFingerprint.SAMPLE_RATE * SkipFingerprint.FRAME_MS / 1_000) {
                AudioFingerprint target = SkipFingerprint.fromPcm(decoded.samples, SkipFingerprint.SAMPLE_RATE);
                List<SkipMarkerHit> newHits = new ArrayList<>();
                for (SkipRule rule : rules) {
                    for (SkipSample sample : rule.samples) {
                        checkCancelled();
                        for (SkipFingerprint.Match match : SkipFingerprint.findMatches(sample.fingerprint, target,
                                decoded.startMs, 0.82f)) {
                            if (match.startMs >= startMs && match.startMs < safeEnd
                                    && match.startMs + sample.durationMs <= actualEnd + 1) {
                                newHits.add(new SkipMarkerHit(rule.id, sample.id, sample.marker,
                                        match.startMs, match.score));
                            }
                        }
                    }
                }
                hits = mergeHits(hits, newHits);
            }
            coverage = mergeCoverage(coverage, Collections.singletonList(new SkipCoverage(startMs, safeEnd)));
        }

        private void checkCancelled() throws InterruptedException {
            if (task.isCancellationRequested()) {
                throw new InterruptedException();
            }
        }

        private void emit(SkipAnalysisStatus status, String error) {
            synchronized (SkipManager.this) {
                if (task.isCancellationRequested() || (callback == null && jobs.get(key) != this)) {
                    return;
                }
                SkipAnalysisSnapshot snapshot = new SkipAnalysisSnapshot(feedId, episodeId, status, revision,
                        identity, durationMs, coverage,
                        error == null ? resolveAll(rules, hits, durationMs, coverage) : Collections.emptyList(),
                        error, System.currentTimeMillis());
                if (status == SkipAnalysisStatus.READY || status == SkipAnalysisStatus.NO_MATCHES
                        || status == SkipAnalysisStatus.ERROR) {
                    noEnabledRules = error == null && rules != null && rules.isEmpty();
                    task.complete();
                }
                if (callback == null) {
                    publish(key, snapshot, task);
                } else {
                    notifyCallback(callback, snapshot);
                }
            }
        }
    }

    private static List<SkipOccurrence> resolveAll(List<SkipRule> rules, List<SkipMarkerHit> hits,
                                                   long durationMs, List<SkipCoverage> coverage) {
        List<SkipOccurrence> occurrences = new ArrayList<>();
        if (rules != null) {
            for (SkipRule rule : rules) {
                occurrences.addAll(SkipResolver.resolve(rule, hits, durationMs, coverage));
            }
        }
        occurrences.sort(Comparator.comparingLong(item -> item.startMs));
        return occurrences;
    }

    static List<SkipCoverage> mergeCoverage(List<SkipCoverage> first, List<SkipCoverage> second) {
        List<SkipCoverage> ranges = new ArrayList<>(first);
        ranges.addAll(second);
        ranges.sort(Comparator.comparingLong(item -> item.startMs));
        List<SkipCoverage> merged = new ArrayList<>();
        for (SkipCoverage range : ranges) {
            if (merged.isEmpty() || range.startMs > merged.get(merged.size() - 1).endMs) {
                merged.add(range);
            } else {
                SkipCoverage previous = merged.remove(merged.size() - 1);
                merged.add(new SkipCoverage(previous.startMs, Math.max(previous.endMs, range.endMs)));
            }
        }
        return merged;
    }

    private static List<SkipMarkerHit> mergeHits(List<SkipMarkerHit> first, List<SkipMarkerHit> second) {
        Map<String, SkipMarkerHit> values = new HashMap<>();
        List<SkipMarkerHit> all = new ArrayList<>(first);
        all.addAll(second);
        for (SkipMarkerHit hit : all) {
            String key = hit.ruleId + ":" + hit.sampleId + ":" + hit.marker + ":" + hit.timeMs;
            SkipMarkerHit previous = values.get(key);
            if (previous == null || hit.score > previous.score) {
                values.put(key, hit);
            }
        }
        return new ArrayList<>(values.values());
    }

    private static boolean isFullyCovered(List<SkipCoverage> coverage, long durationMs) {
        return firstUncovered(0, durationMs, coverage) >= durationMs;
    }

    static long chooseWindowStart(long positionMs, long durationMs, List<SkipCoverage> coverage) {
        long current = Math.max(0, Math.min(positionMs - 5_000, durationMs - WINDOW_MS));
        long upcoming = firstUncovered(current, durationMs, coverage);
        return upcoming < durationMs ? upcoming : firstUncovered(0, durationMs, coverage);
    }

    private static long firstUncovered(long fromMs, long durationMs, List<SkipCoverage> coverage) {
        long cursor = fromMs;
        for (SkipCoverage range : mergeCoverage(coverage, Collections.emptyList())) {
            if (range.startMs > cursor) {
                break;
            }
            cursor = Math.max(cursor, range.endMs);
        }
        return Math.min(cursor, durationMs);
    }

    private void publish(String key, SkipAnalysisSnapshot snapshot) {
        publish(key, snapshot, null);
    }

    private void publish(String key, SkipAnalysisSnapshot snapshot, SkipTask task) {
        snapshots.put(key, snapshot);
        List<SkipAnalysisCallback> callbacks = observers.get(key) == null ? Collections.emptyList()
                : new ArrayList<>(observers.get(key));
        for (SkipAnalysisCallback callback : callbacks) {
            if (snapshots.get(key) != snapshot || (task != null && task.isCancelled())) {
                break;
            }
            List<SkipAnalysisCallback> registered = observers.get(key);
            if (registered != null && registered.contains(callback)) {
                notifyCallback(callback, snapshot);
            }
        }
    }

    private static void notifyCallback(SkipAnalysisCallback callback, SkipAnalysisSnapshot snapshot) {
        try {
            callback.onChanged(snapshot);
        } catch (RuntimeException ignored) {
        }
    }

    private void invalidateFeed(String feedId) {
        Set<String> keys = new HashSet<>(snapshots.keySet());
        keys.addAll(jobs.keySet());
        keys.addAll(observers.keySet());
        Map<String, SkipAnalysisSnapshot> invalidated = new HashMap<>();
        for (String key : keys) {
            if (key.startsWith(feedId + "\n")) {
                AnalysisJob job = jobs.remove(key);
                if (job != null) {
                    job.task.cancel();
                }
                SkipAnalysisSnapshot snapshot = SkipAnalysisSnapshot.notAnalyzed(feedId,
                        key.substring(feedId.length() + 1));
                snapshots.put(key, snapshot);
                invalidated.put(key, snapshot);
            }
        }
        for (Map.Entry<String, SkipAnalysisSnapshot> entry : invalidated.entrySet()) {
            if (snapshots.get(entry.getKey()) == entry.getValue()) {
                publish(entry.getKey(), entry.getValue());
            }
        }
    }

    private static boolean reusableSource(Uri uri) {
        return uri.getScheme() == null || "file".equals(uri.getScheme());
    }

    private static String sourceIdentity(Uri uri) {
        if (reusableSource(uri) && uri.getPath() != null) {
            File file = new File(uri.getPath());
            return file.getAbsolutePath() + ":" + file.length() + ":" + file.lastModified();
        }
        return uri + ":" + UUID.randomUUID();
    }

    private static void validateAnalysis(Uri audioUri, long durationMs, long positionMs) {
        if (audioUri == null || durationMs <= 0 || durationMs > Long.MAX_VALUE / 1_000 || positionMs < 0) {
            throw new IllegalArgumentException("Invalid analysis request");
        }
        if (audioUri.getScheme() != null && !"file".equals(audioUri.getScheme())
                && !"content".equals(audioUri.getScheme())) {
            throw new IllegalArgumentException("Analysis requires downloaded local audio");
        }
    }

    private static void validateRange(long startMs, long endMs) {
        if (startMs < 0 || endMs > Long.MAX_VALUE / 1_000 || endMs <= startMs
                || endMs - startMs < 500 || endMs - startMs > 30_000) {
            throw new IllegalArgumentException("Audio range must be between 500ms and 30s");
        }
    }

    private static String requireId(String value) {
        if (value == null || value.isEmpty() || value.contains("\n")) {
            throw new IllegalArgumentException("A non-empty single-line ID is required");
        }
        return value;
    }

    private static String key(String feedId, String episodeId) {
        return feedId + "\n" + episodeId;
    }
}
