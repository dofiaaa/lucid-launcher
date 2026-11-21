package com.lucid.launcher;

import android.app.Activity;
import android.app.WallpaperManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;   // NEW
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;          // NEW
import android.graphics.Rect;           // NEW
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;              // NEW
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MainActivity extends Activity {

    // NEW: код запроса при выборе обоев
    private static final int REQ_PICK_WALLPAPER = 1001;

    // ──────────────────────────── настройки

    private static class Prefs {
        private final SharedPreferences p;
        Prefs(Context c) { p = c.getSharedPreferences("lucid", Context.MODE_PRIVATE); }

        int     iconDp()      { return p.getInt("icon", 52); }
        void    setIconDp(int v)   { p.edit().putInt("icon", v).apply(); }

        int     alpha()       { return p.getInt("alpha", 0x1F); }
        void    setAlpha(int v)    { p.edit().putInt("alpha", v).apply(); }

        boolean showClock()   { return p.getBoolean("clock", true); }
        void    setShowClock(boolean v) { p.edit().putBoolean("clock", v).apply(); }

        boolean showDate()    { return p.getBoolean("date", true); }
        void    setShowDate(boolean v)  { p.edit().putBoolean("date", v).apply(); }

        boolean h24()         { return p.getBoolean("h24", true); }
        void    setH24(boolean v)  { p.edit().putBoolean("h24", v).apply(); }

        String  sort()        { return p.getString("sort", "az"); }
        void    setSort(String v)  { p.edit().putString("sort", v).apply(); }

        Set<String> hidden()  { return new HashSet<>(p.getStringSet("hidden", new HashSet<String>())); }
        void        setHidden(Set<String> s) { p.edit().putStringSet("hidden", s).apply(); }

        int  usage(String pkg) { return p.getInt("u_" + pkg, 0); }
        void bump(String pkg)  { p.edit().putInt("u_" + pkg, usage(pkg) + 1).apply(); }

        // NEW: обои
        String  wallpaperUri()       { return p.getString("wp_uri", ""); }
        void    setWallpaperUri(String u) { p.edit().putString("wp_uri", u).apply(); }
        int     blur()               { return p.getInt("blur", 65); }   // 0 — резко, 100 — сильный блюр
        void    setBlur(int v)       { p.edit().putInt("blur", v).apply(); }
        int     scrim()              { return p.getInt("scrim", 50); }  // 0 — без затемнения, 100 — очень тёмно
        void    setScrim(int v)      { p.edit().putInt("scrim", v).apply(); }

        void reset() { p.edit().clear().apply(); }
    }

    // ──────────────────────────── состояние
    private Prefs prefs;
    private FrameLayout root;
    private final List<ResolveInfo> apps = new ArrayList<>();
    private final HashMap<String, Drawable> iconCache = new HashMap<>();
    private AppAdapter adapter;
    private GridView grid;
    private TextView clockView, dateView;
    private View settingsOverlay, hiddenOverlay;

    private final Handler ticker = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override public void run() { updateTime(); ticker.postDelayed(this, 20_000); }
    };

    // ──────────────────────────── lifecycle

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);
        makeImmersive();
        root = new FrameLayout(this);
        setContentView(root);
        buildHome();
    }

    @Override protected void onResume() { super.onResume(); ticker.post(tick); }
    @Override protected void onPause()  { super.onPause();  ticker.removeCallbacks(tick); }
    @Override public void onBackPressed() {
        if (hiddenOverlay   != null) { closeHidden();   return; }
        if (settingsOverlay != null) { closeSettings(); return; }
    }

    // NEW: возврат из галереи
    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_PICK_WALLPAPER && res == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri == null) return;
            try {
                getContentResolver().takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException ignored) { /* некоторые провайдеры не дают persist */ }
            prefs.setWallpaperUri(uri.toString());
            rebuildSettings();
        }
    }

    private void makeImmersive() {
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
              | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
              | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
              | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
              | View.SYSTEM_UI_FLAG_FULLSCREEN
              | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    // ──────────────────────────── домашний экран

    private void buildHome() {
        root.removeAllViews();

        // NEW: строим обои с учётом пользовательских настроек
        Drawable wallpaper = buildWallpaper();
        if (wallpaper != null) root.setBackground(wallpaper);
        else root.setBackground(new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{ 0xFF1B2230, 0xFF0B0E14 }));

        // NEW: скрим с регулируемой прозрачностью
        int s = prefs.scrim();
        int topA = Math.min(0xFF, s * 0x4D * 2 / 100);
        int botA = Math.min(0xFF, s * 0xB3 * 2 / 100);
        View scrim = new View(this);
        scrim.setBackground(new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{ (topA << 24), (botA << 24) | 0x0B1018 }));
        root.addView(scrim, match());

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(24), dp(16), dp(24), dp(16));
        root.addView(column, match());

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(20), dp(14), dp(20), dp(14));
        header.setBackground(glass(false));

        LinearLayout leftCol = new LinearLayout(this);
        leftCol.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(this);
        title.setText("Lucid");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(22);
        title.setLetterSpacing(0.06f);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        leftCol.addView(title);

        dateView = new TextView(this);
        dateView.setTextColor(0xB3FFFFFF);
        dateView.setTextSize(12);
        dateView.setVisibility(prefs.showDate() ? View.VISIBLE : View.GONE);
        leftCol.addView(dateView);

        header.addView(leftCol, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        clockView = new TextView(this);
        clockView.setTextColor(0xFFFFFFFF);
        clockView.setTextSize(30);
        clockView.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        clockView.setShadowLayer(8, 0, 0, 0x40000000);
        clockView.setVisibility(prefs.showClock() ? View.VISIBLE : View.GONE);
        header.addView(clockView);

        TextView gear = new TextView(this);
        gear.setText("⚙");
        gear.setTextSize(20);
        gear.setTextColor(0xFFFFFFFF);
        gear.setGravity(Gravity.CENTER);
        gear.setPadding(dp(12), dp(8), dp(12), dp(8));
        gear.setBackground(glass(false));
        gear.setOnClickListener(v -> openSettings());
        LinearLayout.LayoutParams gearLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gearLp.leftMargin = dp(16);
        header.addView(gear, gearLp);

        column.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        grid = new GridView(this);
        int colW = prefs.iconDp() + dp(44);
        grid.setNumColumns(GridView.AUTO_FIT);
        grid.setColumnWidth(colW);
        grid.setHorizontalSpacing(dp(10));
        grid.setVerticalSpacing(dp(10));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setPadding(0, dp(14), 0, dp(14));
        grid.setClipToPadding(false);
        grid.setVerticalScrollBarEnabled(false);
        grid.setSelector(new ColorDrawable(0x00000000));
        grid.setDrawSelectorOnTop(false);

        adapter = new AppAdapter();
        grid.setAdapter(adapter);

        grid.setOnItemClickListener((p, v, pos, id) -> launch(apps.get(pos)));
        grid.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                repaintSelection(pos);
            }
            @Override public void onNothingSelected(AdapterView<?> p) { }
        });
        grid.setOnItemLongClickListener((p, v, pos, id) -> {
            showAppMenu(apps.get(pos));
            return true;
        });

        column.addView(grid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        updateTime();
        loadAppsAsync();
    }

    // ──────────────────────────── NEW: обои

    /** Строит фон: пользовательский URI или системные обои. Размытие — через размер декода. */
    private Drawable buildWallpaper() {
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int screenH = getResources().getDisplayMetrics().heightPixels;
        int blur = prefs.blur();
        int targetW = Math.max(8, screenW * (100 - blur) / 100);
        int targetH = Math.max(8, screenH * (100 - blur) / 100);

        // Буфер декодирования: 2× от целевого, но не больше экрана — экономия ОЗУ
        int decodeW = Math.max(64, Math.min(targetW * 2, screenW));
        int decodeH = Math.max(64, Math.min(targetH * 2, screenH));

        Bitmap source = null;
        if (!prefs.wallpaperUri().isEmpty()) source = decodeUserWallpaper(decodeW, decodeH);

        if (source == null) {
            try {
                Drawable d = WallpaperManager.getInstance(this).getDrawable();
                if (d != null) {
                    source = Bitmap.createBitmap(decodeW, decodeH, Bitmap.Config.ARGB_8888);
                    Canvas c = new Canvas(source);
                    d.setBounds(0, 0, decodeW, decodeH);
                    d.draw(c);
                }
            } catch (Throwable ignored) { }
        }
        if (source == null) return null;

        Bitmap tiny = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(tiny);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        c.drawBitmap(source, null, new Rect(0, 0, targetW, targetH), p);
        if (source != tiny) source.recycle();

        return new BitmapDrawable(getResources(), tiny);
    }

    /** Декодирует пользовательскую картинку с inSampleSize и RGB_565 — минимум ОЗУ. */
    private Bitmap decodeUserWallpaper(int wantW, int wantH) {
        try {
            Uri uri = Uri.parse(prefs.wallpaperUri());

            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            InputStream is = getContentResolver().openInputStream(uri);
            BitmapFactory.decodeStream(is, null, o);
            is.close();

            int sample = 1;
            while (o.outWidth / sample > wantW * 2 && o.outHeight / sample > wantH * 2) {
                sample *= 2;
            }

            o.inJustDecodeBounds = false;
            o.inSampleSize = sample;
            o.inPreferredConfig = Bitmap.Config.RGB_565;

            is = getContentResolver().openInputStream(uri);
            Bitmap bmp = BitmapFactory.decodeStream(is, null, o);
            is.close();
            return bmp;
        } catch (Exception e) {
            return null;
        }
    }

    /** Открывает системный пикер изображений. Разрешения не требуются. */
    private void pickWallpaper() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                 | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i, REQ_PICK_WALLPAPER);
    }

    // ──────────────────────────── меню долгого нажатия

    private void showAppMenu(ResolveInfo ri) {
        String pkg  = ri.activityInfo.packageName;
        String name = ri.loadLabel(getPackageManager()).toString();

        FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(0xCC000000);
        overlay.setOnClickListener(v -> root.removeView(overlay));
        overlay.setClickable(true);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(20), dp(20), dp(20), dp(20));
        card.setBackground(glass(true));
        card.setOnClickListener(v -> { });

        TextView t = new TextView(this);
        t.setText(name);
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(18);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t.setPadding(0, 0, 0, dp(16));
        card.addView(t);

        card.addView(menuButton("Скрыть из лаунчера", 0xFFFFFFFF, v -> {
            Set<String> h = prefs.hidden(); h.add(pkg); prefs.setHidden(h);
            root.removeView(overlay);
            buildHome();
        }));
        card.addView(menuButton("Информация о приложении", 0xFFFFFFFF, v -> {
            try {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + pkg)));
            } catch (Exception ignored) { }
            root.removeView(overlay);
        }));
        card.addView(menuButton("Отмена", 0x99FFFFFF, v -> root.removeView(overlay)));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                (int)(getResources().getDisplayMetrics().widthPixels * 0.7f),
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER;
        overlay.addView(card, lp);
        root.addView(overlay, match());
    }

    private TextView menuButton(String text, int color, View.OnClickListener l) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextColor(color);
        b.setTextSize(15);
        b.setPadding(dp(16), dp(14), dp(16), dp(14));
        b.setBackground(glass(false));
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    // ──────────────────────────── настройки

    private void openSettings() { rebuildSettings(); }
    private void closeSettings() {
        if (settingsOverlay != null) { root.removeView(settingsOverlay); settingsOverlay = null; }
        buildHome();
    }
    private void rebuildSettings() {
        if (settingsOverlay != null) root.removeView(settingsOverlay);
        settingsOverlay = buildSettingsPanel();
        root.addView(settingsOverlay, match());
    }

    private View buildSettingsPanel() {
        FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(0xE6000000);
        overlay.setOnClickListener(v -> closeSettings());
        overlay.setClickable(true);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackground(glass(true));
        scroll.setOnClickListener(v -> { });

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(16), dp(20), dp(20));

        LinearLayout hdr = new LinearLayout(this);
        hdr.setOrientation(LinearLayout.HORIZONTAL);
        hdr.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("Настройки");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(22);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        hdr.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView close = new TextView(this);
        close.setText("✕");
        close.setTextSize(20);
        close.setTextColor(0xFFFFFFFF);
        close.setGravity(Gravity.CENTER);
        close.setPadding(dp(12), dp(6), dp(12), dp(6));
        close.setBackground(glass(false));
        close.setOnClickListener(v -> closeSettings());
        hdr.addView(close);
        box.addView(hdr);

        // Размер иконок
        box.addView(sectionLabel("Размер иконок"));
        int[] sizes = { 40, 52, 68 };
        String[] sizeLabels = { "S", "M", "L" };
        LinearLayout sizeRow = new LinearLayout(this);
        sizeRow.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < sizes.length; i++) {
            final int val = sizes[i];
            TextView b = segButton(sizeLabels[i], prefs.iconDp() == val);
            b.setOnClickListener(v -> { prefs.setIconDp(val); rebuildSettings(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i < sizes.length - 1) lp.rightMargin = dp(8);
            sizeRow.addView(b, lp);
        }
        box.addView(sizeRow);

        // Прозрачность стекла
        box.addView(sectionLabel("Прозрачность стекла"));
        SeekBar seek = new SeekBar(this);
        seek.setMax(100);
        int minA = 0x0D, maxA = 0x4D, curA = prefs.alpha();
        seek.setProgress(Math.max(0, Math.min(100, (curA - minA) * 100 / (maxA - minA))));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean u) {
                if (u) prefs.setAlpha(minA + progress * (maxA - minA) / 100);
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override public void onStopTrackingTouch(SeekBar sb) { }
        });
        box.addView(seek);

        // Часы, формат, дата
        box.addView(toggleRow("Показывать часы", prefs.showClock(), v -> prefs.setShowClock(v)));
        box.addView(toggleRow("24-часовой формат", prefs.h24(), v -> prefs.setH24(v)));
        box.addView(toggleRow("Показывать дату", prefs.showDate(), v -> prefs.setShowDate(v)));

        // Сортировка
        box.addView(sectionLabel("Сортировка"));
        String[][] sortOpts = { { "az", "А→Я" }, { "usage", "По частоте" } };
        LinearLayout sortRow = new LinearLayout(this);
        sortRow.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < sortOpts.length; i++) {
            final String key = sortOpts[i][0];
            TextView b = segButton(sortOpts[i][1], prefs.sort().equals(key));
            b.setOnClickListener(v -> { prefs.setSort(key); rebuildSettings(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i < sortOpts.length - 1) lp.rightMargin = dp(8);
            sortRow.addView(b, lp);
        }
        box.addView(sortRow);

        // NEW: обои
        box.addView(sectionLabel("Обои"));
        boolean hasCustom = !prefs.wallpaperUri().isEmpty();

        TextView pick = new TextView(this);
        pick.setText(hasCustom ? "Сменить обои из галереи" : "Выбрать обои из галереи");
        pick.setTextColor(0xFFFFFFFF);
        pick.setTextSize(14);
        pick.setGravity(Gravity.CENTER);
        pick.setPadding(dp(16), dp(12), dp(16), dp(12));
        pick.setBackground(glass(false));
        pick.setOnClickListener(v -> pickWallpaper());
        box.addView(pick);

        if (hasCustom) {
            TextView sys = new TextView(this);
            sys.setText("Вернуть системные обои");
            sys.setTextColor(0xFFFFAA88);
            sys.setTextSize(14);
            sys.setGravity(Gravity.CENTER);
            sys.setPadding(dp(16), dp(12), dp(16), dp(12));
            sys.setBackground(glass(false));
            sys.setOnClickListener(v -> {
                prefs.setWallpaperUri("");
                rebuildSettings();
            });
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            slp.topMargin = dp(6);
            box.addView(sys, slp);
        }

        // NEW: размытие
        box.addView(sectionLabel("Размытие фона"));
        SeekBar blurSeek = new SeekBar(this);
        blurSeek.setMax(100);
        blurSeek.setProgress(prefs.blur());
        blurSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean u) {
                if (u) prefs.setBlur(p);
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override public void onStopTrackingTouch(SeekBar sb) { }
        });
        box.addView(blurSeek);

        // NEW: затемнение
        box.addView(sectionLabel("Затемнение"));
        SeekBar scrimSeek = new SeekBar(this);
        scrimSeek.setMax(100);
        scrimSeek.setProgress(prefs.scrim());
        scrimSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean u) {
                if (u) prefs.setScrim(p);
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override public void onStopTrackingTouch(SeekBar sb) { }
        });
        box.addView(scrimSeek);

        // Скрытые
        box.addView(sectionLabel("Скрытые приложения"));
        TextView hiddenBtn = new TextView(this);
        hiddenBtn.setText("Управлять скрытыми (" + prefs.hidden().size() + ")");
        hiddenBtn.setTextColor(0xFFFFFFFF);
        hiddenBtn.setTextSize(14);
        hiddenBtn.setGravity(Gravity.CENTER);
        hiddenBtn.setPadding(dp(16), dp(12), dp(16), dp(12));
        hiddenBtn.setBackground(glass(false));
        hiddenBtn.setOnClickListener(v -> openHidden());
        box.addView(hiddenBtn);

        // Сброс
        box.addView(sectionLabel(""));
        TextView reset = new TextView(this);
        reset.setText("Сбросить все настройки");
        reset.setTextColor(0xFFFF8888);
        reset.setTextSize(14);
        reset.setGravity(Gravity.CENTER);
        reset.setPadding(dp(16), dp(12), dp(16), dp(12));
        reset.setBackground(glass(false));
        reset.setOnClickListener(v -> {
            prefs.reset();
            if (hiddenOverlay != null) { root.removeView(hiddenOverlay); hiddenOverlay = null; }
            closeSettings();
        });
        box.addView(reset);

        scroll.addView(box);
        int w = (int)(getResources().getDisplayMetrics().widthPixels * 0.85f);
        int h = (int)(getResources().getDisplayMetrics().heightPixels * 0.85f);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h);
        lp.gravity = Gravity.CENTER;
        overlay.addView(scroll, lp);
        return overlay;
    }

    // ──────────────────────────── скрытые приложения

    private void openHidden() {
        if (hiddenOverlay != null) return;
        hiddenOverlay = buildHiddenPanel();
        root.addView(hiddenOverlay, match());
    }
    private void closeHidden() {
        if (hiddenOverlay != null) { root.removeView(hiddenOverlay); hiddenOverlay = null; }
        rebuildSettings();
    }

    private View buildHiddenPanel() {
        FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(0xE6000000);
        overlay.setOnClickListener(v -> closeHidden());
        overlay.setClickable(true);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackground(glass(true));
        scroll.setOnClickListener(v -> { });

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(16), dp(20), dp(20));

        LinearLayout hdr = new LinearLayout(this);
        hdr.setOrientation(LinearLayout.HORIZONTAL);
        hdr.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("Скрытые приложения");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(20);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        hdr.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView close = new TextView(this);
        close.setText("✕");
        close.setTextSize(20);
        close.setTextColor(0xFFFFFFFF);
        close.setGravity(Gravity.CENTER);
        close.setPadding(dp(12), dp(6), dp(12), dp(6));
        close.setBackground(glass(false));
        close.setOnClickListener(v -> closeHidden());
        hdr.addView(close);
        box.addView(hdr);

        Set<String> hiddenSet = prefs.hidden();
        if (hiddenSet.isEmpty()) {
            TextView none = new TextView(this);
            none.setText("Пока ничего не скрыто.");
            none.setTextColor(0x99FFFFFF);
            none.setTextSize(13);
            none.setPadding(0, dp(12), 0, 0);
            box.addView(none);
        } else {
            Intent main = new Intent(Intent.ACTION_MAIN, null);
            main.addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> all = getPackageManager().queryIntentActivities(main, 0);
            Collections.sort(all, (a, b) ->
                    a.loadLabel(getPackageManager()).toString()
                            .compareToIgnoreCase(b.loadLabel(getPackageManager()).toString()));

            for (ResolveInfo ri : all) {
                String pkg = ri.activityInfo.packageName;
                if (!hiddenSet.contains(pkg)) continue;

                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(12), dp(8), dp(12), dp(8));
                row.setBackground(glass(false));

                ImageView ic = new ImageView(this);
                int isz = dp(28);
                ic.setLayoutParams(new LinearLayout.LayoutParams(isz, isz));
                ic.setImageDrawable(ri.loadIcon(getPackageManager()));
                row.addView(ic);

                TextView lbl = new TextView(this);
                lbl.setText(ri.loadLabel(getPackageManager()));
                lbl.setTextColor(0xFFFFFFFF);
                lbl.setTextSize(14);
                LinearLayout.LayoutParams lblLp = new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                lblLp.leftMargin = dp(12);
                row.addView(lbl, lblLp);

                TextView btn = new TextView(this);
                btn.setText("Вернуть");
                btn.setTextColor(0xFF8FE8B0);
                btn.setTextSize(13);
                btn.setPadding(dp(12), dp(6), dp(12), dp(6));
                btn.setBackground(glass(false));
                btn.setOnClickListener(v -> {
                    Set<String> h = prefs.hidden();
                    h.remove(pkg);
                    prefs.setHidden(h);
                    root.removeView(hiddenOverlay);
                    hiddenOverlay = null;
                    rebuildSettings();
                });
                row.addView(btn);

                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rlp.topMargin = dp(6);
                box.addView(row, rlp);
            }
        }

        scroll.addView(box);
        int w = (int)(getResources().getDisplayMetrics().widthPixels * 0.85f);
        int h = (int)(getResources().getDisplayMetrics().heightPixels * 0.85f);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h);
        lp.gravity = Gravity.CENTER;
        overlay.addView(scroll, lp);
        return overlay;
    }

    // ──────────────────────────── вспомогательные вью

    private TextView segButton(String text, boolean active) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextColor(0xFFFFFFFF);
        b.setTextSize(14);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(16), dp(10), dp(16), dp(10));
        b.setBackground(glass(active));
        return b;
    }

    private TextView sectionLabel(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(0x99FFFFFF);
        t.setTextSize(11);
        t.setLetterSpacing(0.08f);
        t.setAllCaps(true);
        t.setPadding(0, dp(16), 0, dp(8));
        return t;
    }

    private View toggleRow(String label, boolean checked, ToggleCallback cb) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(10), dp(16), dp(10));
        row.setBackground(glass(false));

        TextView lbl = new TextView(this);
        lbl.setText(label);
        lbl.setTextColor(0xFFFFFFFF);
        lbl.setTextSize(14);
        row.addView(lbl, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Switch sw = new Switch(this);
        sw.setChecked(checked);
        sw.setOnCheckedChangeListener((b, v) -> cb.onToggle(v));
        row.addView(sw);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        row.setLayoutParams(lp);
        return row;
    }

    private interface ToggleCallback { void onToggle(boolean value); }

    // ──────────────────────────── стекло (почищено от мусора)

    private Drawable glass(boolean focused) {
        int a  = prefs.alpha();
        int a2 = Math.max(0x05, a * 2 / 3);
        int c1 = (focused ? Math.min(0xFF, a * 2) : a)  << 24 | 0xFFFFFF;
        int c2 = (focused ? a : a2)                     << 24 | 0xFFFFFF;
        GradientDrawable g = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, new int[]{ c1, c2 });
        g.setCornerRadius(dp(18));
        g.setStroke(Math.max(1, dp(1) / 2), focused ? 0xCCFFFFFF : 0x33FFFFFF);
        return g;
    }

    private void repaintSelection(int pos) {
        int first = grid.getFirstVisiblePosition();
        for (int i = 0; i < grid.getChildCount(); i++) {
            grid.getChildAt(i).setBackground(glass(first + i == pos));
        }
    }

    // ──────────────────────────── время

    private void updateTime() {
        long now = System.currentTimeMillis();
        String pattern = prefs.h24() ? "HH:mm" : "h:mm";
        if (clockView != null && prefs.showClock()) clockView.setText(DateFormat.format(pattern, now));
        if (dateView  != null && prefs.showDate())  dateView.setText(DateFormat.format("EEEE, d MMMM", now));
    }

    // ──────────────────────────── приложения

    private void loadAppsAsync() {
        new Thread(() -> {
            Intent main = new Intent(Intent.ACTION_MAIN, null);
            main.addCategory(Intent.CATEGORY_LAUNCHER);
            PackageManager pm = getPackageManager();
            List<ResolveInfo> found = pm.queryIntentActivities(main, 0);

            Set<String> hidden = prefs.hidden();
            final List<ResolveInfo> filtered = new ArrayList<>();
            String self = getPackageName();
            for (ResolveInfo ri : found) {
                if (ri.activityInfo == null) continue;
                String pkg = ri.activityInfo.packageName;
                if (self.equals(pkg)) continue;
                if (hidden.contains(pkg)) continue;
                filtered.add(ri);
            }

            if ("usage".equals(prefs.sort())) {
                Collections.sort(filtered, (a, b) -> {
                    int ua = prefs.usage(a.activityInfo.packageName);
                    int ub = prefs.usage(b.activityInfo.packageName);
                    if (ua != ub) return ub - ua;
                    return a.loadLabel(pm).toString().compareToIgnoreCase(b.loadLabel(pm).toString());
                });
            } else {
                Collections.sort(filtered, (a, b) ->
                        a.loadLabel(pm).toString().compareToIgnoreCase(b.loadLabel(pm).toString()));
            }

            runOnUiThread(() -> {
                apps.clear();
                apps.addAll(filtered);
                adapter.notifyDataSetChanged();
                grid.requestFocus();
                repaintSelection(0);
            });
        }, "apps-loader").start();
    }

    private void launch(ResolveInfo ri) {
        prefs.bump(ri.activityInfo.packageName);
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.addCategory(Intent.CATEGORY_LAUNCHER);
        i.setComponent(new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name));
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try { startActivity(i); }
        catch (Exception e) {
            Toast.makeText(this, "Не удалось запустить: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private class AppAdapter extends BaseAdapter {
        @Override public int getCount() { return apps.size(); }
        @Override public ResolveInfo getItem(int i) { return apps.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override public View getView(int pos, View recycled, ViewGroup parent) {
            ResolveInfo ri = apps.get(pos);

            LinearLayout box = new LinearLayout(MainActivity.this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setGravity(Gravity.CENTER);
            int p = dp(8);
            box.setPadding(p, p, p, p);
            box.setBackground(glass(false));

            ImageView icon = new ImageView(MainActivity.this);
            int isz = dp(prefs.iconDp());
            icon.setLayoutParams(new LinearLayout.LayoutParams(isz, isz));
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);

            String key = ri.activityInfo.packageName + "/" + ri.activityInfo.name;
            Drawable d = iconCache.get(key);
            if (d == null) { d = ri.loadIcon(getPackageManager()); iconCache.put(key, d); }
            icon.setImageDrawable(d);

            TextView label = new TextView(MainActivity.this);
            label.setText(ri.loadLabel(getPackageManager()));
            label.setTextColor(0xFFFFFFFF);
            label.setTextSize(11);
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.END);
            label.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(6);
            label.setLayoutParams(lp);

            box.addView(icon);
            box.addView(label);
            return box;
        }
    }

    // ──────────────────────────── utils

    private FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }
}
