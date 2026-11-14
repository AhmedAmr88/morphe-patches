package org.ungoogled.samsungphone;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.provider.MediaStore;
import android.widget.Toast;

import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Temporary diagnostics: saves this app's own log (an app may always read its own) to Downloads, so the dial pad's
 * layout, dual SIM and search decisions, which Samsung Phone logs, can be read without a computer.
 */
public final class LogDump {
    private LogDump() {
    }

    public static void save() {
        Context app = Calls.app();
        if (app == null) return;
        new Thread(() -> {
            String result;
            try {
                java.lang.Process logcat = Runtime.getRuntime().exec(new String[]{
                        "logcat", "-d", "-v", "time", "--pid=" + Process.myPid()});
                String name = "samsung-dialer-log-"
                        + new SimpleDateFormat("HHmmss", Locale.US).format(new Date()) + ".txt";
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                values.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/");
                ContentResolver resolver = app.getContentResolver();
                Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri == null) throw new IllegalStateException("no file");
                try (InputStream in = logcat.getInputStream(); OutputStream out = resolver.openOutputStream(uri)) {
                    byte[] buffer = new byte[16384];
                    int n;
                    while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                }
                result = "Log saved: Download/" + name;
            } catch (Exception e) {
                result = "Log failed: " + e;
            }
            String text = result;
            new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(app, text, Toast.LENGTH_LONG).show());
        }, "UA-log").start();
    }
}
