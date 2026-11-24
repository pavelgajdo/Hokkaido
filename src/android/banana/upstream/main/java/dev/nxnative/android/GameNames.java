package dev.nxnative.android;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A game's name as the player renamed it (Home's long press › Rename game; Forgejo #420). Kept as one text in Prefs (one line per game: the game id from GameOrder,
 * the name the game had, the player's name, tab separated). The name is stored in the game's entry as any name is; the record only lets Game.save write the player's
 * name again when the same game is added again with its own name, and lets Reset name give the old one back. No Android classes: the JVM test (GameNamesJvmTest) runs it.
 */
final class GameNames {
    private GameNames() {}

    static final int MAX = 60;

    /** The name as the player typed it, made fit for a tile: control characters and runs of blanks become one space, trimmed, at most {@link #MAX} characters */
    static String clean(String s) {
        if (s == null) return "";
        String t = s.replaceAll("[\\p{Cntrl}\\s]+", " ").trim();
        if (t.length() > MAX) t = t.substring(0, MAX).trim();
        return t;
    }

    /** id → {the game's own name, the player's name} */
    static Map<String, String[]> parse(String data) {
        Map<String, String[]> m = new LinkedHashMap<>();
        if (data == null) return m;
        for (String line : data.split("\n")) {
            String[] p = line.split("\t", -1);
            if (p.length == 3 && !p[0].isEmpty() && !p[1].isEmpty() && !p[2].isEmpty()) m.put(p[0], new String[] { p[1], p[2] });
        }
        return m;
    }

    private static String encode(Map<String, String[]> m) {
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, String[]> e : m.entrySet()) b.append(e.getKey()).append('\t').append(e.getValue()[0]).append('\t').append(e.getValue()[1]).append('\n');
        return b.toString();
    }

    /** The record with the game's own and the player's name for id (a name from {@link #clean}) */
    static String put(String data, String id, String own, String custom) {
        Map<String, String[]> m = parse(data);
        m.put(id, new String[] { own, custom });
        return encode(m);
    }

    static String remove(String data, String id) {
        Map<String, String[]> m = parse(data);
        m.remove(id);
        return encode(m);
    }

    /** True when the game has a player's name now (its entry's name is that name) */
    static boolean renamed(String data, String id, String name) {
        String[] r = parse(data).get(id);
        return r != null && r[1].equals(name);
    }

    /** The game's own name: the one recorded when name is the player's, else name itself (a name the game got by an update or a new scan) */
    static String original(String data, String id, String name) {
        String[] r = parse(data).get(id);
        return r != null && r[1].equals(name) ? r[0] : name;
    }

    /** The name to write for a game whose entry says name: the player's name when name is the game's own name recorded for id, else name */
    static String apply(String data, String id, String name) {
        String[] r = parse(data).get(id);
        return r != null && r[0].equals(name) ? r[1] : name;
    }
}
