package com.example.xylotone.engine;

/** Одна нота мелодии: когда начинается, сколько держится, какая высота и громкость. */
public final class Note {
    public final double startSec;
    public final double durSec;
    public final int midi;
    public final float velocity; // 0..1

    public Note(double startSec, double durSec, int midi, float velocity) {
        this.startSec = startSec;
        this.durSec = durSec;
        this.midi = midi;
        this.velocity = velocity;
    }
}
