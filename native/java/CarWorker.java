package com.betzalel.carcalendar;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

public class CarWorker extends Worker {
    public CarWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        CarSync.sync(getApplicationContext(), true);
        return Result.success();
    }
}
