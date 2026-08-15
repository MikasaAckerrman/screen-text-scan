package com.screentextscan;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * Невидимый переходник: плитка → сервис.
 *
 * ЗАЧЕМ ОН ВООБЩЕ НУЖЕН. Панель быстрых настроек закрывается только как
 * побочный эффект `startActivityAndCollapse`, то есть плитка обязана
 * запустить АКТИВНОСТЬ, а не сервис. Но показывать настоящий экран не надо —
 * пользователь хочет сразу выбрать зону. Поэтому активность без интерфейса:
 * поднимает сервис и тут же закрывается.
 *
 * Задержки быть не должно: сервис создаёт окно поверх всего, и если
 * активность ещё жива, окно окажется поверх НЕЁ, а не поверх приложения,
 * которое пользователь читает. Отсюда finish() сразу в onCreate.
 *
 * Анимацию гасим темой (windowAnimationStyle=@null), а не
 * overridePendingTransition — последний объявлен устаревшим с API 34.
 */
public class LaunchActivity extends Activity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        startForegroundService(new Intent(this, OverlayService.class)
                .setAction(OverlayService.ACTION_START));
        finish();
    }
}
