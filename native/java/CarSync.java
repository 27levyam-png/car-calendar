package com.betzalel.carcalendar;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.widget.RemoteViews;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** סנכרון ברקע: התראות על הזמנות חדשות + עדכון הווידג'ט */
public class CarSync {
    static final String DEF_URL = "@@URL@@";
    static final String DEF_KEY = "@@KEY@@";
    static final String CHANNEL = "car_bookings";
    static final String[] DAYS = {"א׳", "ב׳", "ג׳", "ד׳", "ה׳", "ו׳", "ש׳"};

    static class Item {
        String key, driver, purpose;
        long start, end;
    }

    // ---------- הגדרות ----------
    static SharedPreferences cap(Context c) { return c.getSharedPreferences("CapacitorStorage", Context.MODE_PRIVATE); }
    static SharedPreferences own(Context c) { return c.getSharedPreferences("car_sync", Context.MODE_PRIVATE); }
    static String url(Context c) {
        String u = cap(c).getString("car_url", "");
        if (u == null || u.isEmpty()) u = DEF_URL;
        return u.replaceAll("/+$", "");
    }
    static String key(Context c) {
        String k = cap(c).getString("car_key", "");
        return (k == null || k.isEmpty()) ? DEF_KEY : k;
    }

    public static void schedule(Context c) {
        PeriodicWorkRequest req = new PeriodicWorkRequest.Builder(CarWorker.class, 15, TimeUnit.MINUTES)
                .setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build();
        WorkManager.getInstance(c).enqueueUniquePeriodicWork("car_sync", ExistingPeriodicWorkPolicy.KEEP, req);
    }

