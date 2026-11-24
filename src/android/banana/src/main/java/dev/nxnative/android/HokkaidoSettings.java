// SPDX-FileCopyrightText: Copyright 2026 Hokkaido contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.nxnative.android;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.util.Locale;

/** Opens Banana's settings inside the host app, without its launcher or a second game start path. */
public final class HokkaidoSettings {
    private static final String TITLE_ID = "hokkaido_settings_title_id";
    private static final String NAME = "hokkaido_settings_name";
    private static final String GAME_PATH = "hokkaido_settings_game_path";

    private HokkaidoSettings() {}

    /** A null title ID opens defaults; a game opens its own performance and controls. */
    public static void open(Context context, String titleId, String name, String gamePath) {
        Intent intent = new Intent(context, MainActivity.class);
        if (titleId != null) {
            intent.putExtra(TITLE_ID, titleId).putExtra(NAME, name).putExtra(GAME_PATH, gamePath);
        }
        if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    /** Rebuilt from the launch intent too, so process recreation does not depend on Banana's game list. */
    static Game game(Context context, Intent intent) {
        String titleId = intent.getStringExtra(TITLE_ID);
        String path = intent.getStringExtra(GAME_PATH);
        if (titleId == null || !titleId.matches("[0-9a-fA-F]{16}") || path == null || path.isEmpty()) return null;
        titleId = titleId.toUpperCase(Locale.ROOT);
        for (Game game : Game.load(context)) {
            if (game.titleId.equalsIgnoreCase(titleId) && game.gameDir.equals(path)) return game;
        }
        String name = intent.getStringExtra(NAME);
        return new Game(titleId, name == null || name.isEmpty() ? titleId : name, path,
                AppStorage.pack(context, titleId).getPath());
    }

    static void rows(MainActivity activity, LinearLayout parent, Game game, Runnable changed) {
        Ui.add(parent, Ui.small(activity, Tx.s(R.string.fusion_banana_settings_notice)), 10);
        if (game == null) return;
        LinearLayout card = Ui.add(parent, Ui.listCard(activity), 10);
        card.addView(new Ui.Setting(activity, "⚙", Tx.s(R.string.fusion_banana_global_settings),
                v -> open(activity, null, null, null)).chevron(activity).view);
        card.addView(new Ui.Setting(activity, "↺", Tx.s(R.string.fusion_banana_use_recommended), v ->
                new PadFocus.Builder(activity)
                        .setTitle(Tx.s(R.string.fusion_banana_use_recommended))
                        .setMessage(Tx.s(R.string.fusion_banana_recommended_confirm))
                        .setPositiveButton(Tx.s(R.string.game_use_recommended), (dialog, which) -> {
                            HokkaidoBridge.useRecommendedSettings(activity, game.titleId);
                            changed.run();
                            Toast.makeText(activity, Tx.s(R.string.fusion_banana_recommended_done), Toast.LENGTH_LONG).show();
                        })
                        .setNegativeButton(Tx.s(R.string.common_cancel), null).show()).chevron(activity).view);
    }
}
