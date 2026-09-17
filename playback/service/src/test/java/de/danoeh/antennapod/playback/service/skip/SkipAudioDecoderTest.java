package de.danoeh.antennapod.playback.service.skip;

import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class SkipAudioDecoderTest {
    @Test
    public void completeWindowsRetainEverySampleAtLaterTimestamps() throws IOException {
        for (long startMs : new long[] {0, 600_000, 1_234_567, 36_000_000}) {
            for (long durationMs : new long[] {500, 5_000, 15_000, 30_000}) {
                for (int sampleRate : new int[] {44_100, 48_000}) {
                    SkipAudioDecoder.PcmAccumulator accumulator =
                            new SkipAudioDecoder.PcmAccumulator(startMs, startMs + durationMs);
                    double endUs = appendPcm(accumulator, sampleRate, startMs, startMs + durationMs);
                    SkipAudioDecoder.DecodedAudio result = accumulator.result(false, endUs);
                    assertTrue(result.complete);
                    assertEquals(startMs, result.startMs);
                    assertEquals(durationMs * 8, result.samples.length);
                    assertEquals(durationMs, result.durationMs);
                    for (float sample : result.samples) {
                        assertEquals(0.25f, sample, 0.000001f);
                    }
                }
            }
        }
    }

    @Test
    public void incompleteAudioIsNotPaddedToRequestedEnd() throws IOException {
        SkipAudioDecoder.PcmAccumulator accumulator = new SkipAudioDecoder.PcmAccumulator(600_000, 605_000);
        double endUs = appendPcm(accumulator, 44_100, 600_000, 604_999);
        SkipAudioDecoder.DecodedAudio result = accumulator.result(false, endUs);
        assertFalse(result.complete);
        assertTrue(result.samples.length < 40_000);
        assertTrue(accumulator.result(true, endUs).samples.length < 40_000);
    }

    @Test
    public void missingBeginningIsNotPadded() throws IOException {
        SkipAudioDecoder.PcmAccumulator accumulator = new SkipAudioDecoder.PcmAccumulator(600_000, 605_000);
        double endUs = appendPcm(accumulator, 44_100, 600_500, 605_000);
        SkipAudioDecoder.DecodedAudio result = accumulator.result(false, endUs);
        assertTrue(result.startMs > 600_000);
        assertTrue(result.samples.length < 40_000);
    }

    @Test
    public void realTimestampGapsStillFail() throws IOException {
        SkipAudioDecoder.PcmAccumulator accumulator = new SkipAudioDecoder.PcmAccumulator(600_000, 605_000);
        accumulator.append(0.25f, 600_000_000, 125);
        assertThrows(IOException.class, () -> accumulator.append(0.25f, 600_003_000, 125));
    }

    private static double appendPcm(SkipAudioDecoder.PcmAccumulator accumulator, int sampleRate,
                                    long startMs, long endMs) throws IOException {
        double frameUs = 1_000_000.0 / sampleRate;
        long firstFrame = (long) (Math.max(0, startMs - 250) * sampleRate / 1_000.0 / 1152) * 1152;
        double bufferStartUs = Math.floor(firstFrame * frameUs);
        while (bufferStartUs < endMs * 1_000.0) {
            for (int frame = 0; frame < 1152; frame++) {
                double timeUs = bufferStartUs + frame * frameUs;
                double durationUs = Math.min(frameUs, endMs * 1_000.0 - timeUs);
                if (durationUs > 0) {
                    accumulator.append(0.25f, timeUs, durationUs);
                }
            }
            bufferStartUs += 1152 * frameUs;
        }
        return endMs * 1_000.0;
    }
}
