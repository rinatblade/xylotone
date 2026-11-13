package com.example.xylotone.engine;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** Запись моно-звука в WAV (16 бит). */
public final class WavWriter {

    private WavWriter() {}

    public static void write(OutputStream os, float[] pcm, int rate) throws IOException {
        int dataLen = pcm.length * 2;
        ByteBuffer bb = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN);
        bb.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        bb.putInt(36 + dataLen);
        bb.put("WAVE".getBytes(StandardCharsets.US_ASCII));
        bb.put("fmt ".getBytes(StandardCharsets.US_ASCII));
        bb.putInt(16);
        bb.putShort((short) 1);      // PCM
        bb.putShort((short) 1);      // моно
        bb.putInt(rate);
        bb.putInt(rate * 2);
        bb.putShort((short) 2);
        bb.putShort((short) 16);
        bb.put("data".getBytes(StandardCharsets.US_ASCII));
        bb.putInt(dataLen);
        for (float v : pcm) {
            int s = Math.round(v * 32767f);
            bb.putShort((short) Math.max(-32768, Math.min(32767, s)));
        }
        os.write(bb.array());
    }
}
