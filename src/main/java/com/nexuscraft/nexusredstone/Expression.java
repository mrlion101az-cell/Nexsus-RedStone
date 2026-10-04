package com.nexuscraft.nexusredstone;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A tiny, deliberately safe expression language for the Programmable Logic Block. No loops, no
 * user-defined functions, no I/O: an expression is a finite tree that evaluates in a bounded
 * number of steps, which is what makes it safe to run on the main thread every simulation step
 * for any player-written program. Node count and text length are capped at compile time by the
 * caller (scaled by Engineer level), and integer math is clamped so nothing can overflow.
 *
 * <p>Source is one or two expressions separated by {@code ;}. The first is the output; the
 * optional second is the memory register's next value, evaluated after the output, which is what
 * lets a player build counters and small state machines. Variables: {@code a} (left input),
 * {@code b} (right input), {@code c} (back input), {@code s} (previous output), {@code m} (memory
 * register), {@code t} (game ticks mod 24000), {@code x} (reserved external input, 0 for a PLC).
 * Operators, loosest first: {@code || && == != < <= > >= + - * / %}, unary {@code - !}; the words
 * {@code and}, {@code or}, {@code not} are accepted too. Functions: min, max, abs, clamp, if.
 * Comparisons and logic give 1 or 0 inside arithmetic, and a top-level true drives a full 15.
 */
final class Expression {

    static final int VAR_A = 0;
    static final int VAR_B = 1;
    static final int VAR_C = 2;
    static final int VAR_S = 3;
    static final int VAR_M = 4;
    static final int VAR_T = 5;
    static final int VAR_X = 6;
    static final int VAR_COUNT = 7;

    private static final long LIMIT = 1_000_000L;

    @FunctionalInterface
    private interface Ev {
        long eval(long[] vars);
    }

    private record Node(Ev ev, boolean bool) {
    }

    record Result(Expression expr, String error) {
        boolean ok() {
            return expr != null;
        }
    }

    private final Node out;
    private final Node mem;
    final int nodeCount;

    private Expression(Node out, Node mem, int nodeCount) {
        this.out = out;
        this.mem = mem;
        this.nodeCount = nodeCount;
    }

    boolean hasMemory() {
        return mem != null;
    }

    /** Output strength 0-15: a boolean root is 0 or 15, anything else is clamped. */
    int evalOutput(long[] vars) {
        long v = out.ev.eval(vars);
        if (out.bool) {
            return v != 0 ? 15 : 0;
        }
        return (int) Math.max(0, Math.min(15, v));
    }

    /** Next memory value, 0-9999 (the current value if there is no second expression). */
    int evalMemory(long[] vars) {
        if (mem == null) {
            return (int) Math.max(0, Math.min(9999, vars[VAR_M]));
        }
        long v = mem.ev.eval(vars);
        return (int) Math.max(0, Math.min(9999, v));
    }

    static Result compile(String src, int maxNodes, int maxLength) {
        if (src == null || src.isBlank()) {
            return new Result(null, "empty program");
        }
        if (src.length() > maxLength) {
            return new Result(null, "program too long (" + src.length() + " > " + maxLength + " characters)");
        }
        String[] parts = src.split(";", -1);
        if (parts.length > 2) {
            return new Result(null, "at most one ';' (output; memory)");
        }
        try {
            Parser p1 = new Parser(parts[0], maxNodes);
            Node out = p1.parseAll();
            Node mem = null;
            int nodes = p1.nodes;
            if (parts.length == 2 && !parts[1].isBlank()) {
                Parser p2 = new Parser(parts[1], maxNodes - nodes);
                mem = p2.parseAll();
                nodes += p2.nodes;
            }
            return new Result(new Expression(out, mem, nodes), null);
        } catch (ParseException e) {
            return new Result(null, e.getMessage());
        }
    }

    private static final class ParseException extends Exception {
        private static final long serialVersionUID = 1L;

        ParseException(String message) {
            super(message);
        }
    }

    private static long clamp(long v) {
        return Math.max(-LIMIT, Math.min(LIMIT, v));
    }

    private static final class Parser {
        private final List<String> toks = new ArrayList<>();
        private int pos;
        private final int maxNodes;
        private int depth;
        int nodes;

        Parser(String src, int maxNodes) throws ParseException {
            this.maxNodes = maxNodes;
            tokenize(src);
        }

