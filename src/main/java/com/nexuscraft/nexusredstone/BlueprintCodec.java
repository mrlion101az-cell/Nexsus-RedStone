package com.nexuscraft.nexusredstone;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Serialises a {@link Blueprint} to one compact, copy-pasteable string (text lines, deflated,
 * URL-safe Base64), and back. The format is versioned ({@code NRBP1}) and the decoder is
 * defensive: a hard cap on inflated size (so a hostile "zip bomb" string cannot exhaust memory),
 * a cap on block and component counts supplied by the caller, and every field validated. A
 * malformed or tampered string yields a clear error, never an exception or a half-built plan.
 */
final class BlueprintCodec {

    static final String MAGIC = "NRBP1";
    private static final int MAX_INFLATED_BYTES = 2_000_000;

    record Decoded(Blueprint blueprint, String error) {
        boolean ok() {
            return blueprint != null;
        }
    }

    private BlueprintCodec() {
    }

    static String encode(Blueprint bp) {
        StringBuilder sb = new StringBuilder();
        sb.append(MAGIC).append('\n');
        sb.append("N\t").append(esc(bp.name)).append('\n');
        sb.append("S\t").append(bp.sizeX).append('\t').append(bp.sizeY).append('\t').append(bp.sizeZ).append('\n');
        for (Blueprint.Block b : bp.blocks) {
            sb.append("B\t").append(b.dx()).append('\t').append(b.dy()).append('\t').append(b.dz())
                    .append('\t').append(esc(b.data())).append('\n');
        }
        for (Blueprint.Comp c : bp.components) {
            StringBuilder params = new StringBuilder();
            for (Map.Entry<String, Integer> e : c.params().entrySet()) {
                if (params.length() > 0) {
                    params.append(',');
                }
                params.append(e.getKey()).append('=').append(e.getValue());
            }
            sb.append("C\t").append(c.dx()).append('\t').append(c.dy()).append('\t').append(c.dz())
                    .append('\t').append(c.type()).append('\t').append(c.facing())
                    .append('\t').append(params)
                    .append('\t').append(esc(c.text() == null ? "" : c.text()))
                    .append('\t').append(esc(String.join(",", c.filter()))).append('\n');
        }
        return compress(sb.toString());
    }

