package com.screentextscan;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;

import com.googlecode.tesseract.android.TessBaseAPI;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Распознавание текста на снимке экрана (on-device Tesseract, rus+eng).
 *
 * ЗАЧЕМ. Дерево доступности отдаёт только то, что приложение СОЗДАЛО
 * для доступности: canvas-приложения, игры, PDF-битмапы и защищённые
 * экраны текст в нём не публикуют — там сканер слеп. Единственный способ
 * прочитать ВЕСЬ экран — посмотреть на него глазами: снимок + OCR.
 *
 * ХОЛОДНЫЙ СТАТ: инициализация (распаковка моделей из assets) ~1 с,
 * распознавание кадра ~0,3–1 с — поэтому движок живёт на время скана и
 * узнаёт кадры не чаще лимита, который задаёт вызывающий.
 */
public final class OcrEngine {

    private TessBaseAPI tess;
    private boolean failed;

    /** Инициализация: модели из assets копируются в filesDir один раз. */
    public synchronized void ensureInit(Context ctx) {
        if (tess != null || failed) return;
        File dir = new File(ctx.getFilesDir(), "tessdata");
        if (!dir.exists() && !dir.mkdirs()) { failed = true; return; }
        try {
            copyAsset(ctx, "tessdata/rus.traineddata",
                    new File(dir, "rus.traineddata"));
            copyAsset(ctx, "tessdata/eng.traineddata",
                    new File(dir, "eng.traineddata"));
        } catch (IOException e) {
            failed = true;
            return;
        }
        try {
            // DATAPATH — папка, СОДЕРЖАЩАЯ tessdata; язык — оба сразу.
            TessBaseAPI t = new TessBaseAPI();
            if (t.init(ctx.getFilesDir().getAbsolutePath(), "rus+eng")) {
                tess = t;
            } else {
                t.recycle();
                failed = true;
            }
        } catch (RuntimeException e) {
            failed = true;
        }
    }

    public boolean isFailed() { return failed; }

    private static void copyAsset(Context ctx, String name, File out)
            throws IOException {
        if (out.exists() && out.length() > 0) return; // уже распаковано
        try (InputStream in = ctx.getAssets().open(name);
             OutputStream os = new FileOutputStream(out)) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        }
    }

    /**
     * Распознать кадр. Возвращает строки с экранными границами — те же
     * Line, что даёт обход дерева, поэтому дальше один конвейер.
     */
    public synchronized List<ScanAccessibilityService.Line> recognize(Bitmap bmp) {
        List<ScanAccessibilityService.Line> out = new ArrayList<>();
        if (tess == null || bmp == null) return out;
        try {
            tess.setImage(bmp);
            // Page segmentation 11 = «разрежённый текст»: интерфейс — не
            // печатная страница, блоков и абзацев нет, строки разрознены.
            tess.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT);
            String all = tess.getUTF8Text();
            if (all == null || all.isEmpty()) return out;
            tess.getWords().forEach(w -> {
                String text = w.getUTF8Text().trim();
                if (text.length() >= 2) {
                    out.add(new ScanAccessibilityService.Line(text,
                            new Rect(w.getBoundingBox()), false));
                }
            });
        } catch (RuntimeException ignored) {
            // битый кадр или движок умер — просто пусто
        }
        return out;
    }

    public synchronized void release() {
        if (tess != null) {
            try { tess.recycle(); } catch (RuntimeException ignored) { }
            tess = null;
        }
    }
}
