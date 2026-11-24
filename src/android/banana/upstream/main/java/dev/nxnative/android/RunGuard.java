package dev.nxnative.android;

/**
 * One game per :game process, and no :game process without its screen (android/README.md, "The game's task"; docs/reference/threads.md, "Two game instances").
 * The decisions GameActivity takes, without Android, for android/app/src/jvmtest's RunGuardJvmTest.
 * <p>
 * The host starts once per process and cannot be unloaded (Native.load refuses a second title), so a screen created in a process whose host runs another game
 * must show that game and say "stop it first", never load or attach to it as the game it was asked for. And a game screen that goes for good (finished, not
 * recreated for a configuration change) ends its process as Stop does: a host left running without a screen would hold the save's lock, its memory, and the
 * process the next Play lands in.
 */
final class RunGuard {
    private RunGuard() {}

    /** A new game screen asked for want while this process's host runs running (null: no host runs): true when it is another game */
    static boolean otherRunning(boolean hostRunning, String running, String want) {
        return hostRunning && running != null && !running.equals(want);
    }

    /** GameActivity.onDestroy: whether the screen goes for good, so the game's process ends with it (not for a screen Android recreates) */
    static boolean endsProcess(boolean finishing, boolean changingConfigurations) {
        return finishing && !changingConfigurations;
    }
}
