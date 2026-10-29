package io.github.tjhgibbs.stacksmith.client;

import java.util.Locale;

/** Durations shown in seconds or in ticks (20 per second), as the player chose ({@link Session#ticks}). */
public final class Time {
    private Time() {
    }

    /** The unit label: "s" or "ticks". */
    public static String unit() {
        return Session.ticks ? "ticks" : "s";
    }

    /** The header button's label: the unit in use. */
    public static String buttonLabel() {
        return Session.ticks ? "Ticks" : "Seconds";
    }

    public static String buttonTip() {
        return Session.ticks ? "Times are in ticks (20 = 1 s). Click for seconds." : "Times are in seconds. Click for ticks (20 per second).";
    }

    public static void toggle() {
        Session.ticks = !Session.ticks;
    }

    public static String format(int ticks) {
        if (Session.ticks) {
            return Integer.toString(ticks);
        }
        double s = ticks / 20.0;
        return s == Math.rint(s) ? Long.toString((long) s) : String.format(Locale.ROOT, "%.2f", s).replaceAll("0+$", "");
    }

    /** Ticks from the box's text; throws NumberFormatException for bad input (and fractional ticks). */
    public static int parse(String text) {
        String t = text.strip();
        if (Session.ticks) {
            return Integer.parseInt(t);
        }
        return (int) Math.round(Double.parseDouble(t) * 20);
    }
}
