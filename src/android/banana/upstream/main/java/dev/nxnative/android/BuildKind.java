package dev.nxnative.android;

import java.util.Locale;

/**
 * Which kind of release the running app is: "preview" (a vX.Y.Z-preview build), "hotfix", "critical" or "major" (a weekly release), or "" when unknown (a test
 * build). The release is built with -PnxReleaseKind=<the release notes' Update type>, a preview also by its versionName ending in "-preview"
 * (BuildConfig.NX_RELEASE_KIND, read by Prefs.buildKind; android/README.md). Pure functions, so the JVM tests reach them without Android
 */
final class BuildKind {
    private BuildKind() {}

    /** The kind from a version name and the build's release kind ("" when none was given): a "-preview" name is a preview; else the given kind when it is one of the four, else "" */
    static String of(String versionName, String releaseKind) {
        if (versionName != null && versionName.trim().toLowerCase(Locale.ROOT).endsWith("-preview")) return "preview";
        String k = releaseKind == null ? "" : releaseKind.trim().toLowerCase(Locale.ROOT);
        return k.equals("preview") || k.equals("hotfix") || k.equals("critical") || k.equals("major") ? k : "";
    }

    /** The version without a "-preview" or other suffix: "0.3.51-preview" gives "0.3.51" */
    static String base(String versionName) {
        int d = versionName.indexOf('-');
        return d > 0 ? versionName.substring(0, d) : versionName;
    }

    /** A hotfix or a critical release: a small fix between weekly releases */
    static boolean isHotfix(String kind) { return "hotfix".equals(kind) || "critical".equals(kind); }

    /** The kind as one short token for a log line (", hotfix build"), or "" when it is a weekly release or unknown */
    static String logNote(String kind) { return kind.isEmpty() || kind.equals("major") ? "" : ", " + kind + " build"; }
}
