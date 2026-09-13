package com.screentextscan;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

/**
 * Невидимый переходник: плитка → сервис.
 *
 * ЗАЧЕМ ОН НУЖЕН. Панель быстрых настроек закрывается только как побочный
 * эффект `startActivityAndCollapse`, то есть плитка обязана запустить
 * АКТИВНОСТИ, а не сервис. Но показывать настоящий экран не надо —
 * пользователь хочет сразу выбрать зону. Отсюда активность без интерфейса.
 *
 * ПОЧЕМУ БЕЗ ОЖИДАНИЯ. Раньше здесь было ожидание привязки службы 2.5 с.
 * После полного выхода (force-stop) процесс убит, и на холодном старте
 * служба привязывается дольше 2.5 с — Toast «Служба чтения не запустилась»
 * был гарантирован. Теперь мы запускаем overlay сразу: OverlayService
 * показывает селектор зоны, а poll внутри него ждёт службу сам — как
 * только система привязывает ScanAccessibilityService, чтение начинается.
 * Никакого Toast, никакого ожидания, никакого перехода в настройки.
 */
public class LaunchActivity extends Activity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        boolean canOverlay = Permissions.canOverlay(this);
        boolean a11yMaster = Permissions.isAccessibilityMasterOn(this);
        boolean a11y = Permissions.isAccessibilityEnabled(this);
        boolean ready = canOverlay && a11yMaster && a11y;
        android.util.Log.d("ScreenTextScan",
                "launch: ready=" + ready + " overlay=" + canOverlay
                        + " master=" + a11yMaster + " a11y=" + a11y);

        if (!ready) {
            // Разрешений нет — показываем экран статуса (без авто-редиректа).
            startActivity(new Intent(this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            finish();
            return;
        }

        // Запускаем overlay немедленно. Служба привяжется сама —
        // poll в OverlayService подхватит её, как только она подключится.
        startForegroundService(new Intent(this, OverlayService.class)
                .setAction(OverlayService.ACTION_START));
        moveTaskToBack(true);
        finish();
    }
}