        private void tokenize(String s) throws ParseException {
            int i = 0;
            while (i < s.length()) {
                char ch = s.charAt(i);
                if (Character.isWhitespace(ch)) {
                    i++;
                } else if (Character.isDigit(ch)) {
                    int j = i;
                    while (j < s.length() && Character.isDigit(s.charAt(j))) {
                        j++;
                    }
                    if (j - i > 7) {
                        throw new ParseException("number too large near '" + s.substring(i, Math.min(s.length(), i + 8)) + "'");
                    }
                    toks.add(s.substring(i, j));
                    i = j;
                } else if (Character.isLetter(ch)) {
                    int j = i;
                    while (j < s.length() && Character.isLetterOrDigit(s.charAt(j))) {
                        j++;
                    }
                    toks.add(s.substring(i, j).toLowerCase(Locale.ROOT));
                    i = j;
                } else {
                    String two = i + 1 < s.length() ? s.substring(i, i + 2) : "";
                    if (two.equals("&&") || two.equals("||") || two.equals("==") || two.equals("!=")
                            || two.equals("<=") || two.equals(">=")) {
                        toks.add(two);
                        i += 2;
                    } else if ("+-*/%()<>!,".indexOf(ch) >= 0) {
                        toks.add(String.valueOf(ch));
                        i++;
                    } else if (ch == '=') {
                        toks.add("==");
                        i++;
                    } else {
                        throw new ParseException("unexpected character '" + ch + "'");
                    }
                }
            }
        }

        private String peek() {
            return pos < toks.size() ? toks.get(pos) : null;
        }

        private boolean accept(String t) {
            if (t.equals(peek())) {
                pos++;
                return true;
            }
            return false;
        }

        private void count() throws ParseException {
            if (++nodes > maxNodes) {
                throw new ParseException("program too complex (more than " + Math.max(0, maxNodes) + " operations for your level)");
            }
        }

        Node parseAll() throws ParseException {
            if (toks.isEmpty()) {
                throw new ParseException("empty expression");
            }
            Node n = parseOr();
            if (pos < toks.size()) {
                throw new ParseException("unexpected '" + toks.get(pos) + "'");
            }
            return n;
        }

        private Node parseOr() throws ParseException {
            Node l = parseAnd();
            while (peek() != null && (peek().equals("||") || peek().equals("or"))) {
                pos++;
                Node r = parseAnd();
                count();
                final Node a = l;
                final Node b = r;
                l = new Node(v -> (a.ev.eval(v) != 0 || b.ev.eval(v) != 0) ? 1 : 0, true);
            }
            return l;
        }

        private Node parseAnd() throws ParseException {
            Node l = parseEq();
            while (peek() != null && (peek().equals("&&") || peek().equals("and"))) {
                pos++;
                Node r = parseEq();
                count();
                final Node a = l;
                final Node b = r;
                l = new Node(v -> (a.ev.eval(v) != 0 && b.ev.eval(v) != 0) ? 1 : 0, true);
            }
            return l;
        }

        private Node parseEq() throws ParseException {
            Node l = parseRel();
            while (peek() != null && (peek().equals("==") || peek().equals("!="))) {
                boolean eq = toks.get(pos++).equals("==");
                Node r = parseRel();
                count();
                final Node a = l;
                final Node b = r;
                l = new Node(v -> ((a.ev.eval(v) == b.ev.eval(v)) == eq) ? 1 : 0, true);
            }
            return l;
        }

        private Node parseRel() throws ParseException {
            Node l = parseAdd();
            while (peek() != null && (peek().equals("<") || peek().equals("<=") || peek().equals(">") || peek().equals(">="))) {
                String op = toks.get(pos++);
                Node r = parseAdd();
                count();
                final Node a = l;
                final Node b = r;
                l = new Node(v -> {
                    long x = a.ev.eval(v);
                    long y = b.ev.eval(v);
                    boolean res = switch (op) {
                        case "<" -> x < y;
                        case "<=" -> x <= y;
                        case ">" -> x > y;
                        default -> x >= y;
                    };
                    return res ? 1 : 0;
                }, true);
            }
            return l;
        }

        private Node parseAdd() throws ParseException {
            Node l = parseMul();
            while (peek() != null && (peek().equals("+") || peek().equals("-"))) {
                boolean plus = toks.get(pos++).equals("+");
                Node r = parseMul();
                count();
                final Node a = l;
                final Node b = r;
                l = new Node(v -> clamp(plus ? a.ev.eval(v) + b.ev.eval(v) : a.ev.eval(v) - b.ev.eval(v)), false);
            }
            return l;
        }

