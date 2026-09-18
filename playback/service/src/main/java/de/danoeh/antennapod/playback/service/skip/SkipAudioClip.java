package de.danoeh.antennapod.playback.service.skip;

import android.content.Context;
import android.net.Uri;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

public final class SkipAudioClip implements AutoCloseable {
    private static final long MAX_DURATION_MS = 90_000;
    public final Uri uri;
    public final long startMs;
    public final long endMs;
    private final File file;

    private SkipAudioClip(File file, long startMs, long endMs) {
        this.file = file;
        this.uri = Uri.fromFile(file);
        this.startMs = startMs;
        this.endMs = endMs;
    }

    public static SkipAudioClip create(Context context, Uri syntheticUri, long startMs, long endMs)
            throws IOException, InterruptedException {
        return create(context, syntheticUri, startMs, endMs, false);
    }

    public static SkipAudioClip create(Context context, Uri syntheticUri, long startMs, long endMs,
                                       boolean fetchMissing) throws IOException, InterruptedException {
        if (!SkipStreamingSource.isStreaming(syntheticUri)
                || startMs < 0 || endMs <= startMs || endMs - startMs > MAX_DURATION_MS) {
            throw new IllegalArgumentException("Invalid clip source or range");
        }
        SkipAudioDecoder.DecodedAudio audio = SkipAudioDecoder.decodePreview(
                context, syntheticUri, startMs, endMs, fetchMissing);
        if (!audio.complete || audio.startMs != startMs || audio.samples.length == 0) {
            throw new SkipStreamingSource.UnavailableException("Requested clip is not fully cached");
        }
        int sampleCount = audio.samples.length;
        File file = File.createTempFile("skip-clip-", ".wav", context.getCacheDir());
        boolean success = false;
        try {
            try (OutputStream output = new BufferedOutputStream(new FileOutputStream(file))) {
                writeHeader(output, sampleCount * 2, audio.sampleRate, audio.channels);
                for (int index = 0; index < sampleCount; index++) {
                    if ((index & 4095) == 0 && Thread.currentThread().isInterrupted()) {
                        throw new InterruptedException();
                    }
                    int sample = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE,
                            Math.round(audio.samples[index] * Short.MAX_VALUE)));
                    output.write(sample & 0xff);
                    output.write((sample >>> 8) & 0xff);
                }
            }
            success = true;
            return new SkipAudioClip(file, startMs, endMs);
        } finally {
            if (!success) {
                file.delete();
            }
        }
    }

    private static void writeHeader(OutputStream output, int dataSize, int sampleRate, int channels)
            throws IOException {
        writeAscii(output, "RIFF");
        writeInt(output, 36 + dataSize);
        writeAscii(output, "WAVEfmt ");
        writeInt(output, 16);
        writeShort(output, 1);
        writeShort(output, channels);
        writeInt(output, sampleRate);
        writeInt(output, sampleRate * channels * 2);
        writeShort(output, channels * 2);
        writeShort(output, 16);
        writeAscii(output, "data");
        writeInt(output, dataSize);
    }

    private static void writeAscii(OutputStream output, String value) throws IOException {
        for (int index = 0; index < value.length(); index++) {
            output.write(value.charAt(index));
        }
    }

    private static void writeInt(OutputStream output, int value) throws IOException {
        output.write(value & 0xff);
        output.write((value >>> 8) & 0xff);
        output.write((value >>> 16) & 0xff);
        output.write((value >>> 24) & 0xff);
    }

    private static void writeShort(OutputStream output, int value) throws IOException {
        output.write(value & 0xff);
        output.write((value >>> 8) & 0xff);
    }

    @Override
    public void close() {
        file.delete();
    }
}
