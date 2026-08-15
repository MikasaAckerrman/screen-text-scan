package com.screentextscan;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

/**
 * Невидимый переходник: плитка → сервис.
 *
 * ЗАЧЕМ ОН НУЖЕН. Панель быстрых настроек закрывается только как побочный
 * эффект `startActivityAndCollapse`, то есть плитка обязана запустить
 * АКТИВНОСТЬ, а не сервис. Но показывать настоящий экран не надо —
 * пользователь хочет сразу выбрать зону. Отсюда активность без интерфейса.
 *
 * ПОЧЕМУ ЗДЕСЬ ЕСТЬ ОЖИДАНИЕ. Служба доступности живёт в процессе нашего
 * приложения. Система убивает этот процесс, когда приложение простаивает, а
 * при следующем обращении создаёт заново — и служба привязывается не мгновенно.
 * Если сразу после создания процесса спросить «служба подключена?», ответ
 * будет «нет», хотя в настройках она включена. Ровно на этом ломался запуск с
 * плитки: приложение делало вывод «разрешение не выдано» и отправляло человека
 * в настройки, где всё уже было включено.
 *
 * Поэтому: разрешение проверяем по СИСТЕМНОЙ НАСТРОЙКЕ (мгновенно и надёжно),
 * а подключения службы ЖДЁМ — недолго и с сообщением, если не дождались.
 */
public class LaunchActivity extends Activity {

    /** Сколько ждём привязки службы. Замерять нечем, но 2.5 с с запасом
     *  покрывают холодный старт процесса на небыстром устройстве. */
    private static final long WAIT_TOTAL_MS = 2500;
    private static final long WAIT_STEP_MS = 100;

    private final Handler h = new Handler(Looper.getMainLooper());
    private long waited;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        // Разрешений нет — ведём в настройку. Проверка по системной настройке,
        // а не по живому экземпляру службы: см. Permissions.
        if (!Permissions.ready(this)) {
            startActivity(new Intent(this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            finish();
            return;
        }

        waitForServiceThenStart();
    }

    private void waitForServiceThenStart() {
        if (Permissions.isAccessibilityConnected()) {
            startForegroundService(new Intent(this, OverlayService.class)
                    .setAction(OverlayService.ACTION_START));
            finish();
            return;
        }
        if (waited >= WAIT_TOTAL_MS) {
            /*
             * Не дождались. Это не «разрешение не выдано» — в настройках оно
             * есть, иначе мы бы сюда не дошли. Значит система по какой-то
             * причине не привязала службу; чаще всего лечится однократным
             * переключением её в настройках, о чём и сообщаем.
             */
            Toast.makeText(this,
                    "Служба чтения не запустилась. Откройте настройки доступности "
                            + "и переключите «Чтение с экрана» выключить-включить.",
                    Toast.LENGTH_LONG).show();
            startActivity(new Intent(this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            finish();
            return;
        }
        waited += WAIT_STEP_MS;
        h.postDelayed(this::waitForServiceThenStart, WAIT_STEP_MS);
    }

    @Override
    protected void onDestroy() {
        h.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