        private Node parseMul() throws ParseException {
            Node l = parseUnary();
            while (peek() != null && (peek().equals("*") || peek().equals("/") || peek().equals("%"))) {
                String op = toks.get(pos++);
                Node r = parseUnary();
                count();
                final Node a = l;
                final Node b = r;
                l = new Node(v -> {
                    long x = a.ev.eval(v);
                    long y = b.ev.eval(v);
                    return switch (op) {
                        case "*" -> clamp(x * y);
                        case "/" -> y == 0 ? 0 : x / y;
                        default -> y == 0 ? 0 : x % y;
                    };
                }, false);
            }
            return l;
        }

        private Node parseUnary() throws ParseException {
            if (++depth > 32) {
                throw new ParseException("expression nested too deeply");
            }
            try {
                if (accept("-")) {
                    Node n = parseUnary();
                    count();
                    return new Node(v -> -n.ev.eval(v), false);
                }
                if (accept("!") || accept("not")) {
                    Node n = parseUnary();
                    count();
                    return new Node(v -> n.ev.eval(v) == 0 ? 1 : 0, true);
                }
                return parsePrimary();
            } finally {
                depth--;
            }
        }

        private Node parsePrimary() throws ParseException {
            String t = peek();
            if (t == null) {
                throw new ParseException("expression ends too early");
            }
            pos++;
            if (Character.isDigit(t.charAt(0))) {
                count();
                final long value = Long.parseLong(t);
                return new Node(v -> value, false);
            }
            if (t.equals("(")) {
                Node n = parseOr();
                if (!accept(")")) {
                    throw new ParseException("missing ')'");
                }
                return n;
            }
            if (Character.isLetter(t.charAt(0))) {
                if (accept("(")) {
                    return parseCall(t);
                }
                int idx = switch (t) {
                    case "a" -> VAR_A;
                    case "b" -> VAR_B;
                    case "c" -> VAR_C;
                    case "s" -> VAR_S;
                    case "m" -> VAR_M;
                    case "t" -> VAR_T;
                    case "x" -> VAR_X;
                    default -> -1;
                };
                if (idx < 0) {
                    throw new ParseException("unknown name '" + t + "' (use a, b, c, s, m, t)");
                }
                count();
                return new Node(v -> v[idx], false);
            }
            throw new ParseException("unexpected '" + t + "'");
        }

        private Node parseCall(String name) throws ParseException {
            List<Node> args = new ArrayList<>();
            if (!accept(")")) {
                do {
                    args.add(parseOr());
                } while (accept(","));
                if (!accept(")")) {
                    throw new ParseException("missing ')' after " + name + "(...)");
                }
            }
            count();
            int n = args.size();
            switch (name) {
                case "min":
                    requireArgs(name, n, 2);
                    return new Node(v -> Math.min(args.get(0).ev.eval(v), args.get(1).ev.eval(v)), false);
                case "max":
                    requireArgs(name, n, 2);
                    return new Node(v -> Math.max(args.get(0).ev.eval(v), args.get(1).ev.eval(v)), false);
                case "abs":
                    requireArgs(name, n, 1);
                    return new Node(v -> Math.abs(args.get(0).ev.eval(v)), false);
                case "clamp":
                    requireArgs(name, n, 3);
                    return new Node(v -> {
                        long lo = args.get(1).ev.eval(v);
                        long hi = args.get(2).ev.eval(v);
                        if (lo > hi) {
                            long tmp = lo;
                            lo = hi;
                            hi = tmp;
                        }
                        return Math.max(lo, Math.min(hi, args.get(0).ev.eval(v)));
                    }, false);
                case "if":
                    requireArgs(name, n, 3);
                    return new Node(v -> args.get(0).ev.eval(v) != 0 ? args.get(1).ev.eval(v) : args.get(2).ev.eval(v), false);
                default:
                    throw new ParseException("unknown function '" + name + "' (min, max, abs, clamp, if)");
            }
        }

        private static void requireArgs(String name, int got, int want) throws ParseException {
            if (got != want) {
                throw new ParseException(name + "() takes " + want + " argument" + (want == 1 ? "" : "s") + ", got " + got);
            }
        }
    }
}
