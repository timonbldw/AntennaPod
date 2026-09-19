package de.danoeh.antennapod.playback.service.skip;

import android.content.Context;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaDataSource;
import android.media.MediaFormat;
import android.net.Uri;
import android.util.Log;
import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.extractor.mp3.Mp3Extractor;
import androidx.media3.inspector.MediaExtractorCompat;
import de.danoeh.antennapod.playback.service.BuildConfig;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Collections;

@OptIn(markerClass = UnstableApi.class)
final class SkipAudioDecoder {
    private static final String TAG = "SkipAudioDecoder";
    private static final int DIAGNOSTIC_BUFFER_COUNT = 6;
    private static final int MAX_ENCODER_PADDING_SECONDS = 10;
    private static final int MAX_ENCODER_PADDING_VALUES = 1_000_000;
    private static final long CODEC_TIMEOUT_US = 10_000;
    private static final int MAX_IDLE_ITERATIONS = 500;
    private static final long MAX_WINDOW_MS = 95_000;
    private static final long MAX_HISTORY_MS = MAX_WINDOW_MS + 1_000;

    private SkipAudioDecoder() {
    }

    static Session openSession(Context context, Uri uri, boolean fetchMissing, boolean analysisFetch)
            throws IOException {
        if (context == null || uri == null) {
            throw new IllegalArgumentException("Context and URI are required");
        }
        return new Session(startMs -> new CodecDecoder(context, uri, fetchMissing, analysisFetch, false, startMs));
    }

    static DecodedAudio decode(Context context, Uri uri, long startMs, long endMs)
            throws IOException, InterruptedException {
        return decode(context, uri, startMs, endMs, false, false);
    }

    static DecodedAudio decode(Context context, Uri uri, long startMs, long endMs, boolean fetchMissing)
            throws IOException, InterruptedException {
        return decode(context, uri, startMs, endMs, fetchMissing, false);
    }

    static DecodedAudio decodeForAnalysis(Context context, Uri uri, long startMs, long endMs)
            throws IOException, InterruptedException {
        try (Session session = openSession(context, uri, true, true)) {
            return session.decode(startMs, endMs);
        }
    }

    static DecodedAudio decodePreview(Context context, Uri uri, long startMs, long endMs,
                                      boolean fetchMissing) throws IOException, InterruptedException {
        if (context == null || uri == null) {
            throw new IllegalArgumentException("Context and URI are required");
        }
        validateRange(startMs, endMs);
        try (Session session = new Session(windowStartMs ->
                new CodecDecoder(context, uri, fetchMissing, false, true, windowStartMs))) {
            return session.decode(startMs, endMs);
        }
    }

    private static DecodedAudio decode(Context context, Uri uri, long startMs, long endMs,
                                       boolean fetchMissing, boolean analysisFetch)
            throws IOException, InterruptedException {
        try (Session session = openSession(context, uri, fetchMissing, analysisFetch)) {
            return session.decode(startMs, endMs);
        }
    }

    private static void validateRange(long startMs, long endMs) {
        if (startMs < 0 || endMs <= startMs || endMs - startMs > MAX_WINDOW_MS) {
            throw new IllegalArgumentException("Invalid decode range");
        }
    }

