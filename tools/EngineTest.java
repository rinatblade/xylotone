import com.example.xylotone.engine.MelodyExtractor;
import com.example.xylotone.engine.Note;
import com.example.xylotone.engine.RingtoneMaker;
import com.example.xylotone.engine.WavWriter;

import java.io.FileOutputStream;
import java.util.Random;

/**
 * Проверка звукового движка на обычной JVM (без Android).
 * Запуск: javac -d out app/src/main/java/com/example/xylotone/engine/*.java tools/EngineTest.java
 *         java -cp out EngineTest demo.wav
 */
public class EngineTest {

    static final int[] PATTERN = {72, 74, 76, 79, 76, 74, 72, 72, 69, 71, 72, 76, 72, 71, 69, 64};
    static final double STEP = 0.25;

    /**
     * Искусственная «песня»: мелодия (голосоподобная, с вибрато), бас, аккорды, «хай-хэт» и шум.
     * @param chordAmp громкость каждой ноты аккорда, hatAmp - громкость хай-хэта, vib - глубина вибрато
     */
    static float[] makeSong(double chordAmp, double hatAmp, double vib) {
        int sr = MelodyExtractor.RATE;
        int reps = 6;
        double total = PATTERN.length * STEP * reps;
        float[] x = new float[(int) (total * sr)];
        Random r = new Random(1);
        for (int rep = 0; rep < reps; rep++) {
            for (int i = 0; i < PATTERN.length; i++) {
                double t0 = (rep * PATTERN.length + i) * STEP;
                double f = 440 * Math.pow(2, (PATTERN[i] - 69) / 12.0);
                int s0 = (int) (t0 * sr);
                int len = (int) (STEP * sr);
                double phase = 0;
                for (int j = 0; j < len; j++) {
                    double t = j / (double) sr;
                    double env = Math.min(1, t / 0.01) * Math.exp(-t / 0.35);
                    double fi = f * (1 + vib * Math.sin(2 * Math.PI * 5.5 * t));
                    phase += 2 * Math.PI * fi / sr;
                    double v = Math.sin(phase) + 0.5 * Math.sin(2 * phase) + 0.25 * Math.sin(3 * phase);
                    x[s0 + j] += (float) (0.3 * env * v);
                }
            }
        }
        double[] chordF = {261.63, 329.63, 392.0};
        for (int i = 0; i < x.length; i++) {
            double t = i / (double) sr;
            x[i] += (float) (0.15 * Math.sin(2 * Math.PI * 110 * t) + 0.02 * (r.nextDouble() * 2 - 1));
            for (double cf : chordF) x[i] += (float) (chordAmp * Math.sin(2 * Math.PI * cf * t));
            // хай-хэт каждые 0.125 с: короткий всплеск шума
            double ph = t % 0.125;
            if (ph < 0.02) x[i] += (float) (hatAmp * (r.nextDouble() * 2 - 1) * Math.exp(-ph / 0.006));
        }
        return x;
    }

    static void run(String name, float[] song, String wavPath) throws Exception {
        long t = System.currentTimeMillis();
        RingtoneMaker.Output out;
        try {
            out = RingtoneMaker.make(song, 0, 20, null);
        } catch (IllegalStateException e) {
            System.out.printf("  %-34s мелодия не найдена%n", name);
            return;
        }
        long ms = System.currentTimeMillis() - t;

        int ok = 0;
        StringBuilder missed = new StringBuilder();
        for (int i = 0; i < 80; i++) {
            double es = i * STEP;
            boolean hit = false;
            for (Note n : out.notes) {
                if (Math.abs(n.startSec - es) < 0.13) {
                    int diff = ((n.midi - PATTERN[i % PATTERN.length]) % 12 + 12) % 12;
                    if (diff == 0) hit = true;
                    break;
                }
            }
            if (hit) ok++;
            else missed.append(i % PATTERN.length).append(' ');
        }
        System.out.println("  пропущены позиции в такте: " + missed);
        double peak = 0, sum = 0;
        for (float v : out.pcm) {
            peak = Math.max(peak, Math.abs(v));
            sum += v * v;
            if (Float.isNaN(v) || Float.isInfinite(v)) throw new IllegalStateException("NaN в звуке");
        }
        System.out.printf("  %-34s нот: %3d, шаг: %.3f, верных: %2d/80, пик %.2f, rms %.3f, %d мс%n",
                name, out.notes.size(), out.stepSec, ok, peak, Math.sqrt(sum / out.pcm.length), ms);
        if (wavPath != null) {
            try (FileOutputStream fos = new FileOutputStream(wavPath)) {
                WavWriter.write(fos, out.pcm, out.rate);
            }
        }
    }

    public static void main(String[] a) throws Exception {
        String wav = a.length > 0 ? a[0] : "demo.wav";
        float[][] songs = {
                makeSong(0.0, 0.0, 0.0),
                makeSong(0.06, 0.0, 0.0),
                makeSong(0.12, 0.10, 0.012),
        };
        String[] names = {"A: чистая мелодия+бас", "B: + аккорды", "C: + аккорды, хай-хэт, вибрато"};
        for (int s = 0; s < 3; s++) {
            run(names[s], songs[s], s == 2 ? wav : null);
        }
    }
}
