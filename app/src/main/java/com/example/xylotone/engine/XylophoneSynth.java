package com.example.xylotone.engine;

import java.util.List;
import java.util.Random;

/**
 * Синтез ксилофона: у настроенной деревянной пластины три слышимых обертона
 * с отношением частот 1 : 3 : 6, быстро затухающих (верхние - быстрее),
 * плюс короткий «щелчок» молоточка и лёгкая комнатная реверберация.
 */
public final class XylophoneSynth {

    public static final int RATE = 44100;

    private static final double[] RATIO = {1.0, 3.0, 6.0};
    private static final double[] AMP = {1.0, 0.45, 0.18};

    private XylophoneSynth() {}

    /** Играет ноты ксилофоном. Результат - моно, длиной ровно lengthSec секунд. */
    public static float[] render(List<Note> notes, double lengthSec) {
        int total = (int) Math.round(lengthSec * RATE);
        float[] dry = new float[total];
        Random rnd = new Random(7);
        for (Note n : notes) addNote(dry, n, rnd);

        float[] wet = reverb(dry);
        float[] out = new float[total];
        for (int i = 0; i < total; i++) out[i] = dry[i] + 0.16f * wet[i];
        finish(out);
        return out;
    }

    private static void addNote(float[] out, Note n, Random rnd) {
        double f0 = 440.0 * Math.pow(2.0, (n.midi - 69) / 12.0);
        int s0 = (int) Math.round(n.startSec * RATE);
        if (s0 < 0 || s0 >= out.length) return;

        double tau1 = 0.45 * Math.sqrt(880.0 / f0);      // время затухания основного тона, с
        tau1 = Math.max(0.12, Math.min(0.9, tau1));
        int len = (int) Math.min(out.length - s0, Math.round((n.durSec + 0.8) * RATE));
        int releaseFrom = (int) Math.round(n.durSec * RATE);
        double relDec = Math.exp(-1.0 / (0.15 * RATE));
        double vel = n.velocity;

        for (int k = 0; k < RATIO.length; k++) {
            double fk = f0 * RATIO[k];
            if (fk > 0.45 * RATE) continue;
            double tauK = tau1 / (1.0 + 2.2 * k);
            double dec = Math.exp(-1.0 / (tauK * RATE));
            double w = 2 * Math.PI * fk / RATE;
            double cr = Math.cos(w), ci = Math.sin(w);
            double re = 1.0, im = 0.0;
            double amp = vel * AMP[k] * 0.5;
            for (int i = 0; i < len; i++) {
                out[s0 + i] += (float) (amp * im);
                double nr = re * cr - im * ci;
                double ni = re * ci + im * cr;
                re = nr;
                im = ni;
                amp *= dec;
                if (i >= releaseFrom) amp *= relDec;
            }
        }

        // щелчок молоточка по дереву
        int clickLen = (int) (0.006 * RATE);
        double lp = 0;
        for (int i = 0; i < clickLen && s0 + i < out.length; i++) {
            double noise = rnd.nextDouble() * 2 - 1;
            lp += 0.35 * (noise - lp);
            out[s0 + i] += (float) (vel * 0.35 * lp * Math.exp(-i / (0.0018 * RATE)));
        }
    }

    /** Простая реверберация Шрёдера: четыре гребёнки и два all-pass фильтра. */
    private static float[] reverb(float[] in) {
        int[] combD = {1116, 1188, 1277, 1356};
        float fb = 0.78f;
        float[] out = new float[in.length];
        for (int d : combD) {
            float[] buf = new float[d];
            int idx = 0;
            for (int i = 0; i < in.length; i++) {
                float y = buf[idx];
                buf[idx] = in[i] + y * fb;
                if (++idx == d) idx = 0;
                out[i] += y * 0.25f;
            }
        }
        allpass(out, 556, 0.5f);
        allpass(out, 441, 0.5f);
        return out;
    }

    private static void allpass(float[] x, int d, float g) {
        float[] buf = new float[d];
        int idx = 0;
        for (int i = 0; i < x.length; i++) {
            float bo = buf[idx];
            float w = x[i] + g * bo;
            buf[idx] = w;
            x[i] = -g * w + bo;
            if (++idx == d) idx = 0;
        }
    }

    /** Громкость под рингтон: нормализация, мягкое ограничение пиков, плавное затухание в конце. */
    private static void finish(float[] out) {
        float peak = 0;
        for (float v : out) peak = Math.max(peak, Math.abs(v));
        if (peak < 1e-9f) return;
        float g = 0.9f / peak;
        double norm = Math.tanh(1.5);
        for (int i = 0; i < out.length; i++) {
            out[i] = (float) (Math.tanh(1.5 * out[i] * g) / norm);
        }
        int fade = Math.min(out.length, (int) (0.5 * RATE));
        for (int i = 0; i < fade; i++) {
            double factor = 0.5 * (1 - Math.cos(Math.PI * i / fade));
            out[out.length - 1 - i] *= (float) factor;
        }
    }
}
