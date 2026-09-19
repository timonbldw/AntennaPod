package de.danoeh.antennapod.playback.service.skip;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class SkipAudioDecoderTest {
    @Test
    public void pcmOutputIsInvariantAcrossInputBatchPartitions() throws IOException {
        SkipAudioDecoder.PcmHistory whole = new SkipAudioDecoder.PcmHistory(8_000, 1);
        SkipAudioDecoder.PcmHistory partitioned = new SkipAudioDecoder.PcmHistory(8_000, 1);
        whole.append(new float[] {0.25f}, 0, 5_000_000);
        for (int batch = 0; batch < 317; batch++) {
            double startUs = batch * 5_000_000.0 / 317;
            double endUs = (batch + 1) * 5_000_000.0 / 317;
            partitioned.append(new float[] {0.25f}, startUs, endUs - startUs);
        }

        SkipAudioDecoder.DecodedAudio wholeResult = whole.result(0, 5_000, false, false);
        SkipAudioDecoder.DecodedAudio partitionedResult = partitioned.result(0, 5_000, false, false);

        assertEquals(0, wholeResult.startMs);
        assertEquals(5_000, wholeResult.durationMs);
        assertEquals(40_000, wholeResult.samples.length);
        assertArrayEquals(wholeResult.samples, partitionedResult.samples, 0.000001f);
    }

    @Test
    public void overlappingWindowsReuseOneDecoderAndReturnIdenticalOverlap() throws Exception {
        AtomicInteger creations = new AtomicInteger();
        FakeDecoder decoder = new FakeDecoder();
        try (SkipAudioDecoder.Session session = new SkipAudioDecoder.Session(startMs -> {
            creations.incrementAndGet();
            return decoder;
        })) {
            SkipAudioDecoder.DecodedAudio first = session.decode(0, 60_000);
            SkipAudioDecoder.DecodedAudio second = session.decode(30_000, 90_000);

            assertEquals(1, creations.get());
            assertEquals(first.samples[30_000 * 8], second.samples[0], 0f);
            assertEquals(first.samples[59_999 * 8], second.samples[29_999 * 8], 0f);
        }
        assertEquals(1, decoder.closeCount);
    }

    @Test
    public void backwardRequestOutsideHistoryRestartsDecoder() throws Exception {
        AtomicInteger creations = new AtomicInteger();
        List<FakeDecoder> decoders = new ArrayList<>();
        try (SkipAudioDecoder.Session session = new SkipAudioDecoder.Session(startMs -> {
            creations.incrementAndGet();
            FakeDecoder decoder = new FakeDecoder();
            decoders.add(decoder);
            return decoder;
        })) {
            session.decode(0, 95_000);
            session.decode(95_000, 190_000);
            session.decode(0, 1_000);
        }

        assertEquals(2, creations.get());
        assertEquals(1, decoders.get(0).closeCount);
        assertEquals(1, decoders.get(1).closeCount);
    }

    @Test
    public void eofReturnsOnlyVerifiedSamples() throws IOException {
        SkipAudioDecoder.PcmHistory history = new SkipAudioDecoder.PcmHistory(8_000, 1);
        append(history, 8_000, 1_000, 1_500, Integer.MAX_VALUE);

        SkipAudioDecoder.DecodedAudio result = history.result(1_000, 2_000, true, false);

        assertEquals(1_000, result.startMs);
        assertEquals(500, result.durationMs);
        assertEquals(4_000, result.samples.length);
        assertTrue(result.complete);
        assertTrue(result.eof);
    }

    @Test
    public void missingBeginningIsNotPadded() throws IOException {
        SkipAudioDecoder.PcmHistory history = new SkipAudioDecoder.PcmHistory(8_000, 1);
        append(history, 8_000, 1_056, 2_000, Integer.MAX_VALUE);

        SkipAudioDecoder.DecodedAudio result = history.result(1_000, 2_000, false, false);

        assertEquals(1_056, result.startMs);
        assertEquals(944, result.durationMs);
        assertFalse(result.complete);
    }

    @Test
    public void nativeRateKeepsRequestedMillisecondAnchor() throws IOException {
        SkipAudioDecoder.PcmHistory history = new SkipAudioDecoder.PcmHistory(44_100, 2);
        double frameUs = 1_000_000.0 / 44_100;
        for (int frame = 0; frame < 45; frame++) {
            history.append(new float[] {0.25f, -0.25f}, 1_001_000 + frame * frameUs, frameUs);
        }

        SkipAudioDecoder.DecodedAudio result = history.result(1_001, 1_002, false, false);

        assertEquals(1_001, result.startMs);
        assertTrue(result.complete);
        assertEquals(88, result.samples.length);
    }

    @Test
    public void cacheMissAfterPrerollIsRecoverable() throws IOException {
        SkipAudioDecoder.PcmHistory history = new SkipAudioDecoder.PcmHistory(8_000, 1);
        append(history, 8_000, 900, 1_000, Integer.MAX_VALUE);

        assertThrows(SkipStreamingSource.CacheMissException.class,
                () -> history.result(1_000, 2_000, false, true));
    }

    @Test
    public void timestampDiscontinuitiesAreRejectedWithoutSilence() throws IOException {
        SkipAudioDecoder.PcmHistory history = new SkipAudioDecoder.PcmHistory(8_000, 1);
        history.append(new float[] {0.25f}, 1_000_000, 125);

        assertThrows(IOException.class,
                () -> history.append(new float[] {0.25f}, 1_003_000, 125));
        assertEquals(1_000_000, SkipAudioDecoder.alignTimestamp(1_000_000, 999_000), 0);
        assertThrows(IOException.class,
                () -> SkipAudioDecoder.alignTimestamp(1_000_000, 997_999));
    }

    @Test
    public void continuousAndSeekDecodeMapSamePassageToSameTime() throws IOException {
        int delayFrames = 57;
        int sampleRate = 1_000;
        double delayUs = delayFrames * 1_000_000.0 / sampleRate;
        SkipAudioDecoder.PcmHistory continuous = new SkipAudioDecoder.PcmHistory(sampleRate, 1);
        SkipAudioDecoder.PcmHistory seek = new SkipAudioDecoder.PcmHistory(sampleRate, 1);
        for (int rawFrame = delayFrames; rawFrame < delayFrames + 200; rawFrame++) {
            double mappedTimeUs = SkipAudioDecoder.outputTimeUs(
                    rawFrame * 1_000L, 0, sampleRate, delayUs);
            continuous.append(new float[] {(float) (mappedTimeUs / 1_000_000)}, mappedTimeUs, 1_000);
        }
        for (int rawFrame = delayFrames + 100; rawFrame < delayFrames + 110; rawFrame++) {
            double mappedTimeUs = SkipAudioDecoder.outputTimeUs(
                    rawFrame * 1_000L, 0, sampleRate, delayUs);
            seek.append(new float[] {(float) (mappedTimeUs / 1_000_000)}, mappedTimeUs, 1_000);
        }

        SkipAudioDecoder.DecodedAudio continuousPassage = continuous.result(100, 110, false, false);
        SkipAudioDecoder.DecodedAudio seekPassage = seek.result(100, 110, false, false);

        assertEquals(100, continuousPassage.startMs);
        assertEquals(continuousPassage.startMs, seekPassage.startMs);
        assertEquals(continuousPassage.durationMs, seekPassage.durationMs);
        assertArrayEquals(continuousPassage.samples, seekPassage.samples, 0);
    }

    @Test
    public void nativePcmClippingDoesNotCreateUnverifiedSilence() throws IOException {
        SkipAudioDecoder.PcmHistory history = new SkipAudioDecoder.PcmHistory(44_100, 1);
        double frameUs = 1_000_000.0 / 44_100;
        for (int frame = 0; frame < 45; frame++) {
            history.append(new float[] {0.5f}, 1_001_030 + frame * frameUs, frameUs);
        }

        SkipAudioDecoder.DecodedAudio result = history.result(1_001, 1_002, false, false);

        assertFalse(result.complete);
        assertTrue(result.startMs > 1_001);
        for (float sample : result.samples) {
            assertEquals(0.5f, sample, 0.000001f);
        }
    }

    @Test
    public void cacheMissRetainsDecodedRangeAndDoesNotBecomeEof() {
        SkipAudioDecoder.DecodedAudio decoded = new SkipAudioDecoder.DecodedAudio(
                1_000, new float[] {0.25f}, 1, false, false);

        SkipAudioDecoder.DecodedAudio result = decoded.withCacheMiss();

        assertEquals(1_000, result.startMs);
        assertEquals(1, result.samples.length);
        assertFalse(result.complete);
        assertFalse(result.eof);
        assertTrue(result.cacheMiss);
    }

    @Test
    public void encoderPaddingIsRetainedAcrossOutputBuffersAndTrimmedAtEof() throws IOException {
        SkipAudioDecoder.PcmHistory history = new SkipAudioDecoder.PcmHistory(32_000, 2);
        SkipAudioDecoder.TrailingPaddingBuffer padding =
                new SkipAudioDecoder.TrailingPaddingBuffer(history, 1_382, 32_000);

        append(padding, 623, 0, 32_000);
        append(padding, 1_152, 623, 32_000);

        assertEquals(393, history.endFrame());
        assertEquals(1_382, padding.retainedFrames());
        padding.finish(true);
        assertEquals(393, history.endFrame());
    }

    @Test
    public void encoderPaddingIsReleasedWhenDrainIsNotTrueEof() throws IOException {
        SkipAudioDecoder.PcmHistory history = new SkipAudioDecoder.PcmHistory(32_000, 2);
        SkipAudioDecoder.TrailingPaddingBuffer padding =
                new SkipAudioDecoder.TrailingPaddingBuffer(history, 1_382, 32_000);

        append(padding, 623, 0, 32_000);
        append(padding, 1_152, 623, 32_000);
        padding.finish(false);

        assertEquals(1_775, history.endFrame());
        assertEquals(0, padding.retainedFrames());
    }

    @Test
    public void oversizedEncoderPaddingIsRejectedBeforeAllocation() {
        SkipAudioDecoder.PcmHistory history = new SkipAudioDecoder.PcmHistory(32_000, 2);

        assertThrows(IOException.class,
                () -> new SkipAudioDecoder.TrailingPaddingBuffer(history, 320_001, 32_000));
        assertThrows(IOException.class,
                () -> new SkipAudioDecoder.TrailingPaddingBuffer(history, Integer.MAX_VALUE,
                        Integer.MAX_VALUE));
    }

    @Test
    public void outputFormatChangeWithRetainedPaddingIsRejected() throws IOException {
        SkipAudioDecoder.PcmHistory history = new SkipAudioDecoder.PcmHistory(32_000, 2);
        SkipAudioDecoder.TrailingPaddingBuffer padding =
                new SkipAudioDecoder.TrailingPaddingBuffer(history, 1_382, 32_000);
        padding.append(new float[] {0.25f, -0.25f}, 0, 31.25);

        assertThrows(IOException.class, () -> padding.setFormat(44_100, 2));
        assertThrows(IOException.class, () -> padding.setFormat(32_000, 1));
    }

    @Test
    public void errorsCloseDecoderAndSession() throws Exception {
        FakeDecoder decoder = new FakeDecoder();
        decoder.error = new IOException("failed");
        SkipAudioDecoder.Session session = new SkipAudioDecoder.Session(startMs -> decoder);

        assertThrows(IOException.class, () -> session.decode(0, 1_000));
        assertEquals(1, decoder.closeCount);
        assertThrows(IllegalStateException.class, () -> session.decode(0, 1_000));
        session.close();
        assertEquals(1, decoder.closeCount);
    }

    private static void append(SkipAudioDecoder.PcmHistory history, int sourceRate,
                               long startMs, long endMs, int batchFrames) throws IOException {
        double frameUs = 1_000_000.0 / sourceRate;
        long frame = startMs * sourceRate / 1_000;
        long endFrame = endMs * sourceRate / 1_000;
        while (frame < endFrame) {
            long batchEnd = Math.min(endFrame, frame + batchFrames);
            while (frame < batchEnd) {
                history.append(new float[] {0.25f}, frame * frameUs, frameUs);
                frame++;
            }
        }
    }

    private static void append(SkipAudioDecoder.TrailingPaddingBuffer padding, int frameCount,
                               int firstFrame, int sampleRate) throws IOException {
        double frameUs = 1_000_000.0 / sampleRate;
        for (int frame = firstFrame; frame < firstFrame + frameCount; frame++) {
            padding.append(new float[] {frame, -frame}, frame * frameUs, frameUs);
        }
    }

    private static final class FakeDecoder implements SkipAudioDecoder.Decoder {
        private final SkipAudioDecoder.PcmHistory history = new SkipAudioDecoder.PcmHistory(8_000, 1);
        private long generatedEndMs;
        private int closeCount;
        private IOException error;

        @Override
        public boolean canDecode(long startMs) {
            return startMs >= Math.max(0, generatedEndMs - 95_000) && startMs <= generatedEndMs;
        }

        @Override
        public SkipAudioDecoder.DecodedAudio decode(long startMs, long endMs) throws IOException {
            if (error != null) {
                throw error;
            }
            for (long sample = generatedEndMs * 8; sample < endMs * 8; sample++) {
                history.append(new float[] {sample}, sample * 125.0, 125);
            }
            generatedEndMs = Math.max(generatedEndMs, endMs);
            return history.result(startMs, endMs, false, false);
        }

        @Override
        public void close() {
            closeCount++;
        }
    }
}
