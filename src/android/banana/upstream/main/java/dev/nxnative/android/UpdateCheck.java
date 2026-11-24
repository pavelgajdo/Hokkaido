package dev.nxnative.android;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The in-app updater's decisions, plain Java (no Android; checked on the JVM by dm/tools/tests/test_android_update.py with UpdateCheckJvmTest): where releases
 * are published (SOURCES: GitHub, then the Forgejo mirror at git.eden-emu.dev), their releases APIs' JSON as Release objects (parse), which release is newer than
 * this app across every source that answered (pick), the version numbers, the asset's host (only the source's own download hosts) and its SHA-256 digest (the
 * notes: ReleaseNotes).
 */
final class UpdateCheck {
    private UpdateCheck() {}

    /** One asset of a release, as the releases API lists it: name, browser_download_url, size, digest (null when the API gives none) */
    static final class Asset {
        final String name, url, digest; final long size;
        Asset(String name, String url, long size, String digest) { this.name = name; this.url = url; this.size = size; this.digest = digest; }
    }

    /**
     * Where releases are published: a releases API (https, a host fixed here), the hosts its assets' browser_download_url may name (a release whose APK link
     * names another host is never offered), and the name the popup shows. Both APIs list releases newest first with the same fields (tag_name, draft,
     * prerelease, body, assets[] with name, size, browser_download_url); only GitHub gives a digest.
     */
    static final class Source {
        final String name, api, accept, page; final List<String> assetHosts;
        Source(String name, String api, String accept, String page, String... assetHosts) {
            this.name = name; this.api = api; this.accept = accept; this.page = page; this.assetHosts = Arrays.asList(assetHosts);
        }
    }

    static final String GITHUB_HOST = "github.com", FORGEJO_HOST = "git.eden-emu.dev";

    /** GitHub's releases of owner/name: assets on github.com, which redirects to its object storage */
    static Source github(String repo) {
        return new Source("GitHub", "https://api." + GITHUB_HOST + "/repos/" + repo + "/releases?per_page=10", "application/vnd.github+json",
                "https://" + GITHUB_HOST + "/" + repo + "/releases", GITHUB_HOST, "objects.githubusercontent.com");
    }

    /** The Forgejo mirror's releases of owner/name (Gitea's API) */
    static Source forgejo(String repo) {
        return new Source(FORGEJO_HOST, "https://" + FORGEJO_HOST + "/api/v1/repos/" + repo + "/releases?limit=10", "application/json",
                "https://" + FORGEJO_HOST + "/" + repo + "/releases", FORGEJO_HOST);
    }

    static final String REPO = "moceure/BananaNative";

    /**
     * Every source the release app asks, in order. The same release is published on each; all are asked and the highest version wins, so a source that is down
     * or behind never hides a newer release, and on a tie the earlier source is taken. GitHub first (its digest lets the download be checked by SHA-256);
     * git.eden-emu.dev since GitHub suspended the moceure account (2026-11-08), so an app keeps finding updates while either one is unreachable.
     */
    static final List<Source> SOURCES = Arrays.asList(github(REPO), forgejo(REPO));

    /** One release: tag_name, draft, prerelease, body and its assets, and the source that listed it */
    static final class Release {
        final String tag, body; final boolean draft, prerelease; final List<Asset> assets; final Source source;
        Release(String tag, boolean draft, boolean prerelease, String body, List<Asset> assets, Source source) {
            this.tag = tag; this.draft = draft; this.prerelease = prerelease; this.body = body; this.assets = assets; this.source = source;
        }
        Release(String tag, boolean draft, boolean prerelease, String body, List<Asset> assets) { this(tag, draft, prerelease, body, assets, SOURCES.get(0)); }
    }

    /**
     * url with a cache-busting query parameter, {@code _=token}, appended with {@code ?} or {@code &} as the URL needs (a URL with a fragment gets it
     * before the {@code #}). Updater asks a source's releases list through such a URL, one token per request, so each check is its own cache key: a shared
     * cache in front of git.eden-emu.dev (Cloudflare and/or the server's own, {@code cache-control: public, max-age=180}, {@code x-cache-enabled: true})
     * served the list as of two hours before v0.2.3's publication to every phone for hours, while the same URL with another query answered the current
     * list; the updater said up to date. Source.api itself stays the plain URL (the docs, update.txt and allowedAsset know it as such).
     */
    static String busted(String url, String token) {
        if (url == null) return null;
        int hash = url.indexOf('#');
        String base = hash < 0 ? url : url.substring(0, hash), frag = hash < 0 ? "" : url.substring(hash);
        String sep = base.indexOf('?') < 0 ? "?" : base.endsWith("?") || base.endsWith("&") ? "" : "&";
        return base + sep + "_=" + token + frag;
    }

