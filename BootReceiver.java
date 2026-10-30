package com.apkguard.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** После перезагрузки или обновления APK Guard восстанавливает включённую пользователем защиту. */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }
        boolean on = context.getSharedPreferences("apkguard", Context.MODE_PRIVATE)
                .getBoolean("guard", false);
        if (!on) {
            return;
        }
        try {
            context.startForegroundService(new Intent(context, GuardService.class));
        } catch (RuntimeException ignored) {
            // система может запретить запуск; защиту можно включить в приложении
        }
    }
}