    // ---------- רשת ----------
    static JSONArray get(Context c, String path) throws IOException, JSONException {
        HttpURLConnection h = (HttpURLConnection) new URL(url(c) + "/rest/v1/" + path).openConnection();
        h.setConnectTimeout(15000);
        h.setReadTimeout(15000);
        String k = key(c);
        h.setRequestProperty("apikey", k);
        if (k.startsWith("eyJ")) h.setRequestProperty("Authorization", "Bearer " + k);
        try {
            if (h.getResponseCode() >= 300) throw new IOException("HTTP " + h.getResponseCode());
            BufferedReader r = new BufferedReader(new InputStreamReader(h.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();
            return new JSONArray(sb.toString());
        } finally {
            h.disconnect();
        }
    }

    // ---------- נקודת כניסה ----------
    public static synchronized void sync(Context c, boolean notify) {
        SharedPreferences p = own(c);
        String bs, rs;
        try {
            if (url(c).isEmpty() || key(c).isEmpty()) throw new IOException("no server");
            Calendar now = Calendar.getInstance();
            JSONArray b = get(c, "car_bookings?day=gte." + ymd(shift(now, -3)) + "&day=lte." + ymd(shift(now, 62))
                    + "&order=day.asc,start_min.asc");
            JSONArray r = get(c, "car_recurring?select=*");
            bs = b.toString();
            rs = r.toString();
            p.edit().putString("cache_b", bs).putString("cache_r", rs).putLong("cache_t", System.currentTimeMillis()).apply();
            if (notify) {
                try { checkNew(c, build(b, r, now)); } catch (Exception ignore) { }
            }
        } catch (Exception e) {
            bs = p.getString("cache_b", null);
            rs = p.getString("cache_r", null);
        }
        try { render(c, bs, rs); } catch (Exception ignore) { }
    }

    // ---------- בניית רשימת תפיסות ----------
    static List<Item> build(JSONArray b, JSONArray r, Calendar now) throws JSONException {
        Map<String, Item> groups = new LinkedHashMap<>();
        for (int i = 0; i < b.length(); i++) {
            JSONObject o = b.getJSONObject(i);
            long s = at(o.getString("day"), o.getInt("start_min"));
            long e = at(o.getString("day"), o.getInt("end_min"));
            String k = o.isNull("grp") ? "b" + o.getLong("id") : "g" + o.getString("grp");
            Item it = groups.get(k);
            if (it == null) {
                it = new Item();
                it.key = k;
                it.driver = o.getString("driver");
                it.purpose = o.getString("purpose");
                it.start = s;
                it.end = e;
                groups.put(k, it);
            } else {
                it.start = Math.min(it.start, s);
                it.end = Math.max(it.end, e);
            }
        }
        List<Item> out = new ArrayList<>(groups.values());
        for (int i = 0; i < r.length(); i++) {
            JSONObject o = r.getJSONObject(i);
            int wd = o.getInt("weekday");
            for (int d = -1; d <= 8; d++) {
                Calendar x = shift(now, d);
                if (x.get(Calendar.DAY_OF_WEEK) - 1 != wd) continue;
                String day = ymd(x);
                Item it = new Item();
                it.key = "r" + o.getLong("id") + day;
                it.driver = o.getString("driver");
                it.purpose = o.getString("purpose");
                it.start = at(day, o.getInt("start_min"));
                it.end = at(day, o.getInt("end_min"));
                out.add(it);
            }
        }
        return out;
    }

    // ---------- התראות ----------
    static void checkNew(Context c, List<Item> items) throws IOException, JSONException {
        SharedPreferences p = own(c);
        long last = p.getLong("last_id", -1);
        if (last < 0) {   // הפעלה ראשונה – לא מציפים בהתראות ישנות
            JSONArray m = get(c, "car_bookings?select=id&order=id.desc&limit=1");
            p.edit().putLong("last_id", m.length() > 0 ? m.getJSONObject(0).getLong("id") : 0).apply();
            return;
        }
        JSONArray n = get(c, "car_bookings?id=gt." + last + "&order=id.asc");
        if (n.length() == 0) return;
        String me = cap(c).getString("car_user", "");
        if (me == null) me = "";
        Map<String, List<JSONObject>> groups = new LinkedHashMap<>();
        long max = last;
        for (int i = 0; i < n.length(); i++) {
            JSONObject o = n.getJSONObject(i);
            max = Math.max(max, o.getLong("id"));
            String k = o.isNull("grp") ? "b" + o.getLong("id") : "g" + o.getString("grp");
            List<JSONObject> l = groups.get(k);
            if (l == null) { l = new ArrayList<>(); groups.put(k, l); }
            l.add(o);
        }
        p.edit().putLong("last_id", max).apply();
        ensureChannel(c);
        for (Map.Entry<String, List<JSONObject>> en : groups.entrySet()) {
            List<JSONObject> g = en.getValue();
            JSONObject a = g.get(0), z = g.get(g.size() - 1);
            String drv = a.getString("driver");
            if (drv.equals(me)) continue;
            boolean fam = "family".equals(a.getString("purpose"));
            long s = at(a.getString("day"), a.getInt("start_min"));
            long e = at(z.getString("day"), z.getInt("end_min"));
            String title = "🚗 " + drv + " " + (isFemale(drv) ? "הזמינה" : "הזמין") + " את הרכב";
            String text = when(s, e) + " · " + (fam ? "משפחה / אמא" : "פרטי");
            if (fam && !me.isEmpty()) {
                for (Item it : items) {
                    if (me.equals(it.driver) && "private".equals(it.purpose) && !it.key.equals(en.getKey())
                            && it.start < e && it.end > s) {
                        text += "\n⚠️ חופף לזמן שהזמנת – צורך משפחה קודם";
                        break;
                    }
                }
            }
            post(c, (int) (a.getLong("id") % Integer.MAX_VALUE), title, text);
        }
    }

    static boolean isFemale(String n) { return n.equals("בת שבע") || n.equals("צופיה"); }

    static void ensureChannel(Context c) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "הזמנות רכב", NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription("הודעה כשמישהו מזמין את הרכב");
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    static PendingIntent openApp(Context c) {
        Intent i = new Intent(c, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(c, 0, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static void post(Context c, int id, String title, String text) {
        if (Build.VERSION.SDK_INT >= 33
                && c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        NotificationCompat.Builder nb = new NotificationCompat.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_car)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(openApp(c))
                .setAutoCancel(true);
        try { NotificationManagerCompat.from(c).notify(id, nb.build()); } catch (SecurityException ignore) { }
    }

    // ---------- ווידג'ט ----------
    static void render(Context c, String bs, String rs) throws JSONException {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        int[] ids = m.getAppWidgetIds(new ComponentName(c, CarWidget.class));
        if (ids == null || ids.length == 0) return;
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.car_widget);
        v.setOnClickPendingIntent(R.id.w_root, openApp(c));
        if (bs == null || rs == null) {
            v.setTextViewText(R.id.w_name, "אין נתונים");
            v.setTextViewText(R.id.w_when, "פתחו את האפליקציה");
            v.setTextViewText(R.id.w_count, "");
            v.setTextViewText(R.id.w_now, "");
            v.setTextViewText(R.id.w_upd, "");
            m.updateAppWidget(ids, v);
            return;
        }
        long now = System.currentTimeMillis();
        List<Item> items = build(new JSONArray(bs), new JSONArray(rs), Calendar.getInstance());
        Item cur = null, next = null;
        for (Item it : items) {
            if (it.end <= now) continue;
            if (it.start <= now) {
                if (cur == null || "family".equals(it.purpose)) cur = it;
            } else if (next == null || it.start < next.start) {
                next = it;
            }
        }
        if (next != null) {
            v.setTextViewText(R.id.w_title, "הבא ברכב");
            v.setTextViewText(R.id.w_name, next.driver);
            v.setTextViewText(R.id.w_when, when(next.start, next.end) + " · " + ("family".equals(next.purpose) ? "משפחה" : "פרטי"));
            v.setTextViewText(R.id.w_count, countdown(next.start - now));
            v.setInt(R.id.w_root, "setBackgroundResource",
                    "family".equals(next.purpose) ? R.drawable.widget_bg_fam : R.drawable.widget_bg_pri);
        } else {
            v.setTextViewText(R.id.w_title, "יומן הרכב");
            v.setTextViewText(R.id.w_name, "הרכב פנוי");
            v.setTextViewText(R.id.w_when, "אין הזמנות קרובות");
            v.setTextViewText(R.id.w_count, "");
            v.setInt(R.id.w_root, "setBackgroundResource", R.drawable.widget_bg);
        }
        v.setTextViewText(R.id.w_now, cur != null ? "כרגע ברכב: " + cur.driver + " עד " + hmEnd(cur.end) : "");
        v.setTextViewText(R.id.w_upd, "עודכן " + hm(cal(own(c).getLong("cache_t", now))));
        m.updateAppWidget(ids, v);
    }

    static String countdown(long ms) {
        long min = Math.max(0, ms / 60000);
        long d = min / 1440, h = (min % 1440) / 60;
        if (d > 0) {
            String ds = d == 1 ? "יום" : d == 2 ? "יומיים" : d + " ימים";
            String hs = h == 0 ? "" : h == 1 ? " ושעה" : h == 2 ? " ושעתיים" : " ו-" + h + " שעות";
            return "בעוד " + ds + hs;
        }
        if (h > 0) return "בעוד " + (h == 1 ? "שעה" : h == 2 ? "שעתיים" : h + " שעות");
        return "בפחות משעה";
    }

    // ---------- תאריכים ----------
    static Calendar cal(long ms) { Calendar x = Calendar.getInstance(); x.setTimeInMillis(ms); return x; }
    static Calendar shift(Calendar base, int days) { Calendar x = (Calendar) base.clone(); x.add(Calendar.DATE, days); return x; }
    static String ymd(Calendar x) {
        return String.format(Locale.US, "%04d-%02d-%02d", x.get(Calendar.YEAR), x.get(Calendar.MONTH) + 1, x.get(Calendar.DAY_OF_MONTH));
    }
    static long at(String day, int min) {
        String[] p = day.substring(0, 10).split("-");
        Calendar x = Calendar.getInstance();
        x.clear();
        x.set(Integer.parseInt(p[0]), Integer.parseInt(p[1]) - 1, Integer.parseInt(p[2]), 0, 0, 0);
        return x.getTimeInMillis() + min * 60000L;
    }
    static String hm(Calendar x) { return String.format(Locale.US, "%02d:%02d", x.get(Calendar.HOUR_OF_DAY), x.get(Calendar.MINUTE)); }
    static String hmEnd(long e) {
        Calendar x = cal(e);
        return (x.get(Calendar.HOUR_OF_DAY) == 0 && x.get(Calendar.MINUTE) == 0) ? "24:00" : hm(x);
    }
    static String dayLabel(Calendar x) {
        Calendar t = Calendar.getInstance();
        if (ymd(x).equals(ymd(t))) return "היום";
        if (ymd(x).equals(ymd(shift(t, 1)))) return "מחר";
        return "יום " + DAYS[x.get(Calendar.DAY_OF_WEEK) - 1] + " " + x.get(Calendar.DAY_OF_MONTH) + "/" + (x.get(Calendar.MONTH) + 1);
    }
    static String when(long s, long e) {
        Calendar a = cal(s), b = cal(e - 1);
        if (ymd(a).equals(ymd(b))) return dayLabel(a) + " " + hm(a) + "–" + hmEnd(e);
        return dayLabel(a) + " " + hm(a) + " עד " + dayLabel(b) + " " + hmEnd(e);
    }
}
