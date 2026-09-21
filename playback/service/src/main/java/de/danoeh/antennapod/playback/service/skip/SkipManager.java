package de.danoeh.antennapod.playback.service.skip;

import android.content.Context;
import android.net.Uri;
import android.util.Log;
import de.danoeh.antennapod.playback.service.BuildConfig;

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
    private static final String MATCH_TAG = "SkipMatch";
    private static final long COVERAGE_GAP_TOLERANCE_MS = SkipFingerprint.HOP_MS;
    private static final long WINDOW_MS = 30_000;
    private static final long STREAM_LOOKBEHIND_MS = 10_000;
    private static final long LOCAL_SEEK_FALLBACK_MS = 1_000;
    private static final int LOCAL_DECODE_RETRIES = 1;
    private static final int MAX_RETAINED_DECODER_SESSIONS = 2;
    private static volatile SkipManager instance;

    private final Context context;
    private final SkipRuleStore ruleStore;
    private final SkipAnalysisCache analysisCache;
    private final SkipPriorityExecutor executor = new SkipPriorityExecutor();
    private final Map<String, SkipAnalysisSnapshot> snapshots = new HashMap<>();
    private final Map<String, List<SkipAnalysisCallback>> observers = new HashMap<>();
    private final Map<String, AnalysisJob> jobs = new HashMap<>();
    private final Set<String> restoringAnalyses = new HashSet<>();
    private final Map<String, SkipAnalysisCache.Entry> streamingCheckpoints = new HashMap<>();
    private final List<AnalysisJob> decoderSessionOwners = new ArrayList<>();

    private SkipManager(Context context) {
        this.context = context.getApplicationContext();
        ruleStore = new SkipRuleStore(this.context);
        analysisCache = new SkipAnalysisCache(this.context.getFilesDir());
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

    public void restoreAnalysis(String feedId, String episodeId, Uri audioUri, long durationMs) {
        String validFeedId = requireId(feedId);
        String validEpisodeId = requireId(episodeId);
        validateAnalysis(audioUri, durationMs, 0);
        if (!reusableSource(audioUri)) {
            return;
        }
        String analysisKey = key(validFeedId, validEpisodeId);
        synchronized (this) {
            AnalysisJob existing = jobs.get(analysisKey);
            SkipAnalysisSnapshot snapshot = snapshots.get(analysisKey);
            if (existing != null || restoringAnalyses.contains(analysisKey)
                    || snapshot != null && snapshot.status != SkipAnalysisStatus.NOT_ANALYZED) {
                return;
            }
            restoringAnalyses.add(analysisKey);
        }
        Thread restoreThread = new Thread(() -> restoreAnalysis(validFeedId, validEpisodeId, audioUri,
                durationMs, analysisKey), "skip-audio-analysis-restore");
        restoreThread.setDaemon(true);
        restoreThread.start();
    }

    public synchronized void removeAnalysis(String feedId, String episodeId) {
        String validFeedId = requireId(feedId);
        String validEpisodeId = requireId(episodeId);
        String analysisKey = key(validFeedId, validEpisodeId);
        AnalysisJob job = jobs.remove(analysisKey);
        if (job != null) {
            job.task.cancel();
        }
        try {
            analysisCache.delete(validFeedId, validEpisodeId);
        } catch (IOException ignored) {
        }
        streamingCheckpoints.remove(analysisKey);
        restoringAnalyses.remove(analysisKey);
        boolean hadState = snapshots.containsKey(analysisKey) || job != null || observers.containsKey(analysisKey);
        if (hadState) {
            SkipAnalysisSnapshot snapshot = SkipAnalysisSnapshot.notAnalyzed(validFeedId, validEpisodeId);
            snapshots.put(analysisKey, snapshot);
            publish(analysisKey, snapshot);
        }
    }

    public synchronized SkipTask analyze(String feedId, String episodeId, Uri audioUri, long durationMs,
                                          long positionMs, SkipPriority priority) {
        return analyze(feedId, episodeId, audioUri, durationMs, positionMs, -1, priority, true);
    }

    public synchronized SkipTask analyzeForPlayback(String feedId, String episodeId, Uri audioUri,
                                                     long durationMs, long positionMs,
                                                     SkipPriority priority) {
        return analyze(feedId, episodeId, audioUri, durationMs, positionMs, -1, priority, false);
    }

    public synchronized SkipTask analyzeForPlayback(String feedId, String episodeId, Uri audioUri,
                                                     long durationMs, long positionMs, long bufferedPositionMs,
                                                     SkipPriority priority) {
        return analyze(feedId, episodeId, audioUri, durationMs, positionMs, bufferedPositionMs, priority, false);
    }

    public synchronized SkipTask retryAnalysis(String feedId, String episodeId, Uri audioUri, long durationMs,
                                               long positionMs, SkipPriority priority) {
        String validFeedId = requireId(feedId);
        String validEpisodeId = requireId(episodeId);
        validateAnalysis(audioUri, durationMs, positionMs);
        String key = key(validFeedId, validEpisodeId);
        AnalysisJob existing = jobs.get(key);
        SkipAnalysisSnapshot snapshot = snapshots.get(key);
        if (existing != null && snapshot != null && snapshot.status == SkipAnalysisStatus.ERROR
                && !existing.task.isCancelled() && existing.audioUri.equals(audioUri)
                && existing.requestedDurationMs == durationMs) {
            existing.retry(positionMs, priority);
            existing.emit(SkipAnalysisStatus.ANALYZING, null);
            existing.enqueue();
            return existing.task;
        }
        return SkipStreamingSource.isStreaming(audioUri)
                ? analyzeForPlayback(validFeedId, validEpisodeId, audioUri, durationMs, positionMs, priority)
                : analyze(validFeedId, validEpisodeId, audioUri, durationMs, 0, priority);
    }

    private synchronized SkipTask analyze(String feedId, String episodeId, Uri audioUri, long durationMs,
                                           long positionMs, long bufferedPositionMs, SkipPriority priority,
                                           boolean fetchMissing) {
        String validFeedId = requireId(feedId);
        String validEpisodeId = requireId(episodeId);
        validateAnalysis(audioUri, durationMs, positionMs);
        String key = key(validFeedId, validEpisodeId);
        AnalysisJob existing = jobs.get(key);
        if (existing != null && existing.noEnabledRules && !existing.task.isCancelled()
                && existing.audioUri.equals(audioUri) && existing.requestedDurationMs == durationMs
                && existing.fetchMissing == fetchMissing) {
            return existing.task;
        }
        SkipAnalysisSnapshot existingSnapshot = snapshots.get(key);
        if (existing != null && existing.task.isDone() && !existing.task.isCancelled()
                && reusableSource(audioUri) && existing.audioUri.equals(audioUri)
                && existing.requestedDurationMs == durationMs && existing.identity != null
                && existing.identity.equals(sourceIdentity(audioUri)) && existingSnapshot != null
                && (existingSnapshot.status == SkipAnalysisStatus.READY
                || existingSnapshot.status == SkipAnalysisStatus.NO_MATCHES)) {
            return existing.task;
        }
        if (existing != null && !existing.task.isDone()
                && (reusableSource(audioUri) || SkipStreamingSource.isStreaming(audioUri))
                && existing.audioUri.equals(audioUri)
                && (existing.requestedDurationMs == durationMs || SkipStreamingSource.isStreaming(audioUri))
                && (reusableSource(audioUri) || existing.fetchMissing == fetchMissing)) {
            SkipPriority requestedPriority = priority == null ? SkipPriority.BACKGROUND : priority;
            if (requestedPriority.value > existing.priority.value) {
                return existing.task;
            }
            existing.durationMs = durationMs;
            existing.requestedDurationMs = durationMs;
            existing.positionMs = positionMs;
            existing.bufferedPositionMs = bufferedPositionMs;
            existing.priority = requestedPriority;
            executor.reprioritize(existing.task, existing.priority);
            existing.wake(true);
            return existing.task;
        }
        if (existing != null) {
            existing.task.cancel();
        }
        AnalysisJob job = new AnalysisJob(validFeedId, validEpisodeId, audioUri, durationMs,
                positionMs, bufferedPositionMs, priority, null, null, -1, -1, fetchMissing);
        jobs.put(key, job);
        snapshots.put(key, new SkipAnalysisSnapshot(feedId, episodeId, SkipAnalysisStatus.ANALYZING,
                0, null, durationMs, Collections.emptyList(), Collections.emptyList(), null,
                System.currentTimeMillis()));
        job.enqueue();
        return job.task;
    }

    private void restoreAnalysis(String feedId, String episodeId, Uri audioUri, long durationMs, String analysisKey) {
        try {
            SkipRuleStore.RuleSet saved = ruleStore.read(feedId);
            SkipAnalysisCache.Entry cached = analysisCache.read(feedId, episodeId);
            String identity = sourceIdentity(audioUri);
            if (cached == null || !identity.equals(cached.sourceIdentity)
                    || cached.rulesRevision != saved.revision || cached.durationMs != durationMs) {
                if (BuildConfig.DEBUG) {
                    Log.d("SkipManager", "Analysis restore rejected feed=" + feedId + " episode=" + episodeId
                            + " cached=" + (cached == null ? "missing" : cached.sourceIdentity + ":"
                            + cached.rulesRevision + ":" + cached.durationMs) + " requested=" + identity + ":"
                            + saved.revision + ":" + durationMs);
                }
                return;
            }
            List<SkipRule> rules = new ArrayList<>();
            for (SkipRule rule : saved.rules) {
                rule.validate();
                if (rule.enabled) {
                    rules.add(rule);
                }
            }
            Resolution resolution = resolveAll(rules, cached.hits, durationMs, cached.coverage);
            SkipAnalysisStatus status = isFullyCovered(cached.coverage, durationMs)
                    ? completedStatus(resolution) : SkipAnalysisStatus.ANALYZING;
            synchronized (this) {
                if (jobs.containsKey(analysisKey)) {
                    return;
                }
                SkipAnalysisSnapshot existing = snapshots.get(analysisKey);
                if (existing != null && existing.status != SkipAnalysisStatus.NOT_ANALYZED) {
                    return;
                }
                publish(analysisKey, new SkipAnalysisSnapshot(feedId, episodeId, status, saved.revision,
                        identity, durationMs, cached.coverage, resolution.occurrences, resolution.detections,
                        null, System.currentTimeMillis()));
            }
        } catch (IOException | RuntimeException error) {
            if (BuildConfig.DEBUG) {
                Log.d("SkipManager", "Analysis restore failed feed=" + feedId + " episode=" + episodeId,
                        error);
            }
        } finally {
            synchronized (this) {
                restoringAnalyses.remove(analysisKey);
            }
        }
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

    public synchronized boolean updateStreamingPosition(String feedId, String episodeId, long positionMs,
                                                          float speed) {
        return updateStreamingPosition(feedId, episodeId, positionMs, speed, -1);
    }

    public synchronized boolean updateStreamingPosition(String feedId, String episodeId, long positionMs,
                                                          float speed, long bufferedPositionMs) {
        return updateStreamingPosition(feedId, episodeId, positionMs, speed, bufferedPositionMs, false);
    }

    public synchronized boolean updateStreamingPosition(String feedId, String episodeId, long positionMs,
                                                          float speed, long bufferedPositionMs,
                                                          boolean resetAnalysisPosition) {
        if (positionMs < 0 || Float.isNaN(speed) || Float.isInfinite(speed) || speed <= 0) {
            throw new IllegalArgumentException("Invalid streaming playback position");
        }
        AnalysisJob job = jobs.get(key(requireId(feedId), requireId(episodeId)));
        if (job == null || job.task.isDone() || !SkipStreamingSource.isStreaming(job.audioUri)) {
            return false;
        }
        job.positionMs = positionMs;
        job.bufferedPositionMs = bufferedPositionMs;
        if (resetAnalysisPosition) {
            job.streamingAnalysisPositionMs = -1;
        }
        job.playbackSpeed = speed;
        job.priority = SkipPriority.CURRENT_PLAYBACK;
        executor.reprioritize(job.task, job.priority);
        job.wake(resetAnalysisPosition);
        return true;
    }

    public SkipTask extractSample(String feedId, String episodeId, Uri audioUri, SkipMarker marker,
                                   long startMs, long endMs, long markerOffsetMs,
                                   SkipSampleCallback callback) {
        return extractSample(feedId, episodeId, audioUri, marker, startMs, endMs, markerOffsetMs, -1,
                callback);
    }

    public SkipTask extractSample(String feedId, String episodeId, Uri audioUri, SkipMarker marker,
                                   long startMs, long endMs, long markerOffsetMs, long sourcePositionMs,
                                   SkipSampleCallback callback) {
        requireId(feedId);
        requireId(episodeId);
        validateRange(startMs, endMs);
        if (audioUri == null || marker == null || callback == null
                || markerOffsetMs < 0 || markerOffsetMs > endMs - startMs || sourcePositionMs < -1) {
            throw new IllegalArgumentException("Invalid sample request");
        }
        validateAudioSource(audioUri);
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
                if (BuildConfig.DEBUG && Log.isLoggable(MATCH_TAG, Log.DEBUG)) {
                    Log.d(MATCH_TAG, "extracted marker=" + marker + " sample=" + fingerprint.durationMs()
                            + "ms range=" + startMs + ".." + endMs + " sourcePosition=" + sourcePositionMs
                            + " markerOffset=" + markerOffsetMs + " frames=" + fingerprint.frameCount());
                }
                if (!Thread.currentThread().isInterrupted()) {
                    callback.onSuccess(new SkipSample(UUID.randomUUID().toString(), marker,
                            endMs - startMs, markerOffsetMs, sourcePositionMs, fingerprint));
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
        validateAudioSource(audioUri);
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
        return testRule(rule, audioUri, durationMs, 0, callback, false);
    }

    public SkipTask testRule(SkipRule rule, Uri audioUri, long durationMs, long positionMs,
                             SkipAnalysisCallback callback) {
        return testRule(rule, audioUri, durationMs, positionMs, callback, true);
    }

    private SkipTask testRule(SkipRule rule, Uri audioUri, long durationMs, long positionMs,
                               SkipAnalysisCallback callback, boolean bounded) {
        validateAnalysis(audioUri, durationMs, 0);
        if (rule == null || callback == null) {
            throw new IllegalArgumentException("Invalid test request");
        }
        rule.validate();
        if (SkipStreamingSource.isStreaming(audioUri) && !bounded) {
            throw new IllegalArgumentException("Streaming rule tests require a bounded playback position");
        }
        long hint = Math.max(0, Math.min(positionMs, durationMs));
        AnalysisJob job = new AnalysisJob("unsaved", "unsaved", audioUri, durationMs, 0,
                -1, SkipPriority.HIGH, Collections.singletonList(rule.withEnabled(true)), callback,
                bounded ? Math.max(0, hint - 5_000) : -1,
                bounded ? testEndPosition(rule, hint, durationMs) : -1, true);
        job.enqueue();
        return job.task;
    }

    private final class AnalysisJob implements Runnable {
        private final String feedId;
        private final String episodeId;
        private final String key;
        private final Uri audioUri;
        private final boolean fetchMissing;
        private volatile long requestedDurationMs;
        private final SkipAnalysisCallback callback;
        private final SkipTask task = new SkipTask();
        private volatile long positionMs;
        private volatile long bufferedPositionMs;
        private volatile long streamingAnalysisPositionMs = -1;
        private volatile SkipPriority priority;
        private volatile float playbackSpeed = 1;
        private List<SkipRule> rules;
        private long revision;
        private long durationMs;
        private String identity;
        private boolean noEnabledRules;
        private List<SkipCoverage> coverage = new ArrayList<>();
        private List<SkipMarkerHit> hits = new ArrayList<>();
        private final long analysisStartMs;
        private final long analysisEndMs;
        private boolean queued;
        private boolean wakeRequested;
        private boolean resetStreamingPassRequested;
        private boolean streamingPassExhausted;
        private long streamingAnalysisEndMs = -1;
        private boolean streamingUnsupported;
        private String waitingError;
        private SkipAudioDecoder.Session decoderSession;
        private volatile boolean running;

        AnalysisJob(String feedId, String episodeId, Uri audioUri, long durationMs, long positionMs,
                    SkipPriority priority, List<SkipRule> rules, SkipAnalysisCallback callback) {
            this(feedId, episodeId, audioUri, durationMs, positionMs, -1, priority, rules, callback, -1, -1,
                    true);
        }

        AnalysisJob(String feedId, String episodeId, Uri audioUri, long durationMs, long positionMs,
                    long bufferedPositionMs, SkipPriority priority, List<SkipRule> rules,
                    SkipAnalysisCallback callback,
                    long analysisStartMs, long analysisEndMs, boolean fetchMissing) {
            this.feedId = feedId;
            this.episodeId = episodeId;
            key = key(feedId, episodeId);
            this.audioUri = audioUri;
            this.fetchMissing = fetchMissing;
            this.durationMs = durationMs;
            requestedDurationMs = durationMs;
            this.positionMs = positionMs;
            this.bufferedPositionMs = bufferedPositionMs;
            this.priority = priority == null ? SkipPriority.BACKGROUND : priority;
            this.rules = rules;
            this.callback = callback;
            this.analysisStartMs = analysisStartMs;
            this.analysisEndMs = analysisEndMs;
            task.setCancellationListener(this::cancelDecoderSession);
        }

        synchronized void enqueue() {
            if (!queued && !task.isCancellationRequested()) {
                queued = true;
                executor.execute(priority, task, this);
            }
        }

        synchronized void wake(boolean resetStreamingPass) {
            resetStreamingPassRequested |= resetStreamingPass;
            if (queued) {
                wakeRequested |= fetchMissing || resetStreamingPass || streamingPassExhausted;
            } else if (identity == null || !SkipStreamingSource.isStreaming(audioUri)
                    || !isCoveredFrom(coverage, streamingWindowStart(), streamingWindowEnd())) {
                streamingAnalysisPositionMs = -1;
                enqueue();
            }
        }

        synchronized void retry(long positionMs, SkipPriority priority) {
            this.positionMs = positionMs;
            this.priority = priority == null ? SkipPriority.BACKGROUND : priority;
            streamingAnalysisPositionMs = -1;
            streamingAnalysisEndMs = -1;
            resetStreamingPassRequested = false;
            streamingPassExhausted = false;
            wakeRequested = false;
            waitingError = null;
            task.retry();
            queued = false;
        }

        @Override
        public void run() {
            boolean continueAnalysis = false;
            boolean waitingForAudio = false;
            synchronized (SkipManager.this) {
                synchronized (this) {
                    running = true;
                }
            }
            try {
                checkCancelled();
                if (identity == null) {
                    initialize();
                    emit(SkipAnalysisStatus.ANALYZING, null);
                }
                checkCancelled();
                if (streamingUnsupported) {
                    emit(SkipAnalysisStatus.DOWNLOAD_REQUIRED, null);
                    return;
                }
                if (rules.isEmpty()) {
                    emit(SkipAnalysisStatus.NO_MATCHES, null);
                    return;
                }
                waitingError = null;
                if (reusableSource(audioUri) && !identity.equals(sourceIdentity(audioUri))) {
                    throw new IOException("Audio source changed during analysis");
                }
                if (SkipStreamingSource.isStreaming(audioUri) && !SkipStreamingSource.isAvailable(audioUri)) {
                    waitingError = "Streaming cache source is unavailable";
                    boolean complete = isFullyCovered(coverage, durationMs);
                    emit(complete ? completedStatus(resolveAll(rules, hits, durationMs, coverage))
                            : SkipAnalysisStatus.WAITING_FOR_AUDIO, complete ? null : waitingError);
                    return;
                }
                long coverageStart = analysisStartMs >= 0 ? analysisStartMs
                        : SkipStreamingSource.isStreaming(audioUri) ? streamingWindowStart() : 0;
                long coverageEnd = analysisEndMs >= 0 ? analysisEndMs
                        : SkipStreamingSource.isStreaming(audioUri) ? streamingWindowEnd() : durationMs;
                boolean streamingPlayback = SkipStreamingSource.isStreaming(audioUri) && callback == null
                        && !rules.isEmpty();
                boolean cacheOnlyPlayback = streamingPlayback && !fetchMissing;
                boolean streamingPassHasMore = false;
                boolean streamingCoverageChanged = false;
                boolean streamingFetchFailed = false;
                if (cacheOnlyPlayback) {
                    long startMs;
                    long endMs;
                    synchronized (this) {
                        if (streamingAnalysisPositionMs < 0) {
                            streamingAnalysisPositionMs = coverageStart;
                            streamingAnalysisEndMs = coverageEnd;
                            resetStreamingPassRequested = false;
                            streamingPassExhausted = false;
                        }
                        startMs = firstUncovered(Math.max(coverageStart, streamingAnalysisPositionMs),
                                streamingAnalysisEndMs, coverage);
                        if (startMs < streamingAnalysisEndMs) {
                            startMs -= startMs % SkipFingerprint.HOP_MS;
                            endMs = Math.min(streamingAnalysisEndMs, startMs + WINDOW_MS);
                        } else {
                            endMs = startMs;
                        }
                    }
                    if (startMs < streamingAnalysisEndMs) {
                        long coveredBefore = coveredDuration(coverage);
                        if (BuildConfig.DEBUG) {
                            Log.d("SkipManager", "Cache analysis source=" + identity + " position=" + positionMs
                                    + " buffered=" + bufferedPositionMs + " window=" + startMs + ".." + endMs
                                    + " cursor=" + streamingAnalysisPositionMs);
                        }
                        try {
                            analyzeWindow(startMs, endMs, true);
                            checkpoint();
                        } catch (SkipStreamingSource.UnavailableException error) {
                            waitingError = error.toString();
                            streamingFetchFailed = error.getSuppressed().length > 0;
                            if (BuildConfig.DEBUG) {
                                Log.d("SkipManager", "Cache analysis deferred: " + error.getMessage());
                            }
                        }
                        streamingCoverageChanged = coveredDuration(coverage) > coveredBefore;
                        checkCancelled();
                        synchronized (this) {
                            if (!resetStreamingPassRequested) {
                                streamingAnalysisPositionMs = Math.max(streamingAnalysisPositionMs, endMs);
                            }
                        }
                    }
                    synchronized (this) {
                        if (resetStreamingPassRequested) {
                            return;
                        }
                        streamingPassHasMore = !resetStreamingPassRequested && !streamingFetchFailed
                                && firstUncovered(Math.max(coverageStart, streamingAnalysisPositionMs),
                                streamingAnalysisEndMs, coverage) < streamingAnalysisEndMs;
                    }
                    waitingForAudio = !streamingPassHasMore
                            && !isCoveredFrom(coverage, coverageStart, coverageEnd);
                    synchronized (this) {
                        streamingPassExhausted = waitingForAudio;
                    }
                } else if (!isCoveredFrom(coverage, coverageStart, coverageEnd)) {
                    long startMs = analysisStartMs >= 0 && firstUncovered(coverageStart, coverageEnd, coverage)
                            < coverageEnd ? firstUncovered(coverageStart, coverageEnd, coverage)
                            : SkipStreamingSource.isStreaming(audioUri)
                            ? firstUncovered(coverageStart, coverageEnd, coverage)
                            : chooseWindowStart(positionMs, coverageEnd, coverage);
                    startMs -= startMs % SkipFingerprint.HOP_MS;
                    waitingForAudio = analyzeWindow(startMs, Math.min(coverageEnd, startMs + WINDOW_MS), false);
                    checkpoint();
                    checkCancelled();
                }
                boolean done = rules.isEmpty() || isCoveredFrom(coverage, coverageStart, coverageEnd);
                Resolution resolution = resolveAll(rules, hits, durationMs, coverage);
                List<SkipOccurrence> occurrences = resolution.occurrences;
                if (callback != null && !done && !occurrences.isEmpty()) {
                    done = true;
                }
                boolean fullyCovered = isFullyCovered(coverage, durationMs);
                boolean emptyStreamingWindow = streamingPlayback && coverageEnd <= coverageStart
                        && !fullyCovered;
                SkipAnalysisStatus status = fullyCovered
                        ? completedStatus(resolution)
                        : waitingForAudio || emptyStreamingWindow ? SkipAnalysisStatus.WAITING_FOR_AUDIO
                        : done ? streamingPlayback ? SkipAnalysisStatus.WINDOW_READY
                        : occurrences.isEmpty() && resolution.detections.isEmpty()
                        ? SkipAnalysisStatus.NO_MATCHES : SkipAnalysisStatus.READY
                        : SkipAnalysisStatus.ANALYZING;
                boolean suppressUnchangedProbe = cacheOnlyPlayback && status == SkipAnalysisStatus.ANALYZING
                        && !streamingCoverageChanged;
                if (!suppressUnchangedProbe) {
                    emit(status, status == SkipAnalysisStatus.WAITING_FOR_AUDIO ? waitingError : null);
                }
                boolean moreStreamingAudio = streamingPlayback
                        && !isCoveredFrom(coverage, streamingWindowStart(), streamingWindowEnd());
                if (cacheOnlyPlayback ? streamingPassHasMore
                        : !waitingForAudio && (!done || moreStreamingAudio) && !task.isCancellationRequested()) {
                    continueAnalysis = true;
                }
            } catch (InterruptedException | CancellationException ignored) {
                task.cancel();
            } catch (SkipStreamingSource.UnavailableException error) {
                boolean complete = isFullyCovered(coverage, durationMs);
                if (BuildConfig.DEBUG) {
                    Log.d("SkipManager", "Streaming analysis unavailable source=" + audioUri
                            + " position=" + positionMs + " buffered=" + bufferedPositionMs
                            + " coverage=" + coverage + " error=" + error.getMessage(), error);
                }
                emit(complete ? completedStatus(resolveAll(rules, hits, durationMs, coverage))
                        : SkipAnalysisStatus.WAITING_FOR_AUDIO, complete ? null : error.toString());
            } catch (Exception error) {
                if (BuildConfig.DEBUG) {
                    Log.d("SkipManager", "Streaming analysis error source=" + audioUri
                            + " position=" + positionMs + " buffered=" + bufferedPositionMs
                            + " coverage=" + coverage, error);
                }
                emit(SkipAnalysisStatus.ERROR, error.toString());
            } finally {
                SkipAudioDecoder.Session sessionToClose = null;
                boolean enqueueContinuation;
                synchronized (SkipManager.this) {
                    synchronized (this) {
                        if ((!continueAnalysis && wakeRequested) || resetStreamingPassRequested) {
                            streamingAnalysisPositionMs = -1;
                            streamingAnalysisEndMs = -1;
                            continueAnalysis = true;
                            wakeRequested = false;
                            resetStreamingPassRequested = false;
                        }
                        enqueueContinuation = continueAnalysis && !task.isCancellationRequested();
                        if (!enqueueContinuation) {
                            sessionToClose = detachDecoderSessionLocked();
                        }
                        running = false;
                        queued = enqueueContinuation;
                    }
                }
                if (sessionToClose != null) {
                    sessionToClose.close();
                }
                if (enqueueContinuation) {
                    executor.execute(priority, task, this);
                }
            }
        }

        private void initialize() throws IOException, InterruptedException {
            identity = sourceIdentity(audioUri);
            synchronized (SkipManager.this) {
                checkCancelled();
                if (rules == null) {
                    SkipRuleStore.RuleSet saved = ruleStore.read(feedId);
                    revision = saved.revision;
                    rules = new ArrayList<>(saved.rules);
                }
                List<SkipRule> enabledRules = new ArrayList<>();
                boolean hasEnabledRules = false;
                for (SkipRule rule : rules) {
                    rule.validate();
                    if (rule.enabled) {
                        hasEnabledRules = true;
                        if (SkipStreamingSource.isStreaming(audioUri) && rule.type == SkipRule.Type.BETWEEN
                                && rule.maxDurationMs == 0) {
                            streamingUnsupported = true;
                        } else {
                            enabledRules.add(rule);
                        }
                    }
                }
                rules = enabledRules;
                streamingUnsupported &= hasEnabledRules && rules.isEmpty();
            }
            if (callback == null && cacheableSource(audioUri)) {
                try {
                    SkipAnalysisCache.Entry cached = analysisCache.read(feedId, episodeId);
                    if (cached != null && identity.equals(cached.sourceIdentity)
                            && cached.rulesRevision == revision && cached.durationMs == durationMs) {
                        coverage = new ArrayList<>(cached.coverage);
                        hits = new ArrayList<>(cached.hits);
                    }
                } catch (IOException ignored) {
                }
            } else if (callback == null && SkipStreamingSource.isStreaming(audioUri)) {
                SkipAnalysisCache.Entry cached;
                synchronized (SkipManager.this) {
                    cached = streamingCheckpoints.get(key);
                }
                if (cached != null && identity.equals(cached.sourceIdentity)
                        && cached.rulesRevision == revision && cached.durationMs == durationMs) {
                    coverage = new ArrayList<>(cached.coverage);
                    hits = new ArrayList<>(cached.hits);
                }
            }
        }

        private long streamingWindowStart() {
            return fetchMissing ? Math.max(0, positionMs - Math.max(STREAM_LOOKBEHIND_MS, longestSampleDuration()))
                    : positionMs;
        }

        private long streamingWindowEnd() {
            if (bufferedPositionMs >= 0) {
                return Math.min(durationMs, Math.max(positionMs, bufferedPositionMs));
            }
            long betweenHorizon = 0;
            for (SkipRule rule : rules) {
                if (rule.type == SkipRule.Type.BETWEEN) {
                    long startSample = 0;
                    long endSample = 0;
                    for (SkipSample sample : rule.samples) {
                        if (sample.marker == SkipMarker.START) {
                            startSample = Math.max(startSample, sample.durationMs);
                        } else if (!rule.useStartAsEnd) {
                            endSample = Math.max(endSample, sample.durationMs);
                        }
                    }
                    if (rule.useStartAsEnd) {
                        endSample = startSample;
                    }
                    betweenHorizon = Math.max(betweenHorizon, rule.maxDurationMs + startSample + endSample);
                }
            }
            long headroom = (long) Math.ceil(WINDOW_MS * Math.max(1, playbackSpeed));
            return Math.min(durationMs, positionMs + betweenHorizon + headroom);
        }

        private long longestSampleDuration() {
            long longest = 0;
            for (SkipRule rule : rules) {
                for (SkipSample sample : rule.samples) {
                    if (!rule.useStartAsEnd || sample.marker == SkipMarker.START) {
                        longest = Math.max(longest, sample.durationMs);
                    }
                }
            }
            return longest;
        }

        private boolean analyzeWindow(long startMs, long endMs, boolean allowFetchRetry)
                throws IOException, InterruptedException {
            long overlapMs = 0;
            for (SkipRule rule : rules) {
                for (SkipSample sample : rule.samples) {
                    if (!rule.useStartAsEnd || sample.marker == SkipMarker.START) {
                        overlapMs = Math.max(overlapMs, sample.durationMs);
                    }
                }
            }
            boolean cacheOnlyStreaming = SkipStreamingSource.isStreaming(audioUri) && !fetchMissing;
            long decodeStart = cacheOnlyStreaming ? startMs : Math.max(0, startMs - overlapMs);
            decodeStart -= decodeStart % SkipFingerprint.HOP_MS;
            long decodeEnd = Math.min(durationMs, endMs + overlapMs + SkipFingerprint.FRAME_MS);
            SkipAudioDecoder.DecodedAudio decoded = SkipStreamingSource.isStreaming(audioUri)
                    ? decodeStreamingWindow(decodeStart, decodeEnd, allowFetchRetry)
                    : decodeLocalWindow(decodeStart, decodeEnd);
            checkCancelled();
            if (reusableSource(audioUri) && !identity.equals(sourceIdentity(audioUri))) {
                throw new IOException("Audio source changed during decoding");
            }
            long actualEnd = decoded.startMs + decoded.durationMs;
            if (BuildConfig.DEBUG && !SkipStreamingSource.isStreaming(audioUri)) {
                Log.d("SkipManager", "Local decoded=" + decoded.startMs + ".." + actualEnd
                        + " complete=" + decoded.complete + " eof=" + decoded.eof);
            }
            if (SkipStreamingSource.isStreaming(audioUri)
                    && (!decoded.complete && !decoded.cacheMiss
                    || decoded.startMs > startMs && !cacheOnlyStreaming)) {
                throw new SkipStreamingSource.UnavailableException(
                        "Audio window has incomplete decoder coverage");
            }
            if (decoded.startMs > startMs && !cacheOnlyStreaming) {
                if (SkipStreamingSource.isStreaming(audioUri)) {
                    throw new SkipStreamingSource.UnavailableException(
                            "Audio window starts after requested analysis range");
                }
                throw new IOException("Audio window starts after requested analysis range");
            }
            if (decoded.eof && decoded.samples.length == 0) {
                if (endMs - startMs > SkipFingerprint.HOP_MS) {
                    throw new IOException("Audio decoder produced no samples in requested window");
                }
                coverage = mergeCoverage(coverage,
                        Collections.singletonList(new SkipCoverage(startMs, Math.min(endMs, durationMs))));
                return false;
            }
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
                    return false;
                }
            }
            long coveredStart = Math.max(startMs, decoded.startMs);
            long safeEnd = decoded.eof ? Math.min(endMs, durationMs)
                    : Math.min(endMs, actualEnd - overlapMs - SkipFingerprint.FRAME_MS);
            if (decoded.complete && actualEnd >= decodeEnd - 1) {
                safeEnd = Math.min(endMs, durationMs);
            }
            if (BuildConfig.DEBUG && cacheOnlyStreaming) {
                Log.d("SkipManager", "Cache decoded=" + decoded.startMs + ".." + actualEnd
                        + " verified=" + coveredStart + ".." + safeEnd + " cacheMiss=" + decoded.cacheMiss);
            }
            if (safeEnd <= coveredStart) {
                if (SkipStreamingSource.isStreaming(audioUri)) {
                    throw new SkipStreamingSource.UnavailableException("Audio window made no analysis progress");
                }
                throw new IOException("Audio window made no analysis progress");
            }
            if (!SkipStreamingSource.isStreaming(audioUri) && isCoveredFrom(coverage, coveredStart, safeEnd)) {
                throw new IOException("Audio window made no analysis progress");
            }
            if (decoded.samples.length >= SkipFingerprint.SAMPLE_RATE * SkipFingerprint.FRAME_MS / 1_000) {
                AudioFingerprint target = SkipFingerprint.fromPcm(decoded.samples, SkipFingerprint.SAMPLE_RATE);
                List<SkipMarkerHit> newHits = new ArrayList<>();
                for (SkipRule rule : rules) {
                    for (SkipSample sample : rule.samples) {
                        if (rule.useStartAsEnd && sample.marker == SkipMarker.END) {
                            if (BuildConfig.DEBUG && Log.isLoggable(MATCH_TAG, Log.DEBUG)) {
                                Log.d(MATCH_TAG, "window=" + startMs + ".." + endMs + " rule=" + rule.id
                                        + " sample=" + sample.id + " marker=END result=filtered-use-start-as-end");
                            }
                            continue;
                        }
                        checkCancelled();
                        List<SkipFingerprint.Match> matches = SkipFingerprint.findMatches(sample.fingerprint, target,
                                decoded.startMs, 0.82f);
                        if (BuildConfig.DEBUG && Log.isLoggable(MATCH_TAG, Log.DEBUG)) {
                            SkipFingerprint.MatchDetails details = SkipFingerprint.findBestMatchDetails(
                                    sample.fingerprint, target, decoded.startMs);
                            SkipFingerprint.Match best = details == null ? null : details.match;
                            Log.d(MATCH_TAG, "window=" + startMs + ".." + endMs + " decoded="
                                    + decoded.startMs + ".." + actualEnd + " rule=" + rule.id + " sample="
                                    + sample.id + " marker=" + sample.marker + " best=" + formatMatch(best)
                                    + " threshold=0.82 candidates=" + matches.size() + " "
                                    + formatDetails(details) + " " + formatPcm(decoded.samples));
                            if (best != null && best.score >= 0.7f) {
                                Log.d(MATCH_TAG, "phase-sweep rule=" + rule.id + " sample=" + sample.id + " "
                                        + phaseSweep(sample.fingerprint, decoded));
                            }
                        }
                        for (SkipFingerprint.Match match : matches) {
                            boolean inRange = match.startMs >= coveredStart && match.startMs < safeEnd;
                            boolean fitsAudio = match.startMs + sample.durationMs <= actualEnd + 1;
                            if (inRange && fitsAudio) {
                                newHits.add(new SkipMarkerHit(rule.id, sample.id, sample.marker,
                                        match.startMs, match.score));
                                if (BuildConfig.DEBUG && Log.isLoggable(MATCH_TAG, Log.DEBUG)) {
                                    Log.d(MATCH_TAG, "accepted rule=" + rule.id + " sample=" + sample.id
                                            + " marker=" + sample.marker + " start=" + match.startMs
                                            + " score=" + match.score);
                                }
                            } else if (BuildConfig.DEBUG && Log.isLoggable(MATCH_TAG, Log.DEBUG)) {
                                Log.d(MATCH_TAG, "rejected rule=" + rule.id + " sample=" + sample.id
                                        + " marker=" + sample.marker + " start=" + match.startMs
                                        + " score=" + match.score + " reason="
                                        + (!inRange ? "outside-search-range" : "sample-overruns-audio"));
                            }
                        }
                    }
                }
                hits = mergeHits(hits, newHits);
            }
            coverage = mergeCoverage(coverage, Collections.singletonList(new SkipCoverage(coveredStart, safeEnd)));
            return decoded.cacheMiss || coveredStart > startMs;
        }

        private SkipAudioDecoder.DecodedAudio decodeLocalWindow(long startMs, long endMs)
                throws IOException, InterruptedException {
            SkipAudioDecoder.DecodedAudio decoded = decodeSession(startMs, endMs);
            for (int retry = 0; retry < LOCAL_DECODE_RETRIES
                    && (!decoded.complete || decoded.startMs > startMs); retry++) {
                checkCancelled();
                long retryStart = decoded.startMs > startMs
                        ? Math.max(0, startMs - LOCAL_SEEK_FALLBACK_MS) : startMs;
                SkipAudioDecoder.DecodedAudio retryResult;
                try {
                    retryResult = decodeSession(retryStart, endMs);
                } catch (IOException error) {
                    if (decoded.startMs <= startMs && decoded.durationMs > 0) {
                        return decoded;
                    }
                    throw error;
                }
                if (retryResult.complete || retryResult.startMs > startMs
                        || retryResult.durationMs >= decoded.durationMs) {
                    decoded = retryResult;
                }
            }
            return decoded;
        }

        private String formatMatch(SkipFingerprint.Match match) {
            return match == null ? "none" : match.startMs + ":" + match.score;
        }

        private String formatDetails(SkipFingerprint.MatchDetails details) {
            if (details == null) {
                return "frames=none";
            }
            StringBuilder segments = new StringBuilder();
            for (int index = 0; index < details.segmentDistance.length; index++) {
                if (index > 0) {
                    segments.append(',');
                }
                int frames = details.segmentFrames[index];
                float score = frames == 0 ? 0 : 1f - details.segmentDistance[index] / (float) (frames * 32);
                segments.append(score);
            }
            return "activeFrames=" + details.activeFrames + " mismatchingFrames="
                    + details.mismatchingFrames + " distance=" + details.distance + " segmentScores=" + segments;
        }

        private String formatPcm(float[] pcm) {
            double energy = 0;
            float peak = 0;
            int firstAudible = -1;
            for (int index = 0; index < pcm.length; index++) {
                float value = pcm[index];
                energy += value * value;
                peak = Math.max(peak, Math.abs(value));
                if (firstAudible < 0 && Math.abs(value) >= 0.004f) {
                    firstAudible = index;
                }
            }
            double rms = pcm.length == 0 ? 0 : Math.sqrt(energy / pcm.length);
            return "pcmSamples=" + pcm.length + " rms=" + rms + " peak=" + peak
                    + " firstAudibleMs=" + (firstAudible < 0 ? -1 : firstAudible * 1_000L
                    / SkipFingerprint.SAMPLE_RATE);
        }

        private String phaseSweep(AudioFingerprint sample, SkipAudioDecoder.DecodedAudio decoded) {
            StringBuilder result = new StringBuilder();
            for (int phaseMs = 0; phaseMs < SkipFingerprint.HOP_MS; phaseMs += 4) {
                int offset = phaseMs * SkipFingerprint.SAMPLE_RATE / 1_000;
                if (decoded.samples.length - offset < SkipFingerprint.SAMPLE_RATE
                        * SkipFingerprint.FRAME_MS / 1_000) {
                    break;
                }
                float[] shifted = new float[decoded.samples.length - offset];
                System.arraycopy(decoded.samples, offset, shifted, 0, shifted.length);
                SkipFingerprint.Match match = SkipFingerprint.findBestMatch(sample,
                        SkipFingerprint.fromPcm(shifted, SkipFingerprint.SAMPLE_RATE),
                        decoded.startMs + phaseMs);
                if (result.length() > 0) {
                    result.append(',');
                }
                result.append(phaseMs).append('=').append(formatMatch(match));
            }
            return result.toString();
        }

        private SkipAudioDecoder.DecodedAudio decodeStreamingWindow(long startMs, long endMs,
                                                                      boolean allowFetchRetry)
                throws IOException, InterruptedException {
            try {
                return decodeSession(startMs, endMs);
            } catch (SkipStreamingSource.CacheMissException error) {
                if (BuildConfig.DEBUG) {
                    Log.d("SkipManager", "Cache miss source=" + audioUri + " window=" + startMs + ".."
                            + endMs + " retry=" + allowFetchRetry + " error=" + error.getMessage());
                }
                if (!allowFetchRetry || error.getMessage() == null
                        || !error.getMessage().contains("during extractor initialization")) {
                    throw error;
                }
                try {
                    if (BuildConfig.DEBUG) {
                        Log.d("SkipManager", "Retrying cache miss with bounded fetch source=" + audioUri
                                + " window=" + startMs + ".." + endMs);
                    }
                    try (SkipAudioDecoder.Session fetchSession =
                                 SkipAudioDecoder.openSession(context, audioUri, true, true)) {
                        return fetchSession.decode(startMs, endMs);
                    }
                } catch (SkipStreamingSource.UnavailableException fetchError) {
                    fetchError.addSuppressed(error);
                    if (BuildConfig.DEBUG) {
                        Log.d("SkipManager", "Bounded fetch failed source=" + audioUri + " window="
                                + startMs + ".." + endMs, fetchError);
                    }
                    throw fetchError;
                }
            }
        }

        private SkipAudioDecoder.Session decoderSession() throws IOException {
            while (true) {
                SkipAudioDecoder.Session evicted;
                synchronized (SkipManager.this) {
                    if (decoderSession != null) {
                        return decoderSession;
                    }
                    if (decoderSessionOwners.size() < MAX_RETAINED_DECODER_SESSIONS) {
                        decoderSession = SkipAudioDecoder.openSession(context, audioUri, fetchMissing, false);
                        decoderSessionOwners.add(this);
                        return decoderSession;
                    }
                    AnalysisJob owner = null;
                    for (AnalysisJob candidate : decoderSessionOwners) {
                        if (!candidate.running) {
                            owner = candidate;
                            break;
                        }
                    }
                    if (owner == null) {
                        throw new IllegalStateException("No decoder session is available");
                    }
                    evicted = owner.detachDecoderSessionLocked();
                }
                if (evicted != null) {
                    evicted.close();
                }
            }
        }

        private SkipAudioDecoder.DecodedAudio decodeSession(long startMs, long endMs)
                throws IOException, InterruptedException {
            try {
                return decoderSession().decode(startMs, endMs);
            } catch (IOException | InterruptedException | RuntimeException error) {
                closeDecoderSession();
                throw error;
            }
        }

        private void cancelDecoderSession() {
            if (running) {
                return;
            }
            closeDecoderSession();
        }

        private void closeDecoderSession() {
            SkipAudioDecoder.Session session;
            synchronized (SkipManager.this) {
                session = detachDecoderSessionLocked();
            }
            if (session != null) {
                session.close();
            }
        }

        private SkipAudioDecoder.Session detachDecoderSessionLocked() {
            SkipAudioDecoder.Session session = decoderSession;
            if (session != null) {
                decoderSession = null;
                decoderSessionOwners.remove(this);
            }
            return session;
        }

        private void checkCancelled() throws InterruptedException {
            if (task.isCancellationRequested()) {
                throw new InterruptedException();
            }
        }

        private void checkpoint() {
            synchronized (SkipManager.this) {
                if (callback != null || (!cacheableSource(audioUri) && !SkipStreamingSource.isStreaming(audioUri))
                        || jobs.get(key) != this) {
                    return;
                }
                SkipAnalysisCache.Entry entry = new SkipAnalysisCache.Entry(identity, revision, durationMs,
                        coverage, hits);
                if (cacheableSource(audioUri)) {
                    try {
                        analysisCache.write(feedId, episodeId, entry);
                    } catch (IOException ignored) {
                    }
                } else if (SkipStreamingSource.isStreaming(audioUri)) {
                    streamingCheckpoints.put(key, entry);
                }
            }
        }

        private void emit(SkipAnalysisStatus status, String error) {
            if (status != SkipAnalysisStatus.ANALYZING) {
                closeDecoderSession();
            }
            synchronized (SkipManager.this) {
                if (task.isCancellationRequested() || (callback == null && jobs.get(key) != this)) {
                    return;
                }
                Resolution resolution = error == null || status == SkipAnalysisStatus.WAITING_FOR_AUDIO
                        ? resolveAll(rules, hits, durationMs, coverage)
                        : Resolution.EMPTY;
                if (BuildConfig.DEBUG && Log.isLoggable(MATCH_TAG, Log.DEBUG)) {
                    for (SkipOccurrence occurrence : resolution.occurrences) {
                        Log.d(MATCH_TAG, "occurrence rule=" + occurrence.ruleId + " range="
                                + occurrence.startMs + ".." + occurrence.endMs + " score=" + occurrence.score);
                    }
                }
                SkipAnalysisSnapshot snapshot = new SkipAnalysisSnapshot(feedId, episodeId, status, revision,
                        identity, durationMs, coverage, resolution.occurrences, resolution.detections,
                        error, System.currentTimeMillis());
                if (status == SkipAnalysisStatus.READY || status == SkipAnalysisStatus.NO_MATCHES
                        || status == SkipAnalysisStatus.DOWNLOAD_REQUIRED || status == SkipAnalysisStatus.ERROR) {
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

    private static Resolution resolveAll(List<SkipRule> rules, List<SkipMarkerHit> hits,
                                         long durationMs, List<SkipCoverage> coverage) {
        List<SkipOccurrence> occurrences = new ArrayList<>();
        List<SkipDetection> detections = new ArrayList<>();
        if (rules != null) {
            for (SkipRule rule : rules) {
                SkipResolver.Resolution resolution = SkipResolver.resolveResult(rule, hits, durationMs, coverage);
                occurrences.addAll(resolution.occurrences);
                detections.addAll(resolution.detections);
            }
        }
        occurrences.sort(Comparator.comparingLong(item -> item.startMs));
        detections.sort(Comparator.comparingLong((SkipDetection item) -> item.timeMs)
                .thenComparing(item -> item.marker));
        return new Resolution(occurrences, detections);
    }

    private static SkipAnalysisStatus completedStatus(Resolution resolution) {
        return resolution.occurrences.isEmpty() && resolution.detections.isEmpty()
                ? SkipAnalysisStatus.NO_MATCHES : SkipAnalysisStatus.READY;
    }

    static List<SkipCoverage> mergeCoverage(List<SkipCoverage> first, List<SkipCoverage> second) {
        List<SkipCoverage> ranges = new ArrayList<>(first);
        ranges.addAll(second);
        ranges.sort(Comparator.comparingLong(item -> item.startMs));
        List<SkipCoverage> merged = new ArrayList<>();
        for (SkipCoverage range : ranges) {
            if (merged.isEmpty() || range.startMs - merged.get(merged.size() - 1).endMs
                    > COVERAGE_GAP_TOLERANCE_MS) {
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

    private static long coveredDuration(List<SkipCoverage> coverage) {
        long durationMs = 0;
        for (SkipCoverage range : mergeCoverage(coverage, Collections.emptyList())) {
            durationMs += range.endMs - range.startMs;
        }
        return durationMs;
    }

    static long chooseWindowStart(long positionMs, long durationMs, List<SkipCoverage> coverage) {
        long current = Math.max(0, Math.min(positionMs - 5_000, durationMs - WINDOW_MS));
        long upcoming = firstUncovered(current, durationMs, coverage);
        return upcoming < durationMs ? upcoming : firstUncovered(0, durationMs, coverage);
    }

    private static boolean isCoveredFrom(List<SkipCoverage> coverage, long startMs, long endMs) {
        return firstUncovered(startMs, endMs, coverage) >= endMs;
    }

    private static long testEndPosition(SkipRule rule, long positionMs, long durationMs) {
        long longestEndSample = 0;
        for (SkipSample sample : rule.samples) {
            if ((sample.marker == SkipMarker.END && !rule.useStartAsEnd)
                    || (sample.marker == SkipMarker.START && rule.useStartAsEnd)) {
                longestEndSample = Math.max(longestEndSample, sample.durationMs);
            }
        }
        long maximum = rule.maxDurationMs > 0 ? rule.maxDurationMs : 120_000;
        return Math.min(durationMs, positionMs + maximum + longestEndSample);
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
                streamingCheckpoints.remove(key);
                restoringAnalyses.remove(key);
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

    private static boolean cacheableSource(Uri uri) {
        return reusableSource(uri);
    }

    private static String sourceIdentity(Uri uri) {
        if (SkipStreamingSource.isStreaming(uri)) {
            return uri.toString();
        }
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
        validateAudioSource(audioUri);
    }

    private static void validateAudioSource(Uri audioUri) {
        if (audioUri == null || audioUri.getScheme() != null && !"file".equals(audioUri.getScheme())
                && !"content".equals(audioUri.getScheme()) && !SkipStreamingSource.isStreaming(audioUri)) {
            throw new IllegalArgumentException("Analysis requires local or cache-only audio");
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

    private static final class Resolution {
        private static final Resolution EMPTY = new Resolution(Collections.emptyList(), Collections.emptyList());
        private final List<SkipOccurrence> occurrences;
        private final List<SkipDetection> detections;

        private Resolution(List<SkipOccurrence> occurrences, List<SkipDetection> detections) {
            this.occurrences = occurrences;
            this.detections = detections;
        }
    }
}
