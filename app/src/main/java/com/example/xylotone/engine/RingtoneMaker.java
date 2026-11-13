package com.example.xylotone.engine;

import java.util.List;

/** Весь путь от записи песни до готового рингтона с ксилофоном. */
public final class RingtoneMaker {

    public interface Progress {
        void onStage(String text, float fraction);
    }

    public static final class Output {
        public final float[] pcm;        // моно, XylophoneSynth.RATE
        public final int rate;
        public final double startSec;    // откуда взят фрагмент песни
        public final double lengthSec;
        public final List<Note> notes;
        public final double stepSec;

        Output(float[] pcm, double startSec, double lengthSec, List<Note> notes, double stepSec) {
            this.pcm = pcm;
            this.rate = XylophoneSynth.RATE;
            this.startSec = startSec;
            this.lengthSec = lengthSec;
            this.notes = notes;
            this.stepSec = stepSec;
        }
    }

    private RingtoneMaker() {}

    /**
     * @param song      песня в моно, 22050 Гц (MelodyExtractor.RATE)
     * @param startSec  откуда брать фрагмент; отрицательное значение - найти самый яркий момент
     * @param lengthSec желаемая длина рингтона
     */
    public static Output make(float[] song, double startSec, double lengthSec, Progress p) {
        double songSec = song.length / (double) MelodyExtractor.RATE;
        if (songSec < 3) throw new IllegalArgumentException("Песня слишком короткая");
        double len = Math.min(lengthSec, songSec);

        if (p != null) p.onStage("Ищу самый яркий фрагмент…", 0.05f);
        double start = startSec < 0 ? MelodyExtractor.findBestStart(song, len) : startSec;
        start = Math.max(0, Math.min(start, songSec - len));

        if (p != null) p.onStage("Слушаю мелодию…", 0.15f);
        MelodyExtractor.Result r = MelodyExtractor.extract(song, start, len);

        if (p != null) p.onStage("Играю на ксилофоне…", 0.8f);
        float[] pcm = XylophoneSynth.render(r.notes, len);

        if (p != null) p.onStage("Готово", 1f);
        return new Output(pcm, start, len, r.notes, r.stepSec);
    }
}
