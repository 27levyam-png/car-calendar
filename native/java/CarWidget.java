package com.betzalel.carcalendar;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;

public class CarWidget extends AppWidgetProvider {
    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        final PendingResult pr = goAsync();
        final Context c = context.getApplicationContext();
        new Thread(() -> {
            try { CarSync.sync(c, false); } finally { pr.finish(); }
        }).start();
    }

    @Override
    public void onEnabled(Context context) {
        CarSync.schedule(context.getApplicationContext());
    }
}
