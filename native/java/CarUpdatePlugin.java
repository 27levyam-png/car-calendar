package com.betzalel.carcalendar;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import androidx.core.content.FileProvider;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** הורדת גרסה חדשה מ-GitHub ופתיחת מסך ההתקנה */
@CapacitorPlugin(name = "CarUpdate")
public class CarUpdatePlugin extends Plugin {

    @PluginMethod
    public void install(PluginCall call) {
        final String url = call.getString("url");
        if (url == null || url.isEmpty()) { call.reject("no url"); return; }

        if (Build.VERSION.SDK_INT >= 26 && !getContext().getPackageManager().canRequestPackageInstalls()) {
            Intent s = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getContext().getPackageName()));
            s.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(s);
            JSObject r = new JSObject();
            r.put("needPermission", true);
            call.resolve(r);
            return;
        }

        new Thread(() -> {
            try {
                File f = new File(getContext().getCacheDir(), "update.apk");
                String u = url;
                HttpURLConnection h = null;
                for (int i = 0; i < 6; i++) {   // מעקב אחרי הפניות של GitHub
                    h = (HttpURLConnection) new URL(u).openConnection();
                    h.setInstanceFollowRedirects(false);
                    h.setConnectTimeout(20000);
                    h.setReadTimeout(60000);
                    int code = h.getResponseCode();
                    if (code >= 300 && code < 400) {
                        u = new URL(new URL(u), h.getHeaderField("Location")).toString();
                        h.disconnect();
                        continue;
                    }
                    if (code >= 300) throw new IOException("HTTP " + code);
                    break;
                }
                try (InputStream in = h.getInputStream(); OutputStream out = new FileOutputStream(f)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                h.disconnect();

                Uri apk = FileProvider.getUriForFile(getContext(), getContext().getPackageName() + ".fileprovider", f);
                Intent inst = new Intent(Intent.ACTION_VIEW);
                inst.setDataAndType(apk, "application/vnd.android.package-archive");
                inst.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                getContext().startActivity(inst);

                JSObject r = new JSObject();
                r.put("ok", true);
                call.resolve(r);
            } catch (Exception e) {
                call.reject(String.valueOf(e.getMessage()));
            }
        }).start();
    }
}
