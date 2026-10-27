package com.gathin.tvhome;

import android.animation.ArgbEvaluator;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.DisplayMetrics;
import android.view.View;
import android.widget.TextView;

import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * The clock screensaver's scene (layout dream_clock): a large thin clock, the greeting and the date
 * over Home's wallpaper, with a liquid-glass card holding Jakarta's weather now and tomorrow.
 * Shared by ClockDream (the system screensaver) and ClockActivity (Home left idle).
 *
 * Once a minute the greeting and the weather are refreshed (the weather only hits the network when
 * its 30-minute cache is due). Without clock_wallpaper the scene falls back to the old slow ambient
 * glow on black, which still shifts colour for burn-in.
 */
final class ClockScene {

    private static final long MOVE_MS = 60_000L;
    /** The glow's colour change: 16 steps over 8 s. Animated per frame it redrew the full-screen gradient ~480 times. */
    private static final int GLOW_STEPS = 16;
    private static final long GLOW_STEP_MS = 500L;
    /** Deep, low-brightness glow colours; the glow is drawn at partial alpha on black. */
    private static final int[] PALETTE = {0xFF2B5CFF, 0xFF00A39A, 0xFF8A3FFC, 0xFFE0661B, 0xFFD6336C};

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private final View content;
    private final View ambient;
    private final TextView greetingView;
    private final TextView locationView;
    private final TextView tempView;
    private final TextView conditionView;
    private final TextView aqiView;
    private final TextView tomorrowView;
    private final TextView tomorrowRainView;
    private final GradientDrawable glow;
    private int colorIndex;
    private int glowColor;
    /** clock_wallpaper or clock_glow false: something else (a picture, or plain black) replaced the coloured glow. */
    private boolean wallpaperShown;
    /** clock_wallpaper: the picture start() decodes, its darkening, and the resources to draw it with. */
    private File wallpaperFile;
    private int wallpaperShade;
    private Resources wallpaperRes;
    /** Bumped by stop(): a decode started before it finds a different number and drops its result. Main thread only. */
    private int wallpaperGeneration;

