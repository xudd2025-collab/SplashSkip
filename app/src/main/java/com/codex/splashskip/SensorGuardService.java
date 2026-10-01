package com.codex.splashskip;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Runs as the Shizuku shell user. Each guard owns an independent timed restore process. */
public final class SensorGuardService extends ISensorGuard.Stub {
    private final Map<String, Process> active = new ConcurrentHashMap<>();

    public SensorGuardService() { }

    private static String target(String pkg, int user) {
        if (pkg == null || !pkg.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+") ||
                user < 0 || user > 100000) throw new IllegalArgumentException("Invalid app target");
        return pkg + " --user " + user;
    }

    private static void reset(String target) {
        try {
            new ProcessBuilder("sh", "-c", "cmd sensorservice reset-uid-state " + target)
                    .redirectErrorStream(true).start().waitFor(2, TimeUnit.SECONDS);
        } catch (Exception ignored) { }
    }

    @Override public synchronized String protect(String pkg, int user) {
        String target = target(pkg, user);
        Process previous = active.get(target);
        if (previous != null && previous.isAlive()) return "already-active";
        try {
            // The shell lives independently of the app and resets the override after six seconds.
            // EXIT also restores on a normal stop or a catchable termination signal.
            String restore = "cmd sensorservice reset-uid-state " + target + " >/dev/null 2>&1";
            String script = "trap '" + restore + "' EXIT\ntrap 'exit' HUP INT TERM\n" +
                    "cmd sensorservice set-uid-state " + pkg + " idle --user " + user +
                    " || { echo ERROR; exit 1; }\n" +
                    "state=$(cmd sensorservice get-uid-state " + target + ")\n" +
                    "if [ \"$state\" != idle ]; then echo ERROR; exit 1; fi\n" +
                    "echo PROTECTED\n" +
                    "sleep 6\n";
            Process process = new ProcessBuilder("sh", "-c", script).redirectErrorStream(true).start();
            active.put(target, process);
            CompletableFuture<String> ready = CompletableFuture.supplyAsync(() -> {
                try {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.equals("PROTECTED")) return "protected";
                        if (line.equals("ERROR") || line.contains("Permission Denial") ||
                                line.contains("Unknown command")) return "error:" + line;
                    }
                    return "error:no confirmation";
                } catch (Exception error) { return "error:" + error.getClass().getSimpleName(); }
            });
            Thread cleanup = new Thread(() -> {
                boolean forced = false;
                try {
                    if (!process.waitFor(8, TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                        forced = true;
                    }
                } catch (InterruptedException ignored) {
                    forced = true;
                    Thread.currentThread().interrupt();
                }
                if (active.remove(target, process) && forced) reset(target);
            }, "sensor-restore");
            cleanup.start();
            String result = ready.get(1500, TimeUnit.MILLISECONDS);
            if (!result.equals("protected")) {
                process.destroy();
                reset(target);
            }
            return result;
        } catch (Exception error) {
            Process process = active.remove(target);
            if (process != null) process.destroy();
            reset(target);
            return "error:" + error.getClass().getSimpleName();
        }
    }

    @Override public String getState(String pkg, int user) {
        String target = target(pkg, user);
        try {
            Process process = new ProcessBuilder("sh", "-c", "cmd sensorservice get-uid-state " + target)
                    .redirectErrorStream(true).start();
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return "unknown";
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line = reader.readLine();
            return line == null ? "unknown" : line;
        } catch (Exception error) { return "unknown"; }
    }

    @Override public synchronized void restoreAll() {
        for (Map.Entry<String, Process> entry : active.entrySet()) {
            entry.getValue().destroy();
            try {
                if (!entry.getValue().waitFor(200, TimeUnit.MILLISECONDS)) {
                    entry.getValue().destroyForcibly();
                    entry.getValue().waitFor(500, TimeUnit.MILLISECONDS);
                }
            } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            active.remove(entry.getKey(), entry.getValue());
            reset(entry.getKey());
        }
    }

    @Override public void destroy() {
        restoreAll();
        System.exit(0);
    }
}
