package dev.nxnative.android;

/**
 * A setting of the in-game panel with a default and a game's own value: the main screen's Settings hold the default ("<key>"), a game's Game settings and the
 * in-game panel write the game's own ("<key>@<title ID>", Prefs.gameKey), and a game without its own value takes the default. A group of keys (frame generation's
 * switch, multiplier, target, motion estimation and shader set; the on-screen controls' look) falls back key by key, and the game "has its own" when any key of the
 * group is set. titleId null reads and writes the defaults. Pure Java over Store (Prefs.store adapts SharedPreferences), so OwnSettingJvmTest checks it on the JVM.
 */
final class OwnSetting {
    private OwnSetting() {}

    /** The values a settings file holds */
    interface Store {
        boolean has(String key);
        boolean bool(String key, boolean dflt);
        int num(String key, int dflt);
        float real(String key, float dflt);
    }

    /** The key written: the default's, or the game's own ("<key>@<title ID>") */
    static String key(String k, String titleId) { return titleId == null ? k : k + "@" + titleId; }

    static boolean bool(Store s, String k, String titleId, boolean dflt) { boolean d = s.bool(k, dflt); return titleId == null ? d : s.bool(key(k, titleId), d); }
    static int num(Store s, String k, String titleId, int dflt) { int d = s.num(k, dflt); return titleId == null ? d : s.num(key(k, titleId), d); }
    static float real(Store s, String k, String titleId, float dflt) { float d = s.real(k, dflt); return titleId == null ? d : s.real(key(k, titleId), d); }

    /** Whether the game has its own value of any key of the group (never for titleId null) */
    static boolean own(Store s, String[] keys, String titleId) {
        if (titleId == null) return false;
        for (String k : keys) if (s.has(key(k, titleId))) return true;
        return false;
    }

    /** The game's own keys of a group, which "Use Settings' default" removes */
    static String[] ownKeys(String[] keys, String titleId) {
        String[] out = new String[keys.length];
        for (int i = 0; i < keys.length; i++) out[i] = key(keys[i], titleId);
        return out;
    }
}