    private static void checkInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException();
        }
    }

    static final class ExtractorMediaDataSource extends MediaDataSource {
        private final SkipStreamingSource.CachedMediaDataSource source;

        ExtractorMediaDataSource(SkipStreamingSource.CachedMediaDataSource source) {
            this.source = source;
        }

        @Override
        public int readAt(long position, byte[] buffer, int offset, int size) throws IOException {
            int read = source.readAt(position, buffer, offset, size);
            if (read == 0 && size > 0) {
                source.throwIfCacheMiss();
                throw new IOException("Extractor data source made no progress");
            }
            return read;
        }

        @Override
        public long getSize() throws IOException {
            return source.getSize();
        }

        @Override
        public void close() {
        }
    }

    static final class Session implements AutoCloseable {
        private final DecoderFactory factory;
        private Decoder decoder;
        private boolean closed;

        Session(DecoderFactory factory) {
            this.factory = factory;
        }

        DecodedAudio decode(long startMs, long endMs) throws IOException, InterruptedException {
            validateRange(startMs, endMs);
            if (closed) {
                throw new IllegalStateException("Decoder session is closed");
            }
            try {
                if (decoder == null || !decoder.canDecode(startMs)) {
                    closeDecoder();
                    decoder = factory.create(startMs);
                }
                DecodedAudio result = decoder.decode(startMs, endMs);
                if (result.cacheMiss) {
                    closeDecoder();
                }
                return result;
            } catch (IOException | InterruptedException | RuntimeException error) {
                close();
                throw error;
            }
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                closeDecoder();
            }
        }

        private void closeDecoder() {
            if (decoder != null) {
                decoder.close();
                decoder = null;
            }
        }
    }

    interface DecoderFactory {
        Decoder create(long startMs) throws IOException, InterruptedException;
    }

    interface Decoder extends AutoCloseable {
        boolean canDecode(long startMs);

        DecodedAudio decode(long startMs, long endMs) throws IOException, InterruptedException;

        @Override
        void close();
    }

    private static final class CodecDecoder implements Decoder {
        private final Uri uri;
        private final boolean fetchMissing;
        private final boolean preserveSourceAudio;
        private final MediaExtractorCompat extractor;
        private SkipStreamingSource.CachedMediaDataSource streamingSource;
        private MediaCodec codec;
        private boolean codecStarted;
        private boolean inputEnded;
        private boolean outputEnded;
        private boolean sourceEnded;
        private boolean cacheMissed;
        private boolean cacheMissPending;
        private final long initialStartMs;
        private int sampleRate;
        private int channels;
        private int encoding = AudioFormat.ENCODING_PCM_16BIT;
        private PcmHistory history;
        private int encoderDelayFrames;
        private int encoderPaddingFrames;
        private int remainingEncoderDelayFrames;
        private double encoderDelayUs;
        private TrailingPaddingBuffer trailingPadding;
        private double previousEndUs = -1;
        private int loggedInputBuffers;
        private int outputBuffers;
        private int idleIterations;
        private boolean closed;

        CodecDecoder(Context context, Uri uri, boolean fetchMissing, boolean analysisFetch,
                     boolean preserveSourceAudio, long startMs) throws IOException, InterruptedException {
            this.uri = uri;
            this.fetchMissing = fetchMissing;
            this.preserveSourceAudio = preserveSourceAudio;
            this.initialStartMs = startMs;
            DefaultExtractorsFactory extractorsFactory = new DefaultExtractorsFactory();
            extractorsFactory.setConstantBitrateSeekingEnabled(true);
            extractorsFactory.setMp3ExtractorFlags(Mp3Extractor.FLAG_DISABLE_ID3_METADATA);
            extractor = new MediaExtractorCompat(extractorsFactory, new DefaultDataSource.Factory(context));
            try {
                setDataSource(context, analysisFetch);
                int audioTrack = findAudioTrack(extractor);
                if (audioTrack < 0) {
                    throw new IOException("No audio track");
                }
                extractor.selectTrack(audioTrack);
                MediaFormat inputFormat = extractor.getTrackFormat(audioTrack);
                String mime = inputFormat.getString(MediaFormat.KEY_MIME);
                if (mime == null) {
                    throw new IOException("Audio MIME type is missing");
                }
                if (streamingSource != null) {
                    streamingSource.throwIfInterrupted();
                    streamingSource.acceptOptionalMp3TailProbe(mime);
                    streamingSource.setDecoderPhase("extractor seek");
                }
                sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                validateFormat(sampleRate, channels);
                encoderDelayFrames = inputFormat.containsKey(MediaFormat.KEY_ENCODER_DELAY)
                        ? Math.max(0, inputFormat.getInteger(MediaFormat.KEY_ENCODER_DELAY)) : 0;
                encoderPaddingFrames = Math.max(0, formatInteger(inputFormat, MediaFormat.KEY_ENCODER_PADDING));
                encoderDelayUs = encoderDelayFrames * 1_000_000.0 / sampleRate;
                inputFormat.setInteger(MediaFormat.KEY_ENCODER_DELAY, 0);
                inputFormat.setInteger(MediaFormat.KEY_ENCODER_PADDING, 0);
                history = new PcmHistory(preserveSourceAudio ? sampleRate : SkipFingerprint.SAMPLE_RATE,
                        preserveSourceAudio ? channels : 1);
                codec = MediaCodec.createDecoderByType(mime);
                String codecName = codec.getName();
                codec.configure(inputFormat, null, null, 0);
                codec.start();
                codecStarted = true;
                long seekStartMs = streamingSource != null && !fetchMissing
                        ? startMs : Math.max(0, startMs - 250);
                extractor.seekTo(seekStartMs * 1_000, MediaExtractorCompat.SEEK_TO_PREVIOUS_SYNC);
                checkStreamingSource();
                debug("start codec=" + codecName + " requestMs=" + startMs + " seekMs=" + seekStartMs
                        + " firstInputPtsUs=" + extractor.getSampleTime() + " inputRate=" + sampleRate
                        + " inputChannels=" + channels + " inputDelay=" + encoderDelayFrames
                        + " configuredDelay=" + formatInteger(inputFormat, MediaFormat.KEY_ENCODER_DELAY)
                        + " inputPadding=" + encoderPaddingFrames + " configuredPadding="
                        + formatInteger(inputFormat, MediaFormat.KEY_ENCODER_PADDING));
                remainingEncoderDelayFrames = encoderDelayFramesAtStart(
                        encoderDelayFrames, extractor.getSampleTime());
                if (streamingSource != null) {
                    streamingSource.throwIfCacheMiss();
                    streamingSource.setDecoderPhase("sample reading");
                }
            } catch (IOException | RuntimeException error) {
                close();
                if (streamingSource != null) {
                    streamingSource.throwIfUnavailable();
                    streamingSource.throwIfCacheMiss();
                }
                throw error;
            } catch (InterruptedException error) {
                close();
                throw error;
            }
        }

        private void setDataSource(Context context, boolean analysisFetch) throws IOException {
            if (SkipStreamingSource.isStreaming(uri)) {
                streamingSource = analysisFetch
                        ? SkipStreamingSource.openForAnalysis(uri)
                        : SkipStreamingSource.open(uri, fetchMissing);
                streamingSource.bindToCurrentThread();
                streamingSource.setDecoderPhase("extractor initialization");
                extractor.setDataSource(new ExtractorMediaDataSource(streamingSource));
                return;
            }
            if (uri.getScheme() == null || "file".equals(uri.getScheme())) {
                extractor.setDataSource(uri.getPath());
            } else {
                extractor.setDataSource(context, uri, Collections.emptyMap());
            }
        }

        @Override
        public boolean canDecode(long startMs) {
            return !closed && !cacheMissed && (history.canRead(startMs)
                    || history.endFrame() < 0 && startMs == initialStartMs);
        }

        @Override
        public DecodedAudio decode(long startMs, long endMs) throws IOException, InterruptedException {
            if (streamingSource != null) {
                streamingSource.bindToCurrentThread();
            }
            debug("request startMs=" + startMs + " endMs=" + endMs);
            while (!outputEnded && !history.reached(endMs)) {
                checkInterrupted();
                boolean queuedInput = queueInput(endMs);
                boolean producedOutput = drainOutput();
                if (producedOutput || queuedInput) {
                    idleIterations = 0;
                } else if (++idleIterations > MAX_IDLE_ITERATIONS) {
                    throw new IOException("Audio decoder stalled");
                }
            }
            checkStreamingSource();
            if (cacheMissed && !history.hasSamples(startMs) && streamingSource != null) {
                streamingSource.throwIfCacheMiss();
            }
            DecodedAudio result = history.result(startMs, endMs, sourceEnded && outputEnded, cacheMissed);
            if (cacheMissed) {
                close();
            }
            return result;
        }

        private boolean queueInput(long endMs) throws IOException, InterruptedException {
            if (inputEnded) {
                return false;
            }
            int inputIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US);
            if (inputIndex < 0) {
                return false;
            }
            if (cacheMissPending) {
                long timeUs = previousEndUs < 0 ? startTimeUs() : (long) previousEndUs;
                logInputBuffer(timeUs, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                codec.queueInputBuffer(inputIndex, 0, 0, timeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                inputEnded = true;
                cacheMissPending = false;
                return true;
            }
            ByteBuffer inputBuffer = codec.getInputBuffer(inputIndex);
            if (inputBuffer == null) {
                throw new IOException("Decoder input buffer unavailable");
            }
            inputBuffer.clear();
            boolean inputQueued = false;
            try {
                int size = extractor.readSampleData(inputBuffer, 0);
                checkStreamingSource();
                long timeUs = extractor.getSampleTime();
                if (streamingSource != null && !fetchMissing && streamingSource.isCacheMiss()) {
                    cacheMissed = true;
                    if (size < 0 || timeUs < 0) {
                        long endTimeUs = previousEndUs < 0 ? startTimeUs() : (long) previousEndUs;
                        logInputBuffer(endTimeUs, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        codec.queueInputBuffer(inputIndex, 0, 0, endTimeUs,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputEnded = true;
                        return true;
                    }
                    cacheMissPending = true;
                }
                sourceEnded = size < 0 || timeUs < 0;
                if (sourceEnded) {
                    long endTimeUs = previousEndUs < 0 ? 0 : (long) previousEndUs;
                    logInputBuffer(endTimeUs, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    codec.queueInputBuffer(inputIndex, 0, 0, endTimeUs,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    inputEnded = true;
                    return true;
                }
                logInputBuffer(timeUs, size, 0);
                codec.queueInputBuffer(inputIndex, 0, size, timeUs, 0);
                inputQueued = true;
                extractor.advance();
                checkStreamingSource();
                if (streamingSource != null && !fetchMissing && streamingSource.isCacheMiss()) {
                    cacheMissed = true;
                    cacheMissPending = true;
                }
                return true;
            } catch (IOException | RuntimeException error) {
                if (streamingSource == null || fetchMissing || !streamingSource.isCacheMiss()) {
                    throw error;
                }
                cacheMissed = true;
                if (inputQueued) {
                    cacheMissPending = true;
                    return true;
                }
                long timeUs = previousEndUs < 0 ? startTimeUs() : (long) previousEndUs;
                logInputBuffer(timeUs, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                codec.queueInputBuffer(inputIndex, 0, 0, timeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                inputEnded = true;
                return true;
            }
        }

        private boolean drainOutput() throws IOException, InterruptedException {
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int outputIndex = codec.dequeueOutputBuffer(info, CODEC_TIMEOUT_US);
            if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat format = codec.getOutputFormat();
                int outputSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                int outputChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                validateFormat(outputSampleRate, outputChannels);
                int historyChannels = preserveSourceAudio ? outputChannels : 1;
                if (trailingPadding != null) {
                    trailingPadding.setFormat(outputSampleRate, historyChannels);
                }
                if (preserveSourceAudio) {
                    history.setFormat(outputSampleRate, outputChannels);
                }
                sampleRate = outputSampleRate;
                channels = outputChannels;
                encoding = format.containsKey(MediaFormat.KEY_PCM_ENCODING)
                        ? format.getInteger(MediaFormat.KEY_PCM_ENCODING) : AudioFormat.ENCODING_PCM_16BIT;
                debug("output format rate=" + sampleRate + " channels=" + channels + " encoding=" + encoding
                        + " delay=" + formatInteger(format, MediaFormat.KEY_ENCODER_DELAY)
                        + " padding=" + formatInteger(format, MediaFormat.KEY_ENCODER_PADDING));
                return true;
            }
            if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                return false;
            }
            if (outputIndex < 0) {
                return true;
            }
            try {
                ByteBuffer buffer = codec.getOutputBuffer(outputIndex);
                if (info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                    if (buffer == null) {
                        throw new IOException("Decoder output buffer unavailable");
                    }
                    int frameBytes = bytesPerSample(encoding) * channels;
                    if (info.size % frameBytes != 0) {
                        throw new IOException("Invalid decoder PCM output");
                    }
                    buffer.limit(info.offset + info.size);
                    buffer.position(info.offset);
                    buffer.order(ByteOrder.LITTLE_ENDIAN);
                    int frames = info.size / frameBytes;
                    double frameUs = 1_000_000.0 / sampleRate;
                    int skippedFrames = Math.min(frames, remainingEncoderDelayFrames);
                    buffer.position(info.offset + skippedFrames * frameBytes);
                    remainingEncoderDelayFrames -= skippedFrames;
                    double mappedTimeUs = outputTimeUs(
                            info.presentationTimeUs, skippedFrames, sampleRate, encoderDelayUs);
                    double deltaUs = previousEndUs < 0 ? Double.NaN : mappedTimeUs - previousEndUs;
                    int outputSequence = outputBuffers++;
                    if (BuildConfig.DEBUG && (outputSequence < DIAGNOSTIC_BUFFER_COUNT
                            || previousEndUs >= 0 && Math.abs(deltaUs) > 2_000)) {
                        Log.d(TAG, "output sequence=" + outputSequence + " rawPtsUs=" + info.presentationTimeUs
                                + " mappedPtsUs=" + mappedTimeUs + " frames=" + frames
                                + " skippedFrames=" + skippedFrames + " previousEndUs=" + previousEndUs
                                + " deltaUs=" + deltaUs + " size=" + info.size + " flags=" + info.flags);
                    }
                    double timeUs = alignTimestamp(previousEndUs, mappedTimeUs);
                    float[] frameSamples = new float[preserveSourceAudio ? channels : 1];
                    if (trailingPadding == null) {
                        trailingPadding = new TrailingPaddingBuffer(
                                history, encoderPaddingFrames, sampleRate);
                    }
                    for (int frame = skippedFrames; frame < frames; frame++) {
                        if ((frame & 1023) == 0) {
                            checkInterrupted();
                        }
                        float sum = 0;
                        for (int channel = 0; channel < channels; channel++) {
                            float sample = readSample(buffer, encoding);
                            if (preserveSourceAudio) {
                                frameSamples[channel] = sample;
                            }
                            sum += sample;
                        }
                        if (!preserveSourceAudio) {
                            frameSamples[0] = sum / channels;
                        }
                        trailingPadding.append(frameSamples,
                                timeUs + (frame - skippedFrames) * frameUs, frameUs);
                    }
                    if (frames > skippedFrames) {
                        previousEndUs = timeUs + (frames - skippedFrames) * frameUs;
                    }
                }
                outputEnded = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                if (outputEnded && trailingPadding != null) {
                    trailingPadding.finish(sourceEnded);
                }
                return true;
            } finally {
                codec.releaseOutputBuffer(outputIndex, false);
            }
        }

        private long startTimeUs() {
            return Math.max(0, history.endFrame()) * 1_000_000L / history.sampleRate;
        }

        private void logInputBuffer(long timeUs, int size, int flags) {
            if (BuildConfig.DEBUG && loggedInputBuffers < DIAGNOSTIC_BUFFER_COUNT) {
                Log.d(TAG, "input sequence=" + loggedInputBuffers + " ptsUs=" + timeUs
                        + " size=" + size + " flags=" + flags);
            }
            loggedInputBuffers++;
        }

        private void checkStreamingSource() throws IOException, InterruptedException {
            if (streamingSource != null) {
                streamingSource.throwIfInterrupted();
                streamingSource.throwIfUnavailable();
                if (fetchMissing) {
                    streamingSource.throwIfCacheMiss();
                }
            }
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            try {
                if (codec != null) {
                    try {
                        if (codecStarted) {
                            codec.stop();
                        }
                    } finally {
                        codec.release();
                    }
                }
            } finally {
                try {
                    extractor.release();
                } finally {
                    if (streamingSource != null) {
                        streamingSource.close();
                    }
                }
            }
        }
    }

    private static void validateFormat(int sampleRate, int channels) throws IOException {
        if (sampleRate <= 0 || channels <= 0 || channels > 32) {
            throw new IOException("Invalid decoder PCM output format");
        }
    }

    private static int formatInteger(MediaFormat format, String key) {
        return format.containsKey(key) ? format.getInteger(key) : -1;
    }

    private static void debug(String message) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, message);
        }
    }

    static double alignTimestamp(double previousEndUs, double timeUs) throws IOException {
        if (previousEndUs < 0) {
            return timeUs;
        }
        double deltaUs = timeUs - previousEndUs;
        if (Math.abs(deltaUs) <= 2_000) {
            return previousEndUs;
        }
        if (deltaUs < 0) {
            throw new IOException("Non-monotonic audio timestamps: expectedUs=" + previousEndUs
                    + " actualUs=" + timeUs + " deltaUs=" + deltaUs);
        }
        throw new IOException("Gap in decoded audio timestamps: expectedUs=" + previousEndUs
                + " actualUs=" + timeUs + " deltaUs=" + deltaUs);
    }

    static int encoderDelayFramesAtStart(int encoderDelayFrames, long firstSampleTimeUs) {
        return firstSampleTimeUs == 0 ? encoderDelayFrames : 0;
    }

    static double outputTimeUs(long presentationTimeUs, int skippedFrames,
                               int sampleRate, double encoderDelayUs) {
        return presentationTimeUs + skippedFrames * 1_000_000.0 / sampleRate - encoderDelayUs;
    }

    private static int findAudioTrack(MediaExtractorCompat extractor) {
        for (int index = 0; index < extractor.getTrackCount(); index++) {
            String mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) {
                return index;
            }
        }
        return -1;
    }

    private static int bytesPerSample(int encoding) throws IOException {
        switch (encoding) {
            case AudioFormat.ENCODING_PCM_8BIT:
                return 1;
            case AudioFormat.ENCODING_PCM_16BIT:
                return 2;
            case 21:
                return 3;
            case AudioFormat.ENCODING_PCM_FLOAT:
            case 22:
                return 4;
            default:
                throw new IOException("Unsupported PCM encoding: " + encoding);
        }
    }

    private static float readSample(ByteBuffer data, int encoding) {
        switch (encoding) {
            case AudioFormat.ENCODING_PCM_FLOAT:
                float value = data.getFloat();
                return Float.isNaN(value) || Float.isInfinite(value) ? 0 : value;
            case AudioFormat.ENCODING_PCM_8BIT:
                return ((data.get() & 0xff) - 128) / 128f;
            case 21:
                int packed = (data.get() & 0xff) | ((data.get() & 0xff) << 8) | (data.get() << 16);
                return packed / 8_388_608f;
            case 22:
                return data.getInt() / 2_147_483_648f;
            default:
                return data.getShort() / 32_768f;
        }
    }

    static final class DecodedAudio {
        final long startMs;
        final float[] samples;
        final long durationMs;
        final boolean complete;
        final boolean eof;
        final boolean cacheMiss;
        final int sampleRate;
        final int channels;

        DecodedAudio(long startMs, float[] samples, long durationMs, boolean complete) {
            this(startMs, samples, durationMs, complete, false);
        }

        DecodedAudio(long startMs, float[] samples, long durationMs, boolean complete, boolean eof) {
            this(startMs, samples, durationMs, complete, eof, SkipFingerprint.SAMPLE_RATE, 1);
        }

        DecodedAudio(long startMs, float[] samples, long durationMs, boolean complete, boolean eof,
                     int sampleRate, int channels) {
            this(startMs, samples, durationMs, complete, eof, false, sampleRate, channels);
        }

        private DecodedAudio(long startMs, float[] samples, long durationMs, boolean complete, boolean eof,
                             boolean cacheMiss, int sampleRate, int channels) {
            this.startMs = startMs;
            this.samples = samples;
            this.durationMs = durationMs;
            this.complete = complete;
            this.eof = eof;
            this.cacheMiss = cacheMiss;
            this.sampleRate = sampleRate;
            this.channels = channels;
        }

        DecodedAudio withCacheMiss() {
            return new DecodedAudio(startMs, samples, durationMs, complete, eof, true, sampleRate, channels);
        }
    }

    static final class PcmHistory {
        private int sampleRate;
        private int channels;
        private float[] values;
        private int firstSlot;
        private int frameCount;
        private long firstFrame;
        private long nextFrame = Long.MIN_VALUE;
        private double binStartUs;
        private double filledUs;
        private double[] sums;
        private double previousInputEndUs = -1;

        PcmHistory(int sampleRate, int channels) {
            setInitialFormat(sampleRate, channels);
        }

        void setFormat(int sampleRate, int channels) throws IOException {
            if (frameCount > 0 && (this.sampleRate != sampleRate || this.channels != channels)) {
                throw new IOException("Audio decoder output format changed");
            }
            if (this.sampleRate != sampleRate || this.channels != channels) {
                setInitialFormat(sampleRate, channels);
            }
        }

        private void setInitialFormat(int sampleRate, int channels) {
            this.sampleRate = sampleRate;
            this.channels = channels;
            int capacityFrames = (int) (MAX_HISTORY_MS * sampleRate / 1_000) + 1;
            values = new float[capacityFrames * channels];
            sums = new double[channels];
            firstSlot = 0;
            frameCount = 0;
            nextFrame = Long.MIN_VALUE;
            previousInputEndUs = -1;
            filledUs = 0;
            Arrays.fill(sums, 0);
        }

        void append(float[] frame, double timeUs, double durationUs) throws IOException {
            appendFrame(frame, 0, timeUs, durationUs);
        }

        private void appendFrame(float[] frames, int frame, double timeUs, double durationUs) throws IOException {
            if (previousInputEndUs >= 0) {
                timeUs = alignTimestamp(previousInputEndUs, timeUs);
            }
            previousInputEndUs = timeUs + durationUs;
            if (nextFrame == Long.MIN_VALUE) {
                nextFrame = (long) Math.ceil(timeUs * sampleRate / 1_000_000.0 - 0.0000001);
                binStartUs = nextFrame * 1_000_000.0 / sampleRate;
            }
            double fromUs = Math.max(timeUs, binStartUs + filledUs);
            double endUs = timeUs + durationUs;
            double outputUs = 1_000_000.0 / sampleRate;
            while (fromUs < endUs - 0.0001) {
                double amount = Math.min(endUs - fromUs, outputUs - filledUs);
                for (int channel = 0; channel < channels; channel++) {
                    sums[channel] += frames[frame * channels + channel] * amount;
                }
                filledUs += amount;
                fromUs += amount;
                if (filledUs >= outputUs - 0.0001) {
                    add(sums, outputUs, nextFrame++);
                    binStartUs += outputUs;
                    filledUs = 0;
                    Arrays.fill(sums, 0);
                }
            }
        }

        private void add(double[] frame, double outputUs, long frameIndex) {
            int capacity = values.length / channels;
            if (frameCount == 0) {
                firstFrame = frameIndex;
            }
            int slot;
            if (frameCount == capacity) {
                slot = firstSlot;
                firstSlot = (firstSlot + 1) % capacity;
                firstFrame++;
            } else {
                slot = (firstSlot + frameCount) % capacity;
                frameCount++;
            }
            for (int channel = 0; channel < channels; channel++) {
                values[slot * channels + channel] = (float) (frame[channel] / outputUs);
            }
        }

        boolean canRead(long startMs) {
            if (frameCount == 0) {
                return false;
            }
            long requested = ceilFrame(startMs);
            return requested >= firstFrame && requested <= endFrame();
        }

        boolean reached(long endMs) {
            return frameCount > 0 && endFrame() >= ceilFrame(endMs);
        }

        long endFrame() {
            return frameCount == 0 ? -1 : firstFrame + frameCount;
        }

        boolean hasSamples(long startMs) {
            return frameCount > 0 && endFrame() > ceilFrame(startMs);
        }

        DecodedAudio result(long startMs, long endMs, boolean eof, boolean cacheMiss) throws IOException {
            long requestedStart = ceilFrame(startMs);
            long requestedEnd = ceilFrame(endMs);
            if (frameCount == 0 || endFrame() <= requestedStart) {
                if (cacheMiss) {
                    throw new SkipStreamingSource.CacheMissException(
                            "Audio cache miss occurred before requested audio coverage");
                }
                if (eof) {
                    return new DecodedAudio(startMs, new float[0], 0, true, true,
                            sampleRate, channels);
                }
                throw new IOException("Audio decoder produced no samples in requested window");
            }
            long availableStart = Math.max(requestedStart, firstFrame);
            long anchoredStartMs = availableStart == requestedStart ? startMs
                    : (availableStart * 1_000 + sampleRate - 1) / sampleRate;
            long availableEnd = Math.min(requestedEnd, endFrame());
            int frames = (int) Math.max(0, availableEnd - availableStart);
            float[] result = new float[frames * channels];
            int capacity = values.length / channels;
            for (int frame = 0; frame < frames; frame++) {
                int slot = (int) ((firstSlot + availableStart - firstFrame + frame) % capacity);
                System.arraycopy(values, slot * channels, result, frame * channels, channels);
            }
            boolean hasBeginning = availableStart == requestedStart;
            boolean complete = hasBeginning && (availableEnd >= requestedEnd || eof) && !cacheMiss;
            long availableEndMs = (availableEnd * 1_000 + sampleRate - 1) / sampleRate;
            DecodedAudio audio = new DecodedAudio(anchoredStartMs, result,
                    Math.max(0, availableEndMs - anchoredStartMs), complete, eof, sampleRate, channels);
            return cacheMiss ? audio.withCacheMiss() : audio;
        }

        private long ceilFrame(long timeMs) {
            return (timeMs * sampleRate + 999) / 1_000;
        }
    }

    static final class TrailingPaddingBuffer {
        private final PcmHistory history;
        private final int capacityFrames;
        private float[] values;
        private int sampleRate;
        private int channels;
        private int firstFrame;
        private int frameCount;
        private double firstTimeUs;
        private double frameUs;
        private double previousInputEndUs = -1;

        TrailingPaddingBuffer(PcmHistory history, int paddingFrames, int sampleRate) throws IOException {
            this.history = history;
            capacityFrames = paddingFrames;
            setFormat(sampleRate, history.channels);
        }

        void setFormat(int sampleRate, int channels) throws IOException {
            validatePaddingCapacity(capacityFrames, sampleRate, channels);
            if (frameCount > 0 && (this.sampleRate != sampleRate || this.channels != channels)) {
                throw new IOException("Audio decoder output format changed with retained encoder padding");
            }
            if (this.sampleRate != sampleRate || this.channels != channels) {
                this.sampleRate = sampleRate;
                this.channels = channels;
                values = new float[capacityFrames * channels];
            }
        }

        void append(float[] frame, double timeUs, double durationUs) throws IOException {
            if (previousInputEndUs >= 0) {
                timeUs = alignTimestamp(previousInputEndUs, timeUs);
            }
            previousInputEndUs = timeUs + durationUs;
            if (capacityFrames == 0) {
                history.append(frame, timeUs, durationUs);
                return;
            }
            if (frameCount == capacityFrames) {
                history.appendFrame(values, firstFrame, firstTimeUs, this.frameUs);
                firstFrame = (firstFrame + 1) % capacityFrames;
                firstTimeUs += this.frameUs;
                frameCount--;
            }
            int slot = (firstFrame + frameCount) % capacityFrames;
            System.arraycopy(frame, 0, values, slot * channels, channels);
            if (frameCount == 0) {
                firstTimeUs = timeUs;
                this.frameUs = durationUs;
            }
            frameCount++;
        }

        void finish(boolean trim) throws IOException {
            if (!trim) {
                while (frameCount > 0) {
                    history.appendFrame(values, firstFrame, firstTimeUs, frameUs);
                    firstFrame = (firstFrame + 1) % capacityFrames;
                    firstTimeUs += frameUs;
                    frameCount--;
                }
            }
            frameCount = 0;
        }

        int retainedFrames() {
            return frameCount;
        }

        private static void validatePaddingCapacity(int paddingFrames, int sampleRate, int channels)
                throws IOException {
            long values = (long) paddingFrames * channels;
            if (paddingFrames < 0 || paddingFrames > (long) sampleRate * MAX_ENCODER_PADDING_SECONDS
                    || values > MAX_ENCODER_PADDING_VALUES) {
                throw new IOException("Invalid encoder padding: frames=" + paddingFrames
                        + " sampleRate=" + sampleRate + " channels=" + channels);
            }
        }
    }
}
