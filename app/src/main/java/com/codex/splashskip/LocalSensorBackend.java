package com.codex.splashskip;

import java.util.HashSet;
import java.util.Set;

/** Timed shell children restore sensors even if the app or its ADB connection disappears. */
final class LocalSensorBackend {
    private final LocalAdbController adb;
    private final Set<String> targets = new HashSet<>();
    LocalSensorBackend(LocalAdbController adb) { this.adb = adb; }
    private static String target(String pkg, int user) {
        if (pkg == null || !pkg.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+") || user < 0 || user > 100000)
            throw new IllegalArgumentException("Invalid target");
        return pkg + " --user " + user;
    }
    private static String lock(String pkg, int user) {
        target(pkg, user);
        return "/data/local/tmp/splashskip-guard-" + user + "-" + pkg;
    }
    private static String quote(String text) { return "'" + text.replace("'", "'\\''") + "'"; }
    String protect(String pkg, int user) throws Exception {
        String target = target(pkg, user), lock = lock(pkg, user);
        String clean = "rm -f " + lock + "/ready " + lock + "/pid; rmdir " + lock + " 2>/dev/null";
        String reset = "cmd sensorservice reset-uid-state " + target + " >/dev/null 2>&1; " + clean;
        String child = "trap " + quote(reset) + " EXIT; trap 'exit' HUP INT TERM; " +
                "echo $$ > " + lock + "/pid; " +
                "cmd sensorservice set-uid-state " + pkg + " idle --user " + user + " >/dev/null 2>&1 || " +
                "{ echo ERROR > " + lock + "/ready; sleep 1; exit 1; }; " +
                "if [ \"$(cmd sensorservice get-uid-state " + target + ")\" != idle ]; then " +
                "echo ERROR > " + lock + "/ready; sleep 1; exit 1; fi; " +
                "echo PROTECTED > " + lock + "/ready; sleep 6";
        String command = "if [ -d " + lock + " ]; then " +
                "pid=$(cat " + lock + "/pid 2>/dev/null); " +
                "if [ -n \"$pid\" ] && kill -0 \"$pid\" 2>/dev/null; then echo already-active; exit; fi; " + clean + "; fi; " +
                "mkdir " + lock + " 2>/dev/null || { echo already-active; exit; }; " +
                "nohup sh -c " + quote(child) + " </dev/null >/dev/null 2>&1 & " +
                "i=0; while [ ! -f " + lock + "/ready ] && [ $i -lt 15 ]; do sleep 0.1; i=$((i+1)); done; " +
                "if [ -f " + lock + "/ready ]; then cat " + lock + "/ready; else echo ERROR; fi";
        String result = adb.shell(command, 3500);
        if (result.equals("PROTECTED")) { targets.add(target); return "protected"; }
        if (result.equals("already-active")) { targets.add(target); return result; }
        return "error:" + result;
    }
    String getState(String pkg, int user) throws Exception {
        return adb.shell("cmd sensorservice get-uid-state " + target(pkg, user), 2500);
    }
    void restoreAll() throws Exception {
        Exception failure = null;
        for (String target : targets) {
            String[] parts = target.split(" ");
            String lock = lock(parts[0], Integer.parseInt(parts[2]));
            try {
                adb.shell("pid=$(cat " + lock + "/pid 2>/dev/null); " +
                        "if [ -n \"$pid\" ]; then kill -TERM \"$pid\" 2>/dev/null; fi; " +
                        "cmd sensorservice reset-uid-state " + target + "; echo RESTORED", 2500);
            } catch (Exception error) { failure = error; }
        }
        targets.clear();
        if (failure != null) throw failure;
    }
}