    /** A releases API's answer (a JSON array of releases) as Releases of source s; IllegalArgumentException when it is not one */
    static List<Release> parse(String json, Source s) {
        Object root = Json.parse(json);
        if (!(root instanceof List)) throw new IllegalArgumentException("not a list of releases");
        List<Release> out = new ArrayList<>();
        for (Object o : (List<?>) root) {
            if (!(o instanceof Map)) continue;
            Map<?, ?> r = (Map<?, ?>) o;
            List<Asset> assets = new ArrayList<>();
            Object as = r.get("assets");
            if (as instanceof List) for (Object x : (List<?>) as) {
                if (!(x instanceof Map)) continue;
                Map<?, ?> a = (Map<?, ?>) x;
                String n = Json.str(a, "name"), u = Json.str(a, "browser_download_url");
                assets.add(new Asset(n == null ? "" : n, u == null ? "" : u, Json.num(a, "size"), Json.str(a, "digest")));
            }
            String tag = Json.str(r, "tag_name"), body = Json.str(r, "body");
            out.add(new Release(tag == null ? "" : tag, Json.bool(r, "draft"), Json.bool(r, "prerelease"), body == null ? "" : body, assets, s));
        }
        return out;
    }

    /** Whether url is an https link on one of the source's asset hosts (no other port, no user part); a null source allows no host */
    static boolean allowedAsset(String url, Source s) {
        if (url == null || s == null) return false;
        URI u;
        try { u = new URI(url); } catch (URISyntaxException e) { return false; }
        if (!"https".equals(u.getScheme()) || u.getRawUserInfo() != null || (u.getPort() != -1 && u.getPort() != 443)) return false;
        String h = u.getHost();
        return h != null && s.assetHosts.contains(h.toLowerCase(Locale.ROOT));
    }

    /** The hosts besides the source's asset hosts that a GitHub release body's pictures come from (attachments redirect to the first two) */
    static final List<String> GITHUB_IMAGE_HOSTS = Arrays.asList("private-user-images.githubusercontent.com", "user-images.githubusercontent.com", "raw.githubusercontent.com");

    /**
     * Whether a picture of a release body may be fetched from url (also checked for every redirect): https, no other port, no user part, and a host of the
     * source's own (its asset hosts) or, for GitHub, one of GITHUB_IMAGE_HOSTS. A body is written by whoever may publish a release, but the page never sends
     * a request to a host the release check does not already trust.
     */
    static boolean allowedImage(String url, Source s) {
        if (allowedAsset(url, s)) return true;
        if (url == null || s == null || !s.assetHosts.contains(GITHUB_HOST)) return false;
        URI u;
        try { u = new URI(url); } catch (URISyntaxException e) { return false; }
        if (!"https".equals(u.getScheme()) || u.getRawUserInfo() != null || (u.getPort() != -1 && u.getPort() != 443)) return false;
        String h = u.getHost();
        return h != null && GITHUB_IMAGE_HOSTS.contains(h.toLowerCase(Locale.ROOT));
    }

    /** The update the popup offers: the release, its version and its APK */
    static final class Update {
        final Release release; final int[] version; final Asset apk; final String sha256;   // sha256: lower-case hex, null when the asset has no digest
        Update(Release r, int[] v, Asset a, String sha256) { release = r; version = v; apk = a; this.sha256 = sha256; }
        String tag() { return release.tag; }
        /** The version as players read it: the tag without its v ("0.3.47", "0.3.47-preview") */
        String label() { String t = release.tag.trim(); return t.startsWith("v") || t.startsWith("V") ? t.substring(1) : t; }
    }

    private static final Pattern TAG = Pattern.compile("[vV]?(\\d{1,6})\\.(\\d{1,6})\\.(\\d{1,6})");
    /** A preview release's tag, vX.Y.Z-preview: apps up to 0.3.45 read only vX.Y.Z tags, so they never offer one */
    private static final Pattern PREVIEW_TAG = Pattern.compile("[vV]?(\\d{1,6})\\.(\\d{1,6})\\.(\\d{1,6})-preview");
    private static final Pattern APP = Pattern.compile("[vV]?(\\d{1,6})\\.(\\d{1,6})\\.(\\d{1,6})(?:[^0-9].*)?");
    private static final Pattern HEX = Pattern.compile("[0-9a-fA-F]{64}");

    /** A release tag's version: exactly vX.Y.Z or X.Y.Z, else null (rc tags, names, dates are not releases the updater offers) */
    static int[] tagVersion(String tag) { return match(TAG, tag); }

    /** The app's own version (versionName): X.Y.Z, optionally followed by a suffix ("0.1.3", "0.1.1-test"), else null ("0.1-bringup": no update check) */
    static int[] appVersion(String name) { return match(APP, name); }

