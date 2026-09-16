package de.danoeh.antennapod.playback.service.skip;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SkipFingerprintTest {
    @Test
    public void recognizesGainNoiseResamplingAndUnalignedOffset() {
        float[] reference = audio(8_000, 4, 17);
        float[] recording = audio(16_000, 4, 17);
        float[] episode = audio(16_000, 12, 53);
        int offset = 3 * 16_000 + 272;
        Random noise = new Random(19);
        for (int index = 0; index < recording.length; index++) {
            episode[offset + index] = recording[index] * 0.43f + (noise.nextFloat() - 0.5f) * 0.002f;
        }
        List<SkipFingerprint.Match> matches = SkipFingerprint.findMatches(
                SkipFingerprint.fromPcm(reference, 8_000), SkipFingerprint.fromPcm(episode, 16_000), 0, 0.82f);
        assertEquals(1, matches.size());
        assertEquals(3_017, matches.get(0).startMs, 32);
    }

    @Test
    public void rejectsUnrelatedAudioAndSilence() {
        AudioFingerprint sample = SkipFingerprint.fromPcm(audio(8_000, 4, 17), 8_000);
        assertTrue(SkipFingerprint.findMatches(sample,
                SkipFingerprint.fromPcm(audio(8_000, 12, 91), 8_000), 0, 0.82f).isEmpty());
        AudioFingerprint silence = SkipFingerprint.fromPcm(new float[8_000 * 12], 8_000);
        assertTrue(SkipFingerprint.findMatches(sample, silence, 0, 0.82f).isEmpty());
        assertTrue(SkipFingerprint.findMatches(silence, silence, 0, 0.82f).isEmpty());
    }

    @Test
    public void findsRepeatedAudioAtDifferentEpisodeOffsets() {
        float[] reference = audio(8_000, 3, 17);
        float[] episode = new float[8_000 * 16];
        System.arraycopy(reference, 0, episode, 8_000, reference.length);
        System.arraycopy(reference, 0, episode, 8_000 * 10, reference.length);
        List<SkipFingerprint.Match> matches = SkipFingerprint.findMatches(
                SkipFingerprint.fromPcm(reference, 8_000), SkipFingerprint.fromPcm(episode, 8_000), 80_000, 0.82f);
        assertEquals(2, matches.size());
        assertEquals(81_000, matches.get(0).startMs, 32);
        assertEquals(90_000, matches.get(1).startMs, 32);
    }

    @Test
    public void fullSampleOverlapRecognizesBoundarySpanningMarker() {
        float[] reference = audio(8_000, 4, 17);
        float[] episode = new float[8_000 * 40];
        System.arraycopy(reference, 0, episode, 29 * 8_000, reference.length);
        AudioFingerprint sample = SkipFingerprint.fromPcm(reference, 8_000);
        assertTrue(SkipFingerprint.findMatches(sample, SkipFingerprint.fromPcm(
                Arrays.copyOf(episode, 30 * 8_000 + 512), 8_000), 0, 0.82f).isEmpty());
        List<SkipFingerprint.Match> matches = SkipFingerprint.findMatches(sample, SkipFingerprint.fromPcm(
                Arrays.copyOf(episode, 34 * 8_000 + 512), 8_000), 0, 0.82f);
        assertFalse(matches.isEmpty());
        assertEquals(29_000, matches.get(0).startMs, 32);
    }

    @Test
    public void gainChangePreservesSpectralSignature() {
        float[] reference = audio(8_000, 4, 17);
        float[] quieter = reference.clone();
        for (int index = 0; index < quieter.length; index++) {
            quieter[index] *= 0.1f;
        }
        assertTrue(SkipFingerprint.similarity(SkipFingerprint.fromPcm(reference, 8_000),
                SkipFingerprint.fromPcm(quieter, 8_000)) > 0.99f);
    }

    @Test
    public void unrelatedTonesDoNotMatchThroughSharedEmptyBands() {
        float[] first = new float[8_000 * 3];
        float[] second = new float[first.length];
        for (int index = 0; index < first.length; index++) {
            first[index] = (float) Math.sin(2 * Math.PI * 440 * index / 8_000);
            second[index] = (float) Math.sin(2 * Math.PI * 1_760 * index / 8_000);
        }
        assertTrue(SkipFingerprint.findMatches(SkipFingerprint.fromPcm(first, 8_000),
                SkipFingerprint.fromPcm(second, 8_000), 0, 0.82f).isEmpty());
    }

    @Test
    public void loudBriefBurstPassesPcmEnergyButHasTooFewAudibleFrames() {
        float[] reference = new float[8_000 * 4];
        float[] audible = audio(8_000, 1, 17);
        System.arraycopy(audible, 0, reference, 8_000, 800);
        assertTrue(SkipFingerprint.isUsable(reference));
        AudioFingerprint fingerprint = SkipFingerprint.fromPcm(reference, 8_000);
        assertFalse(SkipFingerprint.isUsable(fingerprint));
        assertTrue(SkipFingerprint.findMatches(fingerprint, fingerprint, 0, 0.82f).isEmpty());
    }

    @Test
    public void sampleWithSilentPaddingStillRecognizesItsAudibleContent() {
        float[] reference = new float[8_000 * 4];
        float[] audible = audio(8_000, 2, 17);
        System.arraycopy(audible, 0, reference, 8_000, audible.length);
        assertTrue(SkipFingerprint.isUsable(reference));
        assertTrue(SkipFingerprint.isUsable(SkipFingerprint.fromPcm(reference, 8_000)));
        float[] episode = new float[8_000 * 10];
        System.arraycopy(reference, 0, episode, 8_000 * 3, reference.length);
        List<SkipFingerprint.Match> matches = SkipFingerprint.findMatches(
                SkipFingerprint.fromPcm(reference, 8_000), SkipFingerprint.fromPcm(episode, 8_000), 0, 0.82f);
        assertEquals(1, matches.size());
        assertEquals(3_000, matches.get(0).startMs, 32);
    }

    private static float[] audio(int rate, int seconds, long seed) {
        float[] result = new float[rate * seconds];
        Random random = new Random(seed);
        double[][] amplitudes = new double[seconds * 4 + 1][32];
        double[] phases = new double[32];
        for (int band = 0; band < 32; band++) {
            phases[band] = random.nextDouble() * 2 * Math.PI;
        }
        for (double[] segment : amplitudes) {
            for (int band = 0; band < 32; band++) {
                segment[band] = Math.pow(10, -2 * random.nextDouble());
            }
        }
        for (int index = 0; index < result.length; index++) {
            double time = index / (double) rate;
            int segment = (int) (time * 4);
            double blend = time * 4 - segment;
            double value = 0;
            for (int band = 0; band < 32; band++) {
                double amplitude = amplitudes[segment][band] * (1 - blend)
                        + amplitudes[segment + 1][band] * blend;
                value += amplitude * Math.sin(2 * Math.PI * (140 + band * 109.375) * time + phases[band]);
            }
            result[index] = (float) (value / 12);
        }
        return result;
    }
}
