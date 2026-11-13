package com.example.xylotone.engine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Вытаскивает из записи главную мелодию: находит высоту тона по спектру (устойчиво к аккордам),
 * превращает её в ноты, подгоняет под ровную сетку ритма и под одну тональность.
 * Чистая Java, без зависимостей от Android.
 */
public final class MelodyExtractor {

    /** Частота дискретизации, в которой работает анализ. */
    public static final int RATE = 22050;

    private static final int HOP = 512;           // шаг анализа (~23 мс)
    private static final int WIN = 1024;          // окно YIN (~46 мс)
    private static final int MIN_NOTE_FRAMES = 3; // короче ~70 мс - считаем шумом
    private static final int FFT = 2048;
    private static final int M_LO = 52;           // спектральный поиск: E3 ...
    private static final int M_HI = 88;           // ... до E6
    private static final double[] HANN = new double[FFT];
    static {
        for (int i = 0; i < FFT; i++) HANN[i] = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / (FFT - 1));
    }

    /** Насколько пик должен выделяться над фоном, чтобы считать кадр «с мелодией». */
    private static final double CONF = 3.5;

    public static final class Result {
        public final List<Note> notes;
        public final double stepSec;      // длина «восьмой» на сетке ритма
        public final double keyCoverage;  // доля нот, попавших в найденную тональность

        Result(List<Note> notes, double stepSec, double keyCoverage) {
            this.notes = notes;
            this.stepSec = stepSec;
            this.keyCoverage = keyCoverage;
        }
    }

    private MelodyExtractor() {}

    /** Находит самый плотный по громкости фрагмент заданной длины (обычно это припев). */
    public static double findBestStart(float[] x, double lengthSec) {
        int block = RATE / 2; // 0.5 с
        int nBlocks = x.length / block;
        int win = (int) Math.round(lengthSec * 2);
        if (nBlocks <= win) return 0;
        double[] e = new double[nBlocks];
        for (int b = 0; b < nBlocks; b++) {
            double s = 0;
            int off = b * block;
            for (int i = 0; i < block; i++) {
                double v = x[off + i];
                s += v * v;
            }
            e[b] = Math.sqrt(s / block);
        }
        double cur = 0;
        for (int b = 0; b < win; b++) cur += e[b];
        double best = cur;
        int bestB = 0;
        for (int b = win; b < nBlocks; b++) {
            cur += e[b] - e[b - win];
            if (cur > best) {
                best = cur;
                bestB = b - win + 1;
            }
        }
        return bestB * 0.5;
    }

    /** Извлекает мелодию из фрагмента song[startSec .. startSec+lengthSec]. */
    public static Result extract(float[] song, double startSec, double lengthSec) {
        int s0 = (int) Math.max(0, Math.round(startSec * RATE));
        int s1 = (int) Math.min(song.length, s0 + Math.round(lengthSec * RATE));
        if (s1 - s0 < RATE) throw new IllegalArgumentException("Фрагмент слишком короткий");

        float[] x = prefilter(song, s0, s1);

        int need = FFT;
        int nFrames = (x.length - need) / HOP + 1;
        if (nFrames < 10) throw new IllegalArgumentException("Фрагмент слишком короткий");

        double[] rms = new double[nFrames];
        for (int f = 0; f < nFrames; f++) {
            int pos = f * HOP + (FFT - WIN) / 2;
            double s = 0;
            for (int i = 0; i < WIN; i++) {
                double v = x[pos + i];
                s += v * v;
            }
            rms[f] = Math.sqrt(s / WIN);
        }
        double[] sorted = rms.clone();
        Arrays.sort(sorted);
        double p90 = sorted[Math.min(sorted.length - 1, (int) (sorted.length * 0.9))];
        double gate = Math.max(1e-4, 0.12 * p90);

        // 1. высота тона по кадрам (в полутонах MIDI), NaN - тишина или нет тона
        double[] midi = new double[nFrames];
        double[] fftRe = new double[FFT];
        double[] fftIm = new double[FFT];
        for (int f = 0; f < nFrames; f++) {
            midi[f] = rms[f] < gate ? Double.NaN : salience(x, f * HOP, fftRe, fftIm);
        }

        // 2. медианный фильтр по 5 кадрам убирает одиночные выбросы
        double[] sm = new double[nFrames];
        double[] tmp = new double[5];
        for (int f = 0; f < nFrames; f++) {
            if (Double.isNaN(midi[f])) {
                sm[f] = Double.NaN;
                continue;
            }
            int c = 0;
            for (int k = -2; k <= 2; k++) {
                int idx = f + k;
                if (idx >= 0 && idx < nFrames && !Double.isNaN(midi[idx])) tmp[c++] = midi[idx];
            }
            if (c < 3) {
                sm[f] = Double.NaN;
            } else {
                Arrays.sort(tmp, 0, c);
                sm[f] = tmp[c / 2];
            }
        }

        // 3. нарезка на ноты: {начальный кадр, конечный кадр, высота, средняя громкость}
        List<double[]> runs = new ArrayList<>();
        int f = 0;
        while (f < nFrames) {
            if (Double.isNaN(sm[f])) {
                f++;
                continue;
            }
            int start = f;
            double ref = Math.rint(sm[f]);
            int end = f + 1;
            while (end < nFrames && !Double.isNaN(sm[end]) && Math.abs(sm[end] - ref) < 0.8) {
                if (end - start >= 4) {
                    double minPrev = Math.min(Math.min(rms[end - 1], rms[end - 2]),
                            Math.min(rms[end - 3], rms[end - 4]));
                    if (rms[end] > 1.45 * minPrev) break; // повторный удар той же ноты
                }
                end++;
            }
            if (end - start >= MIN_NOTE_FRAMES) {
                double[] vals = new double[end - start];
                double r = 0;
                for (int i = start; i < end; i++) {
                    vals[i - start] = sm[i];
                    r += rms[i];
                }
                Arrays.sort(vals);
                runs.add(new double[]{start, end, Math.rint(vals[vals.length / 2]), r / (end - start)});
            }
            f = end;
        }
        if (runs.isEmpty()) {
            throw new IllegalStateException("Не удалось найти мелодию в этом фрагменте");
        }

        int n = runs.size();
        double[] onset = new double[n];
        double[] durRaw = new double[n];
        int[] pitch = new int[n];
        double[] vel = new double[n];
        double maxRms = 0;
        for (double[] r : runs) maxRms = Math.max(maxRms, r[3]);
        for (int i = 0; i < n; i++) {
            double[] r = runs.get(i);
            onset[i] = (r[0] * HOP + FFT / 2.0) / RATE;
            durRaw[i] = (r[1] - r[0]) * HOP / (double) RATE;
            pitch[i] = (int) r[2];
            vel[i] = 0.55 + 0.45 * (r[3] / maxRms);
        }

        // 4. подгоняем ноты под одну тональность (если мелодия в основном в ней)
        double coverage = snapToKey(pitch, durRaw);

        // 5. выравниваем по ровной сетке ритма
        double step = estimateStep(onset);
        double t0 = onset[0];
        List<double[]> q = new ArrayList<>(); // {слот сетки, высота, длительность, громкость}
        for (int i = 0; i < n; i++) {
            long slot = Math.round((onset[i] - t0) / step);
            double[] last = q.isEmpty() ? null : q.get(q.size() - 1);
            if (last != null && (long) last[0] == slot) {
                if (durRaw[i] > last[2]) {
                    last[1] = pitch[i];
                    last[2] = durRaw[i];
                    last[3] = vel[i];
                }
            } else {
                q.add(new double[]{slot, pitch[i], durRaw[i], vel[i]});
            }
        }

        // 6. переносим в удобный для ксилофона регистр (целыми октавами)
        int[] sortedPitch = new int[q.size()];
        for (int i = 0; i < q.size(); i++) sortedPitch[i] = (int) q.get(i)[1];
        Arrays.sort(sortedPitch);
        int median = sortedPitch[sortedPitch.length / 2];
        int shift = 12 * (int) Math.round((79.0 - median) / 12.0);

        List<Note> notes = new ArrayList<>();
        for (int i = 0; i < q.size(); i++) {
            double[] c = q.get(i);
            double nextSlot = (i + 1 < q.size()) ? q.get(i + 1)[0] : c[0] + 8;
            long steps = Math.max(1, Math.round(c[2] / step));
            steps = Math.min(steps, Math.max(1, (long) (nextSlot - c[0])));
            int m = (int) c[1] + shift;
            while (m > 96) m -= 12;
            while (m < 65) m += 12;
            notes.add(new Note(c[0] * step, steps * step, m, (float) c[3]));
        }
        return new Result(notes, step, coverage);
    }

    /** Срезает бас (ниже ~120 Гц) и верхний шум (выше ~3 кГц), чтобы мелодия была заметнее. */
    private static float[] prefilter(float[] song, int s0, int s1) {
        int n = s1 - s0;
        float[] y = new float[n];
        double aHp = Math.exp(-2 * Math.PI * 120.0 / RATE);
        double aLp = 1 - Math.exp(-2 * Math.PI * 3000.0 / RATE);
        double hpY = 0, hpX = 0, l1 = 0, l2 = 0;
        for (int i = 0; i < n; i++) {
            double v = song[s0 + i];
            hpY = aHp * (hpY + v - hpX);
            hpX = v;
            l1 += aLp * (hpY - l1);
            l2 += aLp * (l1 - l2);
            y[i] = (float) l2;
        }
        return y;
    }

    /**
     * Спектральный поиск мелодии, устойчивый к аккордам и шуму: для каждой возможной высоты
     * складываем силу её гармоник в спектре и берём самую «звучную». Возвращает MIDI или NaN.
     */
    private static double salience(float[] x, int pos, double[] re, double[] im) {
        for (int i = 0; i < FFT; i++) {
            re[i] = x[pos + i] * HANN[i];
            im[i] = 0;
        }
        fft(re, im);
        int half = FFT / 2;
        double[] spec = new double[half + 1];
        double[] cum = new double[half + 2];
        for (int k = 0; k <= half; k++) {
            spec[k] = Math.sqrt(Math.sqrt(re[k] * re[k] + im[k] * im[k]));
            cum[k + 1] = cum[k] + spec[k];
        }
        // «отбеливание»: оставляем только пики над местным фоном
        double[] w = new double[half + 1];
        for (int k = 0; k <= half; k++) {
            int lo = Math.max(0, k - 24), hi = Math.min(half, k + 24);
            double mean = (cum[hi + 1] - cum[lo]) / (hi - lo + 1);
            w[k] = Math.max(0, spec[k] / (mean + 1e-9) - 1.0);
        }
        double binHz = RATE / (double) FFT;
        int nC = (M_HI - M_LO) * 3 + 1;
        double[] s = new double[nC];
        double sum = 0, best = -1;
        int bestI = -1;
        for (int c = 0; c < nC; c++) {
            double f = 440.0 * Math.pow(2.0, (M_LO + c / 3.0 - 69) / 12.0);
            double acc = 0, wt = 1;
            for (int h = 1; h <= 6; h++) {
                double fh = f * h;
                if (fh > 3000) break;
                int kb = (int) Math.round(fh / binHz);
                double pk = 0;
                for (int kk = kb - 1; kk <= kb + 1; kk++) {
                    if (kk >= 0 && kk <= half) pk = Math.max(pk, w[kk]);
                }
                acc += wt * pk;
                wt *= 0.85;
            }
            s[c] = acc;
            sum += acc;
            if (acc > best) {
                best = acc;
                bestI = c;
            }
        }
        double mean = sum / nC;
        if (bestI < 0 || best < CONF * mean || best <= 0) return Double.NaN;
        double shift = 0;
        if (bestI > 0 && bestI < nC - 1) {
            double a = s[bestI - 1], b = s[bestI], c2 = s[bestI + 1];
            double den = a - 2 * b + c2;
            if (Math.abs(den) > 1e-9) shift = 0.5 * (a - c2) / den;
        }
        return M_LO + (bestI + shift) / 3.0;
    }

    /** Быстрое преобразование Фурье (на месте), длина - степень двойки. */
    private static void fft(double[] re, double[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                double t = re[i]; re[i] = re[j]; re[j] = t;
                t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2 * Math.PI / len;
            double wr = Math.cos(ang), wi = Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                double cr = 1, ci = 0;
                for (int j = 0; j < len / 2; j++) {
                    int a = i + j, b = i + j + len / 2;
                    double xr = re[b] * cr - im[b] * ci;
                    double xi = re[b] * ci + im[b] * cr;
                    re[b] = re[a] - xr;
                    im[b] = im[a] - xi;
                    re[a] += xr;
                    im[a] += xi;
                    double ncr = cr * wr - ci * wi;
                    ci = cr * wi + ci * wr;
                    cr = ncr;
                }
            }
        }
    }

    /** Подбирает длину шага сетки (в секундах), на которую лучше всего ложатся начала нот. */
    static double estimateStep(double[] t) {
        if (t.length < 6) return 0.25;
        double t0 = t[0];
        double lo = 0.14, hi = 0.40, inc = 0.0025;
        int m = (int) ((hi - lo) / inc) + 1;
        double[] err = new double[m];
        double min = Double.MAX_VALUE;
        for (int c = 0; c < m; c++) {
            double s = lo + c * inc;
            double sum = 0;
            for (double ti : t) {
                double u = (ti - t0) / s;
                sum += Math.abs(u - Math.rint(u));
            }
            err[c] = sum / t.length;
            min = Math.min(min, err[c]);
        }
        for (int c = m - 1; c >= 0; c--) {
            if (err[c] <= min + 0.03) return lo + c * inc; // из равноценных берём самый крупный шаг
        }
        return 0.25;
    }

    /**
     * Находит мажорную (или параллельную минорную) тональность и сдвигает ноты вне её на полутон.
     * Возвращает долю нот, которые уже были в тональности.
     */
    private static double snapToKey(int[] p, double[] w) {
        double[] hist = new double[12];
        double total = 0;
        for (int i = 0; i < p.length; i++) {
            hist[((p[i] % 12) + 12) % 12] += w[i];
            total += w[i];
        }
        int[] major = {0, 2, 4, 5, 7, 9, 11};
        int bestTonic = 0;
        double bestScore = -1;
        for (int t = 0; t < 12; t++) {
            double s = 0;
            for (int deg : major) s += hist[(t + deg) % 12];
            if (s > bestScore) {
                bestScore = s;
                bestTonic = t;
            }
        }
        double coverage = total > 0 ? bestScore / total : 0;
        if (coverage < 0.75 || coverage > 0.9999) return coverage;
        boolean[] inScale = new boolean[12];
        for (int deg : major) inScale[(bestTonic + deg) % 12] = true;
        for (int i = 0; i < p.length; i++) {
            int pc = ((p[i] % 12) + 12) % 12;
            if (inScale[pc]) continue;
            int lowPc = (pc + 11) % 12, highPc = (pc + 1) % 12;
            boolean lowOk = inScale[lowPc], highOk = inScale[highPc];
            if (lowOk && highOk) p[i] += hist[highPc] > hist[lowPc] ? 1 : -1;
            else if (highOk) p[i] += 1;
            else p[i] -= 1;
        }
        return coverage;
    }
}