    /** Whether a release tag is a preview one (vX.Y.Z-preview) */
    static boolean previewTag(String tag) { return tag != null && PREVIEW_TAG.matcher(tag.trim()).matches(); }

    /** Whether the app itself is a preview build (versionName X.Y.Z-preview): the release X.Y.Z is then newer than it */
    static boolean previewApp(String name) { return name != null && name.trim().toLowerCase(Locale.ROOT).endsWith("-preview"); }

    private static int[] match(Pattern p, String s) {
        if (s == null) return null;
        Matcher m = p.matcher(s.trim());
        if (!m.matches()) return null;
        return new int[] {Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))};
    }

    /** Numeric comparison, part by part (0.1.10 is above 0.1.9) */
    static int compare(int[] a, int[] b) {
        for (int k = 0; k < 3; k++) if (a[k] != b[k]) return Integer.compare(a[k], b[k]);
        return 0;
    }

    static String text(int[] v) { return v[0] + "." + v[1] + "." + v[2]; }

    /** Marks an asset whose digest names SHA-256 but is not 64 hex digits: it cannot be verified, so it is never installed */
    static final String BAD_DIGEST = "";

    /**
     * The asset's digest field as a SHA-256: "sha256:&lt;64 hex&gt;" gives the hex in lower case; null when there is no digest or it names another algorithm
     * (nothing to check against: the size is still checked); BAD_DIGEST when it says sha256 but the value is malformed.
     */
    static String sha256(String digest) {
        if (digest == null) return null;
        String d = digest.trim();
        if (d.isEmpty()) return null;
        int c = d.indexOf(':');
        if (c < 0 || !d.substring(0, c).trim().equalsIgnoreCase("sha256")) return null;
        String hex = d.substring(c + 1).trim();
        return HEX.matcher(hex).matches() ? hex.toLowerCase(Locale.ROOT) : BAD_DIGEST;
    }

    /** The experimental Android 12 build (BuildConfig.NX_MIN_SDK below 33, set by NxApp): it takes only the release's asset named ...android12....apk, every other app never that one */
    static boolean android12 = false;
    static final String ANDROID12_MARK = "android12";

    /** A release's APK: the first asset whose name ends in .apk with an https download link on its source's asset hosts and a usable digest; null when it has none.
     *  An asset with "android12" in its name is the experimental Android 12 build's (a minSdk 31 APK): only that build takes it, and it takes no other */
    static Asset apk(Release r) {
        if (r.assets == null) return null;
        for (Asset a : r.assets) {
            if (a == null || a.name == null || a.url == null) continue;
            if (!a.name.toLowerCase(Locale.ROOT).endsWith(".apk") || !allowedAsset(a.url, r.source)) continue;
            if (a.name.toLowerCase(Locale.ROOT).contains(ANDROID12_MARK) != android12) continue;
            if (BAD_DIGEST.equals(sha256(a.digest))) continue;
            return a;
        }
        return null;
    }

    /**
     * The newest release above the app's version (current), or null: drafts are skipped, pre-releases count (every release of this app is one), tags that are not
     * vX.Y.Z are skipped, and so is a release without an APK. The highest version wins whatever the list's order; on a tie the first in the list (Updater lists
     * the sources in SOURCES order).
     */
    static Update pick(List<Release> releases, int[] current) { return pick(releases, current, false, HOTFIX); }

    private static final Pattern KIND = Pattern.compile("(?im)^[ \\t*_>#-]*update[ -]type[ \\t]*:[ \\t*_]*(hotfix|critical|major)\\b");

    /**
     * What kind of update a release is, from a line of its notes, "Update type: hotfix" (a small fix, optional), "Update type: critical" (a hotfix that
     * everyone should take: always offered) or "Update type: major" (the default, also for every older release without the line)
     */
    static String kind(Release r) {
        if (r != null && previewTag(r.tag)) return "preview";   // the tag decides, not the notes: a preview must never reach an app that reads only the notes
        Matcher m = r == null || r.body == null ? null : KIND.matcher(r.body);
        return m != null && m.find() ? m.group(1).toLowerCase(Locale.ROOT) : "major";
    }

    /**
     * pick, and with skipHotfix (the player turned "small fix updates" off) a release that is a hotfix is passed over: the newest major or critical one above
     * the app's version is offered instead, and a hotfix alone is never offered. The next major release carries every hotfix before it (an APK is the whole app)
     */
    static Update pick(List<Release> releases, int[] current, boolean skipHotfix) { return pick(releases, current, false, skipHotfix ? STABLE : HOTFIX); }

    /**
     * The newest release the channel offers (offers) above the app's version. A preview of X.Y.Z (vX.Y.Z-preview) comes before the release X.Y.Z: an app
     * that is that preview (currentPreview) is offered X.Y.Z, and on a tie the release wins over the preview
     */
    static Update pick(List<Release> releases, int[] current, boolean currentPreview, String channel) {
        if (releases == null || current == null) return null;
        Update best = null;
        for (Release r : releases) {
            if (r == null || r.draft) continue;
            boolean pv = previewTag(r.tag);
            int[] v = pv ? match(PREVIEW_TAG, r.tag) : tagVersion(r.tag);
            if (v == null) continue;
            int c = compare(v, current);
            if (c < 0 || c == 0 && !(currentPreview && !pv)) continue;
            if (best != null) { int b = compare(v, best.version); if (b < 0 || b == 0 && !(previewTag(best.tag()) && !pv)) continue; }
            if (!offers(channel, kind(r))) continue;
            Asset a = apk(r);
            if (a == null) continue;
            best = new Update(r, v, a, sha256(a.digest));
        }
        return best;
    }

    /**
     * Update channels (Settings > Updates > Update channel), a filter on the release notes' kind line, so no release, branch or tag changes on the server and
     * every older release (no line: major) is read as before. STABLE: major and critical releases (the weekly ones and the fixes everyone needs); HOTFIX: every
     * release, also the small fixes between weekly ones. The channel replaces the "Small fix updates" switch of 0.3.42 to 0.3.45 (Prefs "hotfix_updates",
     * on = HOTFIX, off = STABLE).
     */
    static final String STABLE = "stable", HOTFIX = "hotfix", PREVIEW = "preview";

    /** The channel of a player who never chose one and never touched the older switch: STABLE (from 0.3.46, the first weekly release). Players who did
     *  choose (the channel, or Small fix updates switched off or back on) keep their choice */
    static final String DEFAULT_CHANNEL = STABLE;

    /**
     * The player's channel: the stored one when it names a channel, else the older switch's choice when it was ever set (smallFixes: null when never written),
     * else DEFAULT_CHANNEL. So an update to this version keeps each player's choice without writing anything at start.
     */
    static String channel(String stored, Boolean smallFixes) {
        if (STABLE.equals(stored) || HOTFIX.equals(stored) || PREVIEW.equals(stored)) return stored;
        if (smallFixes != null) return smallFixes ? HOTFIX : STABLE;
        return DEFAULT_CHANNEL;
    }

    /** Whether a channel passes hotfix releases over (pick's skipHotfix) */
    static boolean skipsHotfix(String channel) { return STABLE.equals(channel); }

    /** Whether a channel offers a release of this kind: Stable major and critical; Hotfix also hotfix; Preview every release, also previews (tags vX.Y.Z-preview) */
    static boolean offers(String channel, String kind) {
        if ("preview".equals(kind)) return PREVIEW.equals(channel);
        if ("hotfix".equals(kind)) return !STABLE.equals(channel);
        return true;
    }

    /** The newest release across the sources' lists (null for a source that failed): pick on them all, in order, so a tie goes to the earlier source */
    static Update pickAll(List<List<Release>> perSource, int[] current) { return pickAll(perSource, current, false, HOTFIX); }

    static Update pickAll(List<List<Release>> perSource, int[] current, boolean skipHotfix) { return pickAll(perSource, current, false, skipHotfix ? STABLE : HOTFIX); }

    static Update pickAll(List<List<Release>> perSource, int[] current, boolean currentPreview, String channel) {
        if (perSource == null) return null;
        List<Release> all = new ArrayList<>();
        for (List<Release> l : perSource) if (l != null) all.addAll(l);
        return pick(all, current, currentPreview, channel);
    }

    /**
     * What a channel other than the player's offers beyond the player's own pick (own: pickAll on their channel), for Settings' Update channel row: the
     * newest release of any kind (the Preview channel's pick) when it is not the player's own, else null. On Preview there is nothing beyond
     */
    static Update elsewhere(List<List<Release>> perSource, int[] current, boolean currentPreview, String channel, Update own) {
        if (PREVIEW.equals(channel)) return null;
        Update all = pickAll(perSource, current, currentPreview, PREVIEW);
        return all == null || own != null && all.tag().equals(own.tag()) ? null : all;
    }

    /** The channel that offers a release first: Preview for a preview, Hotfix for a hotfix, else Stable */
    static String channelFor(Release r) {
        String k = kind(r);
        return "preview".equals(k) ? PREVIEW : "hotfix".equals(k) ? HOTFIX : STABLE;
    }

    /** Whether Home's Settings button shows the update dot: an update of the player's channel waits, and either Settings was not opened since it came
     *  (seenTag: the tag the player last saw there) or it is critical (then the dot stays until it is installed) */
    static boolean dot(Update pending, String seenTag) {
        return pending != null && ("critical".equals(kind(pending.release)) || !pending.tag().equals(seenTag));
    }

    /** "12.3 MB" */
    static String mb(long bytes) { return String.format(Locale.US, "%.1f MB", bytes / 1048576.0); }
}
