package com.screentextscan;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;

/**
 * Прозрачная активность-посредник для разрешения на захват экрана.
 *
 * Диалог системы показывается ОДИН раз за скан: vision-режим включается
 * только когда интерфейс молчит для доступности. Активность невидима —
 * никакого мерцания, только системный диалог.
 */
public class VisionGrantActivity extends Activity {

    private static final String EXTRA_REQUEST = "sts.request_code";
    private static ScreenCaptureManager.Listener listener;
    private static int pendingRequestCode;

    @SuppressLint("StaticFieldLeak") // listener живёт ровно до диалога
    static void request(Context ctx, ScreenCaptureManager.Listener l) {
        listener = l;
        pendingRequestCode = 4242;
        Intent i = new Intent(ctx, VisionGrantActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_REQUEST, 4242);
        ctx.startActivity(i);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        MediaProjectionManager mpm = (MediaProjectionManager)
                getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        startActivityForResult(mpm.createScreenCaptureIntent(),
                getIntent().getIntExtra(EXTRA_REQUEST, 4242));
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        ScreenCaptureManager.Listener l = listener;
        listener = null;
        if (requestCode != pendingRequestCode && requestCode != 4242) {
            if (l != null) l.onGrantResult(false);
            finish();
            return;
        }
        if (l != null) {
            if (resultCode == RESULT_OK && data != null) {
                // Передаём данные прямо в сервис — активность сейчас умрёт.
                OverlayService.deliverVisionGrant(resultCode, data);
                l.onGrantResult(true);
            } else {
                l.onGrantResult(false);
            }
        }
        finish();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Пустая пауза: активность прозрачная и живёт секунды.
    }
}
