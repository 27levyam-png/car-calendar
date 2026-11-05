package com.betzalel.carcalendar;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 7);
        }
        CarSync.schedule(this);
    }

    @Override
    public void onPause() {
        super.onPause();
        final Context c = getApplicationContext();
        new Thread(() -> CarSync.sync(c, false)).start();   // רענון הווידג'ט ביציאה מהאפליקציה
    }
}
