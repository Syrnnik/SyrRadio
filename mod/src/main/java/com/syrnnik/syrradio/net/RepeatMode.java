package com.syrnnik.syrradio.net;

/** Повтор: выключен, вся очередь по кругу или один трек. */
public enum RepeatMode {
    OFF, ALL, ONE;

    public RepeatMode next() {
        return values()[(ordinal() + 1) % values().length];
    }
}
