package com.screentextscan;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Восстанавливает защиту службы после загрузки телефона и обновления APK. */
public class KeepAliveReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            AccessibilityKeepAliveService.start(context);
        }
    }
}
