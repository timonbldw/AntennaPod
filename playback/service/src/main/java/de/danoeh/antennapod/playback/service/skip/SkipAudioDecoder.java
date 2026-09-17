package de.danoeh.antennapod.playback.service.skip;

import android.content.Context;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Collections;

final class SkipAudioDecoder {
    private static final long CODEC_TIMEOUT_US = 10_000;
    private static final int MAX_IDLE_ITERATIONS = 500;
    private static final long MAX_WINDOW_MS = 95_000;

    private SkipAudioDecoder() {
    }

    static DecodedAudio decode(Context context, Uri uri, long startMs, long endMs)
            throws IOException, InterruptedException {
        if (uri == null || startMs < 0 || endMs <= startMs || endMs - startMs > MAX_WINDOW_MS) {
            throw new IllegalArgumentException("Invalid decode range");
        }
        MediaExtractor extractor = new MediaExtractor();
        SkipStreamingSource.CachedMediaDataSource streamingSource = null;
        MediaCodec codec = null;
        boolean started = false;
        try {
            if (SkipStreamingSource.isStreaming(uri)) {
                streamingSource = SkipStreamingSource.open(uri);
                extractor.setDataSource(streamingSource);
            } else if (uri.getScheme() == null || "file".equals(uri.getScheme())) {
                extractor.setDataSource(uri.getPath());
            } else {
                extractor.setDataSource(context, uri, Collections.emptyMap());
            }
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
                streamingSource.acceptOptionalMp3TailProbe(mime);
            }
            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(inputFormat, null, null, 0);
            codec.start();
            started = true;
            extractor.seekTo(Math.max(0, startMs - 250) * 1_000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            if (streamingSource != null) {
                streamingSource.throwIfUnavailable();
            }
            PcmAccumulator accumulator = new PcmAccumulator(startMs, endMs);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputEnded = false;
            boolean outputEnded = false;
            boolean sourceEnded = false;
            int idleIterations = 0;
            int sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            int encoding = AudioFormat.ENCODING_PCM_16BIT;
            double previousEndUs = -1;
            while (!outputEnded && !accumulator.reachedEnd()) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException();
                }
                if (!inputEnded) {
                    int inputIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US);
                    if (inputIndex >= 0) {
                        ByteBuffer inputBuffer = codec.getInputBuffer(inputIndex);
                        if (inputBuffer == null) {
                            throw new IOException("Decoder input buffer unavailable");
                        }
                        inputBuffer.clear();
                        int size = extractor.readSampleData(inputBuffer, 0);
                        if (streamingSource != null) {
                            streamingSource.throwIfUnavailable();
                        }
                        long timeUs = extractor.getSampleTime();
                        sourceEnded = size < 0 || timeUs < 0;
                        if (sourceEnded || timeUs >= (endMs + 250) * 1_000) {
                            codec.queueInputBuffer(inputIndex, 0, 0, Math.max(0, timeUs),
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputEnded = true;
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, size, timeUs, 0);
                            extractor.advance();
                        }
                    }
                }
                int outputIndex = codec.dequeueOutputBuffer(info, CODEC_TIMEOUT_US);
                if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat format = codec.getOutputFormat();
                    sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    encoding = format.containsKey(MediaFormat.KEY_PCM_ENCODING)
                            ? format.getInteger(MediaFormat.KEY_PCM_ENCODING) : AudioFormat.ENCODING_PCM_16BIT;
                    idleIterations = 0;
                } else if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (++idleIterations > MAX_IDLE_ITERATIONS) {
                        throw new IOException("Audio decoder stalled");
                    }
                } else if (outputIndex >= 0) {
                    idleIterations = 0;
                    try {
                        ByteBuffer buffer = codec.getOutputBuffer(outputIndex);
                        if (info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            if (buffer == null || sampleRate <= 0 || channels <= 0 || channels > 32) {
                                throw new IOException("Invalid decoder PCM output");
                            }
                            int frameBytes = bytesPerSample(encoding) * channels;
                            buffer.limit(info.offset + info.size);
                            buffer.position(info.offset);
                            buffer.order(ByteOrder.LITTLE_ENDIAN);
                            int frames = info.size / frameBytes;
                            double frameUs = 1_000_000.0 / sampleRate;
                            double timeUs = info.presentationTimeUs;
                            if (previousEndUs >= 0 && Math.abs(timeUs - previousEndUs) <= 2_000) {
                                timeUs = previousEndUs;
                            } else if (previousEndUs >= 0 && timeUs < previousEndUs - 2_000) {
                                throw new IOException("Non-monotonic audio timestamps");
                            }
                            for (int frame = 0; frame < frames; frame++) {
                                if ((frame & 1023) == 0 && Thread.currentThread().isInterrupted()) {
                                    throw new InterruptedException();
                                }
                                float sum = 0;
                                for (int channel = 0; channel < channels; channel++) {
                                    sum += readSample(buffer, encoding);
                                }
                                accumulator.append(sum / channels, timeUs + frame * frameUs, frameUs);
                            }
                            previousEndUs = timeUs + frames * frameUs;
                        }
                        outputEnded = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    } finally {
                        codec.releaseOutputBuffer(outputIndex, false);
                    }
                }
            }
            if (streamingSource != null) {
                streamingSource.throwIfUnavailable();
            }
            return accumulator.result(sourceEnded && outputEnded, previousEndUs);
        } catch (IOException | RuntimeException e) {
            if (streamingSource != null) {
                streamingSource.throwIfUnavailable();
            }
            throw e;
        } finally {
            try {
                if (codec != null) {
                    try {
                        if (started) {
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

    private static int findAudioTrack(MediaExtractor extractor) {
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

        DecodedAudio(long startMs, float[] samples, long durationMs, boolean complete) {
            this(startMs, samples, durationMs, complete, false);
        }

        DecodedAudio(long startMs, float[] samples, long durationMs, boolean complete, boolean eof) {
            this.startMs = startMs;
            this.samples = samples;
            this.durationMs = durationMs;
            this.complete = complete;
            this.eof = eof;
        }
    }

    private static final class PcmAccumulator {
        private static final double OUTPUT_US = 1_000_000.0 / SkipFingerprint.SAMPLE_RATE;
        private final float[] values;
        private final long requestedStartMs;
        private final long endMs;
        private long actualStartMs = -1;
        private int size;
        private double binStartUs;
        private double filledUs;
        private double sum;
        private double lastEndUs = -1;

        PcmAccumulator(long startMs, long endMs) {
            requestedStartMs = startMs;
            this.endMs = endMs;
            values = new float[(int) ((endMs - startMs) * SkipFingerprint.SAMPLE_RATE / 1_000) + 1];
        }

        void append(float value, double timeUs, double durationUs) throws IOException {
            double endUs = Math.min(endMs * 1_000.0, timeUs + durationUs);
            double fromUs = Math.max(requestedStartMs * 1_000.0, timeUs);
            if (endUs <= fromUs) {
                return;
            }
            if (actualStartMs < 0) {
                actualStartMs = (long) Math.ceil(fromUs / 1_000.0);
                binStartUs = actualStartMs * 1_000.0;
            }
            fromUs = Math.max(fromUs, binStartUs + filledUs);
            if (endUs <= fromUs) {
                return;
            }
            if (lastEndUs >= 0 && fromUs - lastEndUs > 2_000) {
                throw new IOException("Gap in decoded audio timestamps");
            }
            lastEndUs = endUs;
            while (fromUs < endUs - 0.0001) {
                double amount = Math.min(endUs - fromUs, OUTPUT_US - filledUs);
                sum += value * amount;
                filledUs += amount;
                fromUs += amount;
                if (filledUs >= OUTPUT_US - 0.0001) {
                    values[size++] = (float) (sum / OUTPUT_US);
                    binStartUs += OUTPUT_US;
                    filledUs = 0;
                    sum = 0;
                }
            }
        }

        boolean reachedEnd() {
            return lastEndUs >= endMs * 1_000.0 - 1;
        }

        DecodedAudio result(boolean eof, double decodedEndUs) throws IOException {
            if (size == 0) {
                if (eof && decodedEndUs > 0 && decodedEndUs <= requestedStartMs * 1_000.0) {
                    return new DecodedAudio((long) (decodedEndUs / 1_000), new float[0], 0, true, true);
                }
                throw new IOException("Audio decoder produced no samples in requested window");
            }
            return new DecodedAudio(actualStartMs, Arrays.copyOf(values, size),
                    size * 1_000L / SkipFingerprint.SAMPLE_RATE,
                    reachedEnd() || eof, eof);
        }
    }
}
