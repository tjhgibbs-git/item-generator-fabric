package io.github.tjhgibbs.stacksmith.core.nbt;

import java.util.ArrayList;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads and writes SNBT, the text form of NBT used in commands, as of Minecraft 1.21.5+:
 * mixed-type lists, {@code true}/{@code false}, hex/binary numbers with underscores, signedness
 * suffixes ({@code 200ub}), and the {@code bool(...)} / {@code uuid(...)} operations.
 *
 * <p>The game's own {@code TagParser} is the authority; the mod checks commands with it before
 * sending them. This copy exists so the core can turn text into an editable tree, and back,
 * without Minecraft (tests, the outline format, command building).
 */
public final class Snbt {
    private Snbt() {
    }

    // ---- writing -------------------------------------------------------------------------------

    /** Compact SNBT, as commands want it: {@code {a:1,b:[2,3]}}. */
    public static String write(Nbt n) {
        StringBuilder sb = new StringBuilder();
        write(sb, n, -1, 0);
        return sb.toString();
    }

    /** Indented, multi-line SNBT (for display; still valid SNBT). */
    public static String pretty(Nbt n) {
        StringBuilder sb = new StringBuilder();
        write(sb, n, 0, 2);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Nbt n, int depth, int indent) {
        switch (n) {
            case Nbt.Str s -> sb.append(quote(s.value()));
            // Bytes 0 and 1 are written as false/true: identical to the game, easier to read.
            case Nbt.Num num -> sb.append(num.kind() == Nbt.Num.Kind.BYTE && (num.whole() == 0 || num.whole() == 1)
                    ? (num.whole() == 1 ? "true" : "false") : number(num));
            case Nbt.Arr a -> {
                sb.append('[').append(a.kind().suffix.isEmpty() ? "I" : a.kind().suffix.toUpperCase(Locale.ROOT)).append(';');
                long[] v = a.values();
                for (int i = 0; i < v.length; i++) {
                    sb.append(i == 0 ? "" : ",").append(v[i]).append(a.kind() == Nbt.Num.Kind.BYTE ? "b" : a.kind() == Nbt.Num.Kind.LONG ? "L" : "");
                }
                sb.append(']');
            }
            case Nbt.List l -> {
                if (l.isEmpty()) {
                    sb.append("[]");
                    return;
                }
                boolean multi = depth >= 0 && !allScalars(l);
                sb.append('[');
                for (int i = 0; i < l.size(); i++) {
                    if (i > 0) {
                        sb.append(depth >= 0 && !multi ? ", " : ",");
                    }
                    if (multi) {
                        newline(sb, depth + 1, indent);
                    }
                    write(sb, l.get(i), multi ? depth + 1 : depth, indent);
                }
                if (multi) {
                    newline(sb, depth, indent);
                }
                sb.append(']');
            }
            case Nbt.Compound c -> {
                if (c.isEmpty()) {
                    sb.append("{}");
                    return;
                }
                sb.append('{');
                boolean first = true;
                for (var e : c.entries()) {
                    if (!first) {
                        sb.append(',');
                    }
                    first = false;
                    if (depth >= 0) {
                        newline(sb, depth + 1, indent);
                    }
                    sb.append(key(e.getKey())).append(depth >= 0 ? ": " : ":");
                    write(sb, e.getValue(), depth >= 0 ? depth + 1 : -1, indent);
                }
                if (depth >= 0) {
                    newline(sb, depth, indent);
                }
                sb.append('}');
            }
        }
    }

    private static boolean allScalars(Nbt.List l) {
        for (Nbt n : l.items()) {
            if (n instanceof Nbt.Compound || n instanceof Nbt.List) {
                return false;
            }
        }
        return true;
    }

    private static void newline(StringBuilder sb, int depth, int indent) {
        sb.append('\n').append(" ".repeat(depth * indent));
    }

    /** A number in SNBT: {@code 1b 2s 3 4L 5.0f 6.0}. */
    public static String number(Nbt.Num n) {
        return switch (n.kind()) {
            case BYTE, SHORT, LONG -> n.whole() + n.kind().suffix;
            case INT -> Long.toString(n.whole());
            case FLOAT -> decimal(n.real()) + "f";
            case DOUBLE -> {
                String s = decimal(n.real());
                // A double needs a '.' or an exponent to not read back as an int.
                yield s.contains(".") || s.contains("E") || s.contains("N") || s.contains("I") ? s : s + "d";
            }
        };
    }

