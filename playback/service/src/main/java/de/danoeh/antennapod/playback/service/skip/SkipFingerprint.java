package de.danoeh.antennapod.playback.service.skip;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Comparator;
import java.util.concurrent.CancellationException;

public final class SkipFingerprint {
    public static final int SAMPLE_RATE = 8_000;
    public static final int FRAME_MS = 64;
    public static final int HOP_MS = 32;
    private static final int FRAME_SIZE = SAMPLE_RATE * FRAME_MS / 1_000;
    private static final int HOP_SIZE = SAMPLE_RATE * HOP_MS / 1_000;
    private static final int BAND_COUNT = 32;
    private static final int FFT_SIZE = 512;
    private static final float MIN_RMS = 0.004f;

    private SkipFingerprint() {
    }

    public static AudioFingerprint fromPcm(float[] pcm, int sampleRate) {
        if (pcm == null || sampleRate <= 0 || pcm.length < (long) sampleRate * FRAME_MS / 1_000
                || pcm.length * 1_000L / sampleRate > 120_000) {
            throw new IllegalArgumentException("PCM is too short");
        }
        float[] resampled = resample(pcm, sampleRate);
        int frameCount = 1 + (resampled.length - FRAME_SIZE) / HOP_SIZE;
        if (frameCount <= 0) {
            throw new IllegalArgumentException("PCM is too short");
        }
        int[] hashes = new int[frameCount];
        for (int frame = 0; frame < frameCount; frame++) {
            checkCancelled();
            hashes[frame] = hashFrame(resampled, frame * HOP_SIZE);
        }
        return new AudioFingerprint(SAMPLE_RATE, FRAME_MS, HOP_MS, hashes);
    }

    public static boolean isUsable(float[] pcm) {
        if (pcm == null || pcm.length == 0) {
            return false;
        }
        double energy = 0;
        double peak = 0;
        for (float value : pcm) {
            energy += value * value;
            peak = Math.max(peak, Math.abs(value));
        }
        double rms = Math.sqrt(energy / pcm.length);
        return rms >= MIN_RMS && peak >= MIN_RMS * 2;
    }

    static boolean isUsable(AudioFingerprint fingerprint) {
        int audibleFrames = 0;
        for (int hash : fingerprint.hashes) {
            if (hash != 0) {
                audibleFrames++;
            }
        }
        return audibleFrames >= 8;
    }

    public static float similarity(AudioFingerprint first, AudioFingerprint second) {
        if (first.sampleRate != second.sampleRate || first.frameMs != second.frameMs
                || first.hopMs != second.hopMs) {
            return 0;
        }
        int count = Math.min(first.hashes.length, second.hashes.length);
        if (count == 0) {
            return 0;
        }
        int distance = 0;
        for (int i = 0; i < count; i++) {
            distance += frameDistance(first.hashes[i], second.hashes[i]);
        }
        return 1f - distance / (float) (count * 32);
    }

    static List<Match> findMatches(AudioFingerprint sample, AudioFingerprint target,
                                   long targetStartMs, float threshold) {
        if (sample.frameCount() > target.frameCount() || sample.sampleRate != target.sampleRate
                || sample.frameMs != target.frameMs || sample.hopMs != target.hopMs) {
            return Collections.emptyList();
        }
        int activeFrames = 0;
        for (int hash : sample.hashes) {
            if (hash != 0) {
                activeFrames++;
            }
        }
        if (activeFrames < 8) {
            return Collections.emptyList();
        }
        List<Match> matches = new ArrayList<>();
        int lastFrame = target.frameCount() - sample.frameCount();
        for (int start = 0; start <= lastFrame; start++) {
            checkCancelled();
            int distance = 0;
            int budget = (int) ((1 - threshold) * activeFrames * 32);
            for (int offset = 0; offset < sample.frameCount(); offset++) {
                if (sample.hashes[offset] == 0) {
                    continue;
                }
                distance += frameDistance(sample.hashes[offset], target.hashes[start + offset]);
                if (distance > budget) {
                    break;
                }
            }
            float score = 1f - distance / (float) (activeFrames * 32);
            if (score >= threshold) {
                matches.add(new Match(targetStartMs + start * target.hopMs, score));
            }
        }
        matches.sort(Comparator.comparingDouble((Match match) -> -match.score));
        List<Match> peaks = new ArrayList<>();
        for (Match match : matches) {
            boolean duplicate = false;
            for (Match peak : peaks) {
                if (Math.abs(peak.startMs - match.startMs) <= Math.max(256, sample.durationMs() / 2)) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) {
                peaks.add(match);
            }
        }
        peaks.sort(Comparator.comparingLong(match -> match.startMs));
        return peaks;
    }

