package com.example.xylotone;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.Arrays;

/**
 * Читает любой звуковой файл, который понимает Android (mp3, m4a, aac, ogg, flac, wav...),
 * и сразу превращает его в моно с нужной частотой дискретизации.
 */
final class AudioDecoder {

    interface Progress {
        void onProgress(float fraction);
    }

    private AudioDecoder() {}

    static float[] decodeToMono(Context ctx, Uri uri, int targetRate, double maxSeconds, Progress progress)
            throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        try {
            extractor.setDataSource(ctx, uri, null);
            int track = -1;
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat f = extractor.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    track = i;
                    format = f;
                    break;
                }
            }
            if (track < 0) throw new IOException("В файле нет звуковой дорожки");
            extractor.selectTrack(track);

            long durationUs = format.containsKey(MediaFormat.KEY_DURATION)
                    ? format.getLong(MediaFormat.KEY_DURATION) : 0;
            int limit = (int) (maxSeconds * targetRate);
            int expected = durationUs > 0
                    ? (int) Math.min(limit, durationUs / 1_000_000.0 * targetRate * 1.02 + targetRate)
                    : targetRate * 30;

            int srcRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int channels = format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                    ? format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 2;
            Mono mono = new Mono(srcRate, channels, targetRate, limit, expected);

            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
            codec.configure(format, null, null, 0);
            codec.start();

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            boolean outputDone = false;
            while (!outputDone) {
                if (!inputDone) {
                    int ii = codec.dequeueInputBuffer(10_000);
                    if (ii >= 0) {
                        ByteBuffer ib = codec.getInputBuffer(ii);
                        int size = ib == null ? -1 : extractor.readSampleData(ib, 0);
                        if (size < 0) {
                            codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(ii, 0, size, extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }

                int oi = codec.dequeueOutputBuffer(info, 10_000);
                if (oi >= 0) {
                    ByteBuffer ob = codec.getOutputBuffer(oi);
                    if (ob != null && info.size > 0) {
                        ob.position(info.offset);
                        ob.limit(info.offset + info.size);
                        mono.push(ob.order(ByteOrder.nativeOrder()).asShortBuffer());
                    }
                    codec.releaseOutputBuffer(oi, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0 || mono.full()) {
                        outputDone = true;
                    }
                    if (progress != null && durationUs > 0) {
                        progress.onProgress(Math.min(1f, info.presentationTimeUs / (float) durationUs));
                    }
                } else if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat nf = codec.getOutputFormat();
                    mono.setFormat(nf.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                            nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT));
                }
            }

            float[] result = mono.result();
            if (result.length < targetRate) throw new IOException("Не удалось прочитать звук из файла");
            return result;
        } catch (IllegalStateException | IllegalArgumentException e) {
            throw new IOException("Этот формат не поддерживается: " + e.getMessage(), e);
        } finally {
            if (codec != null) {
                try {
                    codec.stop();
                } catch (Exception ignored) {
                    // уже остановлен
                }
                codec.release();
            }
            extractor.release();
        }
    }

    /** Склеивает каналы в один и приводит частоту к нужной (усреднением, чтобы не было «ряби»). */
    private static final class Mono {
        private float[] out;
        private int n;
        private final int limit;
        private final int dstRate;
        private int channels;
        private double ratio;
        private double srcPos;
        private double nextOut;
        private double acc;
        private int cnt;
        private float last;

        Mono(int srcRate, int channels, int dstRate, int limit, int expected) {
            this.dstRate = dstRate;
            this.limit = limit;
            this.out = new float[Math.max(1024, expected)];
            setFormat(srcRate, channels);
        }

        void setFormat(int srcRate, int channels) {
            this.channels = Math.max(1, channels);
            this.ratio = srcRate / (double) dstRate;
            this.srcPos = 0;
            this.nextOut = ratio;
            this.acc = 0;
            this.cnt = 0;
        }

        boolean full() {
            return n >= limit;
        }

        void push(ShortBuffer sb) {
            while (sb.remaining() >= channels && n < limit) {
                float s = 0;
                for (int c = 0; c < channels; c++) s += sb.get();
                s /= channels * 32768f;
                acc += s;
                cnt++;
                last = s;
                srcPos += 1.0;
                while (srcPos >= nextOut && n < limit) {
                    float v = cnt > 0 ? (float) (acc / cnt) : last;
                    acc = 0;
                    cnt = 0;
                    if (n == out.length) out = Arrays.copyOf(out, out.length * 2);
                    out[n++] = v;
                    nextOut += ratio;
                }
            }
        }

        float[] result() {
            return Arrays.copyOf(out, n);
        }
    }
}