    static Decoded decode(String encoded, int maxBlocks) {
        if (encoded == null || encoded.isBlank()) {
            return new Decoded(null, "empty blueprint");
        }
        String text;
        try {
            text = decompress(encoded.trim());
        } catch (IllegalArgumentException | DataFormatException e) {
            return new Decoded(null, "not a valid blueprint (corrupt or truncated)");
        }
        if (text == null) {
            return new Decoded(null, "blueprint data is too large");
        }
        String[] lines = text.split("\n");
        if (lines.length == 0 || !lines[0].equals(MAGIC)) {
            return new Decoded(null, "unknown blueprint version");
        }
        Blueprint bp = new Blueprint();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.isEmpty()) {
                continue;
            }
            String[] f = line.split("\t", -1);
            try {
                switch (f[0]) {
                    case "N":
                        bp.name = unesc(f[1]);
                        break;
                    case "S":
                        bp.sizeX = Integer.parseInt(f[1]);
                        bp.sizeY = Integer.parseInt(f[2]);
                        bp.sizeZ = Integer.parseInt(f[3]);
                        break;
                    case "B":
                        bp.blocks.add(new Blueprint.Block(Integer.parseInt(f[1]), Integer.parseInt(f[2]),
                                Integer.parseInt(f[3]), unesc(f[4])));
                        break;
                    case "C":
                        bp.components.add(parseComp(f));
                        break;
                    default:
                        return new Decoded(null, "unknown line type '" + f[0] + "'");
                }
            } catch (RuntimeException e) {
                return new Decoded(null, "malformed line " + (i + 1));
            }
            if (bp.totalBlocks() > maxBlocks) {
                return new Decoded(null, "blueprint has more than " + maxBlocks + " blocks (your level allows " + maxBlocks + ")");
            }
        }
        if (bp.sizeX < 0 || bp.sizeY < 0 || bp.sizeZ < 0 || bp.sizeX > 256 || bp.sizeY > 256 || bp.sizeZ > 256) {
            return new Decoded(null, "blueprint dimensions are out of range");
        }
        for (Blueprint.Block b : bp.blocks) {
            if (!inBox(bp, b.dx(), b.dy(), b.dz())) {
                return new Decoded(null, "a block lies outside the blueprint's bounds");
            }
        }
        for (Blueprint.Comp c : bp.components) {
            if (!inBox(bp, c.dx(), c.dy(), c.dz())) {
                return new Decoded(null, "a component lies outside the blueprint's bounds");
            }
        }
        return new Decoded(bp, null);
    }

    private static boolean inBox(Blueprint bp, int x, int y, int z) {
        return x >= 0 && y >= 0 && z >= 0 && x < Math.max(1, bp.sizeX) && y < Math.max(1, bp.sizeY) && z < Math.max(1, bp.sizeZ);
    }

    private static Blueprint.Comp parseComp(String[] f) {
        ComponentType type = ComponentType.byId(f[4]);
        if (type == null) {
            throw new IllegalArgumentException("unknown component " + f[4]);
        }
        Map<String, Integer> params = new LinkedHashMap<>();
        if (!f[6].isEmpty()) {
            for (String kv : f[6].split(",")) {
                String[] p = kv.split("=");
                ParamSpec spec = type.spec(p[0]);
                if (spec != null) {
                    params.put(p[0], spec.clamp(Integer.parseInt(p[1])));
                }
            }
        }
        List<String> filter = new ArrayList<>();
        String rawFilter = unesc(f[8]);
        if (!rawFilter.isEmpty()) {
            for (String s : rawFilter.split(",")) {
                String n = ItemFilter.normalize(s);
                if (!n.isEmpty() && filter.size() < Math.max(1, type.filterSlots())) {
                    filter.add(n);
                }
            }
        }
        String text = unesc(f[7]);
        if (text.length() > 400) {
            text = text.substring(0, 400);
        }
        return new Blueprint.Comp(Integer.parseInt(f[1]), Integer.parseInt(f[2]), Integer.parseInt(f[3]),
                type.id, f[5], params, text, filter);
    }

    static String esc(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '%':
                    sb.append("%25");
                    break;
                case '\t':
                    sb.append("%09");
                    break;
                case '\n':
                    sb.append("%0A");
                    break;
                case '\r':
                    sb.append("%0D");
                    break;
                default:
                    sb.append(ch);
            }
        }
        return sb.toString();
    }

    static String unesc(String s) {
        return s.replace("%09", "\t").replace("%0A", "\n").replace("%0D", "\r").replace("%25", "%");
    }

    private static String compress(String text) {
        byte[] input = text.getBytes(StandardCharsets.UTF_8);
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        deflater.setInput(input);
        deflater.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        while (!deflater.finished()) {
            int n = deflater.deflate(buf);
            out.write(buf, 0, n);
        }
        deflater.end();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray());
    }

    /** Returns null if the inflated data would exceed the safety cap. */
    private static String decompress(String encoded) throws DataFormatException {
        byte[] data = Base64.getUrlDecoder().decode(encoded);
        Inflater inflater = new Inflater();
        inflater.setInput(data);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        try {
            while (!inflater.finished()) {
                int n = inflater.inflate(buf);
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    throw new DataFormatException("truncated");
                }
                out.write(buf, 0, n);
                if (out.size() > MAX_INFLATED_BYTES) {
                    return null;
                }
            }
        } finally {
            inflater.end();
        }
        return out.toString(StandardCharsets.UTF_8);
    }
}