    private static int frameDistance(int first, int second) {
        int minority = Math.min(Math.min(Integer.bitCount(first), Integer.bitCount(second)),
                Math.min(Integer.bitCount(~first), Integer.bitCount(~second)));
        return minority == 0 ? 32 : Math.min(32, Integer.bitCount(first ^ second) * 16 / minority);
    }

    private static void checkCancelled() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException();
        }
    }

    static final class Match {
        final long startMs;
        final float score;

        Match(long startMs, float score) {
            this.startMs = startMs;
            this.score = score;
        }
    }

    private static float[] resample(float[] source, int sourceRate) {
        if (sourceRate == SAMPLE_RATE) {
            return source;
        }
        int outputLength = Math.max(1, (int) Math.round(source.length * (double) SAMPLE_RATE / sourceRate));
        float[] output = new float[outputLength];
        float scale = sourceRate / (float) SAMPLE_RATE;
        for (int i = 0; i < output.length; i++) {
            float sourcePosition = i * scale;
            int lower = Math.min(source.length - 1, (int) sourcePosition);
            int upper = Math.min(source.length - 1, lower + 1);
            float fraction = sourcePosition - lower;
            output[i] = source[lower] + fraction * (source[upper] - source[lower]);
        }
        return output;
    }

    private static int hashFrame(float[] samples, int offset) {
        double[] real = new double[FFT_SIZE];
        double[] imaginary = new double[FFT_SIZE];
        double energy = 0;
        for (int i = 0; i < FRAME_SIZE; i++) {
            double value = samples[offset + i] * (0.5 - 0.5 * Math.cos(2 * Math.PI * i / (FRAME_SIZE - 1)));
            real[i] = value;
            energy += value * value;
        }
        fft(real, imaginary);
        double[] bands = new double[BAND_COUNT];
        for (int band = 0; band < BAND_COUNT; band++) {
            int low = 6 + band * 7;
            int high = low + 7;
            double sum = 0;
            for (int bin = low; bin < high; bin++) {
                sum += real[bin] * real[bin] + imaginary[bin] * imaginary[bin];
            }
            bands[band] = sum / Math.max(1, high - low);
        }
        if (energy < 0.000001) {
            return 0;
        }
        int hash = 0;
        double maximum = 0;
        for (double band : bands) {
            maximum = Math.max(maximum, band);
        }
        for (int band = 0; band < BAND_COUNT; band++) {
            bands[band] = Math.max(bands[band], maximum * 0.0001);
        }
        for (int band = 0; band < BAND_COUNT; band++) {
            if (bands[band] > bands[(band + 11) % BAND_COUNT]) {
                hash |= 1 << band;
            }
        }
        return hash;
    }

    private static void fft(double[] real, double[] imaginary) {
        int n = real.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) {
                j ^= bit;
            }
            j ^= bit;
            if (i < j) {
                double swap = real[i];
                real[i] = real[j];
                real[j] = swap;
            }
        }
        for (int length = 2; length <= n; length <<= 1) {
            double angle = -2 * Math.PI / length;
            double wReal = Math.cos(angle);
            double wImaginary = Math.sin(angle);
            for (int offset = 0; offset < n; offset += length) {
                double currentReal = 1;
                double currentImaginary = 0;
                int half = length / 2;
                for (int i = 0; i < half; i++) {
                    int even = offset + i;
                    int odd = even + half;
                    double productReal = currentReal * real[odd] - currentImaginary * imaginary[odd];
                    double productImaginary = currentReal * imaginary[odd]
                            + currentImaginary * real[odd];
                    real[odd] = real[even] - productReal;
                    imaginary[odd] = imaginary[even] - productImaginary;
                    real[even] += productReal;
                    imaginary[even] += productImaginary;
                    double nextReal = currentReal * wReal - currentImaginary * wImaginary;
                    currentImaginary = currentReal * wImaginary + currentImaginary * wReal;
                    currentReal = nextReal;
                }
            }
        }
    }
}