    /** The application context, for the greeting's locale and the cached weather; null until useHomeWallpaper. */
    private Context context;
    /** greeting_name from the theme: "SELAMAT MALAM, MIKHAYLA HANNA ELEANOR". */
    private String greetingName = "";

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            updateGreetingText();
            updateWeather();
            WeatherAirQuality.syncIfDue(context, ClockScene.this::updateWeather);
            shiftGlow();
            handler.postDelayed(this, MOVE_MS);
        }
    };

    /** root is the inflated dream_clock layout. */
    ClockScene(View root) {
        content = root.findViewById(R.id.dreamContent);
        ambient = root.findViewById(R.id.dreamAmbient);
        greetingView = root.findViewById(R.id.dreamGreeting);
        locationView = root.findViewById(R.id.dreamLocation);
        tempView = root.findViewById(R.id.dreamTemp);
        conditionView = root.findViewById(R.id.dreamCondition);
        aqiView = root.findViewById(R.id.dreamAqi);
        tomorrowView = root.findViewById(R.id.dreamTomorrow);
        tomorrowRainView = root.findViewById(R.id.dreamTomorrowRain);
        glow = new GradientDrawable();
        glow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        ambient.setBackground(glow);
        ambient.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) ->
                glow.setGradientRadius((r - l) * 0.55f));
        colorIndex = random.nextInt(PALETTE.length);
        setGlow(PALETTE[colorIndex]);
    }

    void start() {
        updateGreetingText();
        updateWeather();
        if (context != null) WeatherAirQuality.syncIfDue(context, this::updateWeather);
        content.setAlpha(0f);
        content.animate().alpha(1f).setDuration(1200).start();
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, MOVE_MS);
        if (wallpaperFile != null && !wallpaperShown) loadWallpaper();
    }

    void stop() {
        handler.removeCallbacksAndMessages(null);   // the tick and any glow steps still queued
        content.animate().cancel();
        wallpaperGeneration++;   // a decode still running must not attach its picture to a stopped scene
    }

    /**
     * clock_wallpaper: put the same picture Home is showing behind the clock, darkened by
     * clock_wallpaper_dim, in place of the glow. WallpaperPlayer saves the picture on screen (wallpaper_rotation,
     * "image") every time it changes, so the screensaver follows a picker choice, a theme picture and the
     * rotation alike, and after a reboot it opens on the same picture Home resumes. Decoded off the main thread
     * at screen size in RGB_565 (4 MB at 1080p), like Home's own; until then, or if it cannot be read, the glow
     * stays. With no wallpaper at all (the picker's "none"), the clock sits on plain black like Home.
     * Also reads greeting_name, so the screensaver greets the same person as Home.
     * Call before start(), which starts the decode (again after a stop() that dropped an unfinished one).
     */
    void useHomeWallpaper(Context ctx) {
        context = ctx.getApplicationContext();
        Config cfg;
        try {
            cfg = Config.load(ctx);
        } catch (RuntimeException e) {
            return;
        }
        greetingName = cfg.greetingName;
        updateGreetingText();
        if (!cfg.clockWallpaper) {
            // clock_glow false: plain black behind the clock (OLED).
            if (!cfg.clockGlow) {
                wallpaperShown = true;   // also stops the glow's colour cycle
                ambient.setBackground(null);
            }
            return;
        }
        if (!cfg.wallpaperEnabled) {
            wallpaperShown = true;   // the picker's "none": Home is plain too, so no glow either
            ambient.setBackground(null);
            return;
        }
        String shown = ctx.getSharedPreferences(WallpaperRotation.PREFS_NAME, Context.MODE_PRIVATE)
                .getString(WallpaperRotation.CURRENT_KEY, "");
        File onScreen = WallpaperRotation.find(cfg.wallpapers, shown);
        wallpaperFile = onScreen != null ? onScreen
                : cfg.wallpaper != null ? cfg.wallpaper
                : cfg.wallpapers.isEmpty() ? null : cfg.wallpapers.get(0);
        if (wallpaperFile == null) {
            wallpaperShown = true;
            ambient.setBackground(null);
            return;
        }
        wallpaperShade = (cfg.clockWallpaperDim * 255 / 100) << 24;
        wallpaperRes = ctx.getResources();
    }

    private void updateGreetingText() {
        if (greetingView == null || context == null) return;
        String greeting = context.getString(Greeting.res(context));
        if (!greetingName.isEmpty()) greeting = context.getString(R.string.greeting_with_name, greeting, greetingName);
        if (!greeting.contentEquals(greetingView.getText())) greetingView.setText(greeting);
    }

    /** Fills the glass card from the cached forecast: now, AQI, and tomorrow's range and rain chance. */
    private void updateWeather() {
        if (context == null || tempView == null) return;
        WeatherAirQuality.FullWeather weather = WeatherAirQuality.getFullWeather(context);
        locationView.setText(weather.location);
        tempView.setText(String.format(Locale.ROOT, "%d°", weather.temp));
        conditionView.setText(weather.condition);

        String aqi = context.getString(R.string.clock_aqi, weather.aqi,
                weather.aqiStatus == null ? "" : weather.aqiStatus);
        SpannableStringBuilder aqiLine = new SpannableStringBuilder(aqi);
        aqiLine.setSpan(new ForegroundColorSpan(weather.aqiColor), 0, aqiLine.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        aqiView.setText(aqiLine);

        List<WeatherAirQuality.DailyItem> daily = weather.daily;
        WeatherAirQuality.DailyItem tomorrow = daily != null && daily.size() > 1 ? daily.get(1) : null;
        if (tomorrow == null) {
            tomorrowView.setText("—");
            tomorrowRainView.setText("");
        } else {
            tomorrowView.setText(String.format(Locale.ROOT, "%d° / %d°", tomorrow.maxTemp, tomorrow.minTemp));
            tomorrowRainView.setText(context.getString(R.string.clock_rain, tomorrow.rainChance));
        }
    }

    private void loadWallpaper() {
        File file = wallpaperFile;
        int shade = wallpaperShade;
        Resources res = wallpaperRes;
        DisplayMetrics dm = res.getDisplayMetrics();
        int w = dm.widthPixels, h = dm.heightPixels;
        int generation = wallpaperGeneration;
        new Thread(() -> {
            Bitmap bmp = decode(file, w, h);
            if (bmp == null) return;
            handler.post(() -> {
                if (generation != wallpaperGeneration || wallpaperShown) {
                    bmp.recycle();   // stopped meanwhile (or already shown): this picture was never attached
                    return;
                }
                wallpaperShown = true;
                ambient.setBackground(new LayerDrawable(new Drawable[]{
                        new BitmapDrawable(res, bmp), new ColorDrawable(shade)}));
            });
        }, "clock-wallpaper").start();
    }

    /** WallpaperPlayer's decode: sampled and scaled down to cover the screen, never bigger than 4K. */
    private static Bitmap decode(File file, int screenW, int screenH) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), options);
            int width = options.outWidth, height = options.outHeight;
            if (width <= 0 || height <= 0) return null;
            int sample = WallpaperSize.sampleSize(width, height, screenW, screenH);
            if (!WallpaperSize.accepted(width / sample, height / sample)) return null;
            options.inJustDecodeBounds = false;
            options.inSampleSize = sample;
            int[] scale = WallpaperSize.coverScale(width / sample, height / sample, screenW, screenH);
            if (scale != null) {
                options.inScaled = true;
                options.inDensity = scale[0];
                options.inTargetDensity = scale[1];
            }
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(file.getPath(), options);
        } catch (Exception | OutOfMemoryError failure) {
            return null;
        }
    }

    private void shiftGlow() {
        if (wallpaperShown) return;
        int from = glowColor;
        colorIndex = (colorIndex + 1) % PALETTE.length;
        int to = PALETTE[colorIndex];
        ArgbEvaluator mix = new ArgbEvaluator();
        for (int i = 1; i <= GLOW_STEPS; i++) {
            float f = (float) i / GLOW_STEPS;
            handler.postDelayed(() -> setGlow((int) mix.evaluate(f, from, to)), i * GLOW_STEP_MS);
        }
    }

    private void setGlow(int color) {
        glowColor = color;
        int c = color & 0x00FFFFFF;
        glow.setColors(new int[]{0x40000000 | c, 0x14000000 | c, 0x00000000 | c});
    }
}
