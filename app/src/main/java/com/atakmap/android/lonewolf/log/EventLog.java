package com.atakmap.android.lonewolf.log;

import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Date;
import java.util.Deque;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Audit log. Every event is written to logcat and appended to a daily file:
 * {@code <atak>/tools/lonewolf/logs/lonewolf-YYYY-MM-DD.log} (UTC). Files older
 * than {@link #KEEP_DAYS} days are deleted.
 */
public final class EventLog {

    public static final String TAG = "LoneWolf";
    private static final int KEEP_DAYS = 90;

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "lonewolf-log");
        t.setDaemon(true);
        return t;
    });

    private static volatile File dir;

    private EventLog() {
    }

    public static void init(File logDir) {
        dir = logDir;
        IO.execute(() -> {
            if (!logDir.exists() && !logDir.mkdirs())
                Log.w(TAG, "could not create log dir " + logDir);
            prune();
        });
    }

    public static File getDirectory() {
        return dir;
    }

    public static void i(String category, String msg) {
        write("INFO", category, msg);
    }

    public static void w(String category, String msg) {
        write("WARN", category, msg);
    }

    public static void e(String category, String msg, Throwable t) {
        write("ERROR", category, msg + (t != null ? " : " + t : ""));
        if (t != null)
            Log.e(TAG, msg, t);
    }

    private static void write(String level, String category, String msg) {
        final long now = System.currentTimeMillis();
        final String line = iso(now) + " " + level + " [" + category + "] " + msg;
        if ("WARN".equals(level))
            Log.w(TAG, line);
        else
            Log.i(TAG, line);
        final File d = dir;
        if (d == null)
            return;
        IO.execute(() -> {
            try (Writer w = new FileWriter(new File(d, fileName(now)), true)) {
                w.write(line);
                w.write('\n');
            } catch (IOException e) {
                Log.w(TAG, "log write failed", e);
            }
        });
    }

    /** Last {@code n} lines of today's log (blocking; call off the UI thread if large). */
    public static String tail(int n) {
        File d = dir;
        if (d == null)
            return "";
        File f = new File(d, fileName(System.currentTimeMillis()));
        if (!f.exists())
            return "";
        Deque<String> lines = new ArrayDeque<>(n);
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String l;
            while ((l = r.readLine()) != null) {
                if (lines.size() == n)
                    lines.removeFirst();
                lines.addLast(l);
            }
        } catch (IOException e) {
            return "Unable to read log: " + e;
        }
        StringBuilder sb = new StringBuilder();
        for (String l : lines)
            sb.append(l).append('\n');
        return sb.toString();
    }

    public static String iso(long t) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(t));
    }

    private static String fileName(long t) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return "lonewolf-" + f.format(new Date(t)) + ".log";
    }

    private static void prune() {
        File d = dir;
        if (d == null)
            return;
        File[] files = d.listFiles((x, name) -> name.startsWith("lonewolf-") && name.endsWith(".log"));
        if (files == null)
            return;
        long cutoff = System.currentTimeMillis() - KEEP_DAYS * 24L * 3600_000L;
        Arrays.sort(files);
        for (File f : files) {
            if (f.lastModified() < cutoff && !f.delete())
                Log.w(TAG, "could not delete old log " + f);
        }
    }
}
