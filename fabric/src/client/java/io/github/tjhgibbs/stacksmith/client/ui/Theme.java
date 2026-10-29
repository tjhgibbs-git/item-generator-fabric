package io.github.tjhgibbs.stacksmith.client.ui;

import io.github.tjhgibbs.stacksmith.core.outline.Highlighter;

/** Colours used across Stacksmith's screens, in one place so the look can be tuned. */
public final class Theme {
    public static final int PANEL = 0xC0101418;
    public static final int PANEL_LIGHT = 0xC0202830;
    public static final int CARD = 0xE0181E24;
    public static final int CARD_HEADER = 0xFF232B33;
    public static final int BORDER = 0xFF3A4652;
    public static final int BORDER_FOCUS = 0xFFE0E0E0;
    public static final int ACCENT = 0xFFFFC14D;
    public static final int TEXT = 0xFFE8ECEF;
    public static final int TEXT_DIM = 0xFF8C9AA6;
    public static final int TEXT_FAINT = 0xFF5D6B77;
    public static final int GOOD = 0xFF7BD88F;
    public static final int BAD = 0xFFFF6B6B;
    public static final int WARN = 0xFFFFC14D;
    public static final int SELECTION = 0x804A90D9;
    public static final int CARET = 0xFFFFFFFF;
    public static final int ERROR_LINE = 0x40FF4040;
    public static final int GUTTER = 0xFF161B20;

    private Theme() {
    }

    /** Syntax colours for the outline editor. */
    public static int syntax(Highlighter.Kind k) {
        return switch (k) {
            case KEY -> 0xFF8CC8FF;
            case PUNCT, DASH -> 0xFF9AA5B1;
            case STRING -> 0xFFB5E08C;
            case TEXT -> 0xFFE8ECEF;
            case NUMBER -> 0xFFF5A97F;
            case BOOL -> 0xFFC79BF2;
            case ID -> 0xFF7FDBCA;
            case COMMENT -> 0xFF6A7682;
        };
    }

    /** One colour per component group, for the stripe on each card. */
    public static int group(String group) {
        return switch (group) {
            case "display" -> 0xFFFFC14D;
            case "enchanting" -> 0xFFC79BF2;
            case "combat" -> 0xFFFF6B6B;
            case "tool" -> 0xFF8CC8FF;
            case "food" -> 0xFF7BD88F;
            case "equipment" -> 0xFF7FDBCA;
            case "block", "container" -> 0xFFF5A97F;
            case "entity" -> 0xFFFF9EC7;
            default -> 0xFF9AA5B1;
        };
    }
}