    private static String decimal(double v) {
        if (v == 0 && 1 / v < 0) {
            return "-0.0";
        }
        if (v == Math.rint(v) && Math.abs(v) < 1e15) {
            return (long) v + ".0";
        }
        String s = Double.toString(v);
        // Floats were stored widened; print the float's shortest form, not the double's.
        String f = Float.toString((float) v);
        return (double) Float.parseFloat(f) == v && f.length() < s.length() ? f : s;
    }

    private static final Pattern BARE_KEY = Pattern.compile("[A-Za-z0-9_\\-.+]+");

    /** A compound key, quoted only when it has to be. */
    public static String key(String k) {
        return BARE_KEY.matcher(k).matches() ? k : quote(k);
    }

    /** A quoted string, using single quotes when that avoids escaping. */
    public static String quote(String s) {
        char q = s.indexOf('"') >= 0 && s.indexOf('\'') < 0 ? '\'' : '"';
        StringBuilder sb = new StringBuilder(s.length() + 2).append(q);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\t' -> sb.append("\\t");
                case '\r' -> sb.append("\\r");
                default -> {
                    if (c == q) {
                        sb.append('\\');
                    }
                    sb.append(c);
                }
            }
        }
        return sb.append(q).toString();
    }

    // ---- reading -------------------------------------------------------------------------------

    /** Parses one complete SNBT value; trailing text is an error. */
    public static Nbt parse(String text) throws SnbtException {
        Reader r = new Reader(text, 0);
        Nbt n = r.value();
        r.skipWs();
        if (r.canRead()) {
            throw r.error("unexpected text after the value");
        }
        return n;
    }

    /** Parses a compound ({@code {...}}). */
    public static Nbt.Compound parseCompound(String text) throws SnbtException {
        if (parse(text) instanceof Nbt.Compound c) {
            return c;
        }
        throw new SnbtException("expected a compound ({...})", 0);
    }

    /**
     * Reads one value starting at {@code offset} and returns it with the offset after it. Used
     * by command and item-string parsers that embed SNBT.
     */
    public static Parsed parseAt(String text, int offset) throws SnbtException {
        Reader r = new Reader(text, offset);
        Nbt n = r.value();
        return new Parsed(n, r.pos);
    }

    public record Parsed(Nbt value, int end) {
    }

    /**
     * Parses a bare scalar token the way an unquoted SNBT value would be read: numbers with
     * suffixes, {@code true}/{@code false}, otherwise a string. Returns null if it is not a
     * single unquoted token.
     */
    public static Nbt scalar(String token) {
        if (token.isEmpty() || !BARE_KEY.matcher(token).matches()) {
            return null;
        }
        Nbt n = Reader.number(token);
        if (n != null) {
            return n;
        }
        if (Reader.looksNumeric(token)) {
            return null; // "12x": not a number, and SNBT does not read it as a string either
        }
        if (token.equals("true")) {
            return Nbt.bool(true);
        }
        if (token.equals("false")) {
            return Nbt.bool(false);
        }
        return new Nbt.Str(token);
    }

    private static final Pattern NUMBER = Pattern.compile(
            "([+-]?)(0x[0-9a-f_]+|0b[01_]+|(?:[0-9][0-9_]*)?\\.?[0-9_]*(?:e[+-]?[0-9_]+)?)([us]?[bsil]|[fd])?",
            Pattern.CASE_INSENSITIVE);

    private static final class Reader {
        final String s;
        int pos;

        Reader(String s, int pos) {
            this.s = s;
            this.pos = pos;
        }

        boolean canRead() {
            return pos < s.length();
        }

        char peek() {
            return s.charAt(pos);
        }

        void skipWs() {
            while (canRead() && Character.isWhitespace(peek())) {
                pos++;
            }
        }

        SnbtException error(String msg) {
            return new SnbtException(msg, pos);
        }

        void expect(char c) throws SnbtException {
            skipWs();
            if (!canRead() || peek() != c) {
                throw error("expected '" + c + "'" + (canRead() ? " but found '" + peek() + "'" : " but the text ended"));
            }
            pos++;
        }

        Nbt value() throws SnbtException {
            skipWs();
            if (!canRead()) {
                throw error("expected a value");
            }
            char c = peek();
            if (c == '{') {
                return compound();
            }
            if (c == '[') {
                return list();
            }
            if (c == '"' || c == '\'') {
                return new Nbt.Str(quoted());
            }
            int start = pos;
            String token = unquoted();
            if (token.isEmpty()) {
                throw error("expected a value but found '" + c + "'");
            }
            skipWs();
            if (canRead() && peek() == '(') {
                return operation(token, start);
            }
            Nbt n;
            try {
                n = number(token);
            } catch (IllegalArgumentException e) {
                pos = start;
                throw error(e.getMessage());
            }
            if (n != null) {
                return n;
            }
            if (looksNumeric(token)) {
                pos = start;
                throw error("'" + token + "' is not a valid number");
            }
            return switch (token) {
                case "true" -> Nbt.bool(true);
                case "false" -> Nbt.bool(false);
                default -> new Nbt.Str(token);
            };
        }

        static boolean looksNumeric(String t) {
            char c = t.charAt(0);
            return Character.isDigit(c) || (c == '-' || c == '+' || c == '.') && t.length() > 1 && Character.isDigit(t.charAt(1));
        }

        private Nbt operation(String name, int start) throws SnbtException {
            pos++; // (
            java.util.List<Nbt> args = new ArrayList<>();
            skipWs();
            while (canRead() && peek() != ')') {
                args.add(value());
                skipWs();
                if (canRead() && peek() == ',') {
                    pos++;
                }
                skipWs();
            }
            expect(')');
            switch (name) {
                case "bool" -> {
                    if (args.size() == 1 && args.getFirst() instanceof Nbt.Num n) {
                        return Nbt.bool(n.asDouble() != 0);
                    }
                    if (args.size() == 1 && args.getFirst() instanceof Nbt.Str str && (str.value().equals("true") || str.value().equals("false"))) {
                        return Nbt.bool(str.value().equals("true"));
                    }
                }
                case "uuid" -> {
                    if (args.size() == 1 && args.getFirst() instanceof Nbt.Str str) {
                        try {
                            return uuid(UUID.fromString(str.value()));
                        } catch (IllegalArgumentException e) {
                            pos = start;
                            throw error("not a UUID: " + str.value());
                        }
                    }
                }
                default -> {
                    pos = start;
                    throw error("unknown operation " + name + "(...)");
                }
            }
            pos = start;
            throw error("bad arguments for " + name + "(...)");
        }

        private Nbt.Compound compound() throws SnbtException {
            pos++; // {
            Nbt.Compound c = new Nbt.Compound();
            skipWs();
            while (canRead() && peek() != '}') {
                int keyAt = pos;
                String key;
                if (peek() == '"' || peek() == '\'') {
                    key = quoted();
                } else {
                    key = unquoted();
                    if (key.isEmpty()) {
                        throw error("expected a key");
                    }
                }
                if (c.has(key)) {
                    pos = keyAt;
                    throw error("duplicate key " + key);
                }
                expect(':');
                c.put(key, value());
                skipWs();
                if (canRead() && peek() == ',') {
                    pos++;
                    skipWs();
                } else {
                    break;
                }
            }
            expect('}');
            return c;
        }

        private Nbt list() throws SnbtException {
            pos++; // [
            skipWs();
            if (pos + 1 < s.length() && s.charAt(pos + 1) == ';' && "BILbil".indexOf(peek()) >= 0) {
                return array();
            }
            Nbt.List l = new Nbt.List();
            while (canRead() && peek() != ']') {
                l.add(value());
                skipWs();
                if (canRead() && peek() == ',') {
                    pos++;
                    skipWs();
                } else {
                    break;
                }
            }
            expect(']');
            return l;
        }

        private Nbt array() throws SnbtException {
            Nbt.Num.Kind kind = switch (Character.toUpperCase(peek())) {
                case 'B' -> Nbt.Num.Kind.BYTE;
                case 'L' -> Nbt.Num.Kind.LONG;
                default -> Nbt.Num.Kind.INT;
            };
            pos += 2;
            java.util.List<Long> values = new ArrayList<>();
            skipWs();
            while (canRead() && peek() != ']') {
                int at = pos;
                Nbt v = value();
                if (!(v instanceof Nbt.Num n) || !n.kind().integral()) {
                    pos = at;
                    throw error("arrays hold whole numbers");
                }
                if (n.whole() < kind.min || n.whole() > kind.max) {
                    pos = at;
                    throw error(n.whole() + " does not fit in a " + kind.name().toLowerCase(Locale.ROOT) + " array");
                }
                values.add(n.whole());
                skipWs();
                if (canRead() && peek() == ',') {
                    pos++;
                    skipWs();
                } else {
                    break;
                }
            }
            expect(']');
            return new Nbt.Arr(kind, values.stream().mapToLong(Long::longValue).toArray());
        }

        private String unquoted() {
            int start = pos;
            while (canRead()) {
                char c = peek();
                if (Character.isLetterOrDigit(c) && c < 128 || c == '_' || c == '-' || c == '.' || c == '+') {
                    pos++;
                } else {
                    break;
                }
            }
            return s.substring(start, pos);
        }

        private String quoted() throws SnbtException {
            char q = s.charAt(pos++);
            StringBuilder sb = new StringBuilder();
            while (canRead()) {
                char c = s.charAt(pos++);
                if (c == q) {
                    return sb.toString();
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                if (!canRead()) {
                    break;
                }
                char e = s.charAt(pos++);
                switch (e) {
                    case '\\', '"', '\'' -> sb.append(e);
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 's' -> sb.append(' ');
                    case 'x' -> sb.appendCodePoint(hex(2));
                    case 'u' -> sb.appendCodePoint(hex(4));
                    case 'U' -> sb.appendCodePoint(hex(8));
                    default -> {
                        pos -= 2;
                        throw error("unknown escape \\" + e);
                    }
                }
            }
            throw error("unterminated string");
        }

        private int hex(int digits) throws SnbtException {
            if (pos + digits > s.length()) {
                throw error("short escape");
            }
            try {
                int v = Integer.parseUnsignedInt(s.substring(pos, pos + digits), 16);
                pos += digits;
                return v;
            } catch (NumberFormatException e) {
                throw error("bad escape");
            }
        }

        /** A number token, or null if the token is not one. Throws on out-of-range values. */
        static Nbt.Num number(String token) {
            Matcher m = NUMBER.matcher(token);
            if (!m.matches() || m.group(2).isEmpty() || m.group(2).equals(".")) {
                return null;
            }
            String sign = m.group(1), body = m.group(2).replace("_", "").toLowerCase(Locale.ROOT);
            String suffix = m.group(3) == null ? "" : m.group(3).toLowerCase(Locale.ROOT);
            if (body.isEmpty() || body.startsWith("e")) {
                return null;
            }
            // The regex only lets a body start with 0x/0b when it is a hex/binary literal ("0b" alone is byte zero).
            boolean hexOrBin = body.startsWith("0x") || body.startsWith("0b");
            boolean decimal = !hexOrBin && (body.contains(".") || body.contains("e"));
            boolean unsigned = suffix.length() == 2 && suffix.startsWith("u");
            String type = suffix.length() == 2 ? suffix.substring(1) : suffix;
            try {
                if (decimal || type.equals("f") || type.equals("d")) {
                    if (hexOrBin || unsigned || !(type.isEmpty() || type.equals("f") || type.equals("d"))) {
                        return null;
                    }
                    double v = Double.parseDouble(sign + body);
                    return type.equals("f") ? Nbt.f(v) : Nbt.d(v);
                }
                java.math.BigInteger v = hexOrBin
                        ? new java.math.BigInteger(body.substring(2), body.startsWith("0x") ? 16 : 2)
                        : new java.math.BigInteger(body);
                if (sign.equals("-")) {
                    v = v.negate();
                }
                Nbt.Num.Kind kind = switch (type) {
                    case "b" -> Nbt.Num.Kind.BYTE;
                    case "s" -> Nbt.Num.Kind.SHORT;
                    case "l" -> Nbt.Num.Kind.LONG;
                    default -> Nbt.Num.Kind.INT;
                };
                long lv;
                if (unsigned || hexOrBin) {
                    // Unsigned (or hex/binary, unsigned by default): wrap into the signed range.
                    int bits = switch (kind) {
                        case BYTE -> 8;
                        case SHORT -> 16;
                        case LONG -> 64;
                        default -> 32;
                    };
                    if (v.signum() < 0 || v.bitLength() > bits) {
                        throw new IllegalArgumentException(token + " is out of range");
                    }
                    lv = v.longValue();
                    if (bits < 64 && lv >= 1L << (bits - 1)) {
                        lv -= 1L << bits;
                    }
                } else {
                    if (v.bitLength() > 63) {
                        throw new IllegalArgumentException(token + " is out of range");
                    }
                    lv = v.longValue();
                }
                return Nbt.Num.of(kind, lv);
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    /** A UUID as the game stores it: four ints, most significant first. */
    public static Nbt.Arr uuid(UUID id) {
        long hi = id.getMostSignificantBits(), lo = id.getLeastSignificantBits();
        return new Nbt.Arr(Nbt.Num.Kind.INT, new long[]{(int) (hi >> 32), (int) hi, (int) (lo >> 32), (int) lo});
    }
}
