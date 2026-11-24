package dev.nxnative.android;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Which on-screen controls the player hid in the layout editor (TouchControls; #539): a set of control keys ("zl", "l3", "dpad", "stick0" ...) kept with the layout of a screen shape, as
 * a comma separated string. Pure logic, so the JVM tests run it without Android: parsing a stored value (unknown keys dropped, so an older or newer layout never breaks), turning one
 * control on or off, and the rule that the last visible control cannot be hidden (the host takes no empty layout, and a screen with no control and no show / hide button would have
 * nothing to tap). Nothing hidden is the default: an existing layout stays as it is.
 */
final class TouchHidden {
    private TouchHidden() {}

    /** The stored value's keys that are among the controls there are (all: every key of the layout); never null */
    static Set<String> parse(String stored, Collection<String> all) {
        Set<String> out = new LinkedHashSet<>();
        if (stored == null) return out;
        for (String k : stored.split(",")) { k = k.trim(); if (!k.isEmpty() && all.contains(k)) out.add(k); }
        return out;
    }

    static String encode(Set<String> hidden) { return String.join(",", hidden); }

    /** The set after a tap on a control: shown becomes hidden and the other way round; a hide that would leave no control visible changes nothing */
    static Set<String> toggle(Set<String> hidden, String key, Collection<String> all) {
        Set<String> out = new LinkedHashSet<>(hidden);
        if (!all.contains(key)) return out;
        if (out.remove(key)) return out;
        out.add(key);
        for (String k : all) if (!out.contains(k)) return out;
        return new LinkedHashSet<>(hidden);
    }
}
