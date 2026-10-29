package io.github.tjhgibbs.stacksmith.core.nbt;

/** A parse error with a position the editor can underline. */
public final class SnbtException extends Exception {
    /** Offset into the parsed text (0-based), or -1 when unknown. */
    public final int offset;
    /** 1-based line, for multi-line formats; 0 when not applicable. */
    public final int line;
    public final String reason;

    public SnbtException(String reason, int offset) {
        this(reason, offset, 0);
    }

    public SnbtException(String reason, int offset, int line) {
        super(line > 0 ? "line " + line + ": " + reason : offset >= 0 ? reason + " (at " + offset + ")" : reason);
        this.reason = reason;
        this.offset = offset;
        this.line = line;
    }
}
