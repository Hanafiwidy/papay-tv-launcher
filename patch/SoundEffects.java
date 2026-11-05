package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.SoundPool;

/**
 * Clean UI Sound Effects Manager with console sound profiles (PS5, Nintendo Switch, Xbox Series X/S)
 * and full mute/unmute toggle support.
 */
final class SoundEffects {
    private static final String PREFS_NAME = "sound_prefs";
    private static final String PREF_THEME = "sound_theme";

    public static final String THEME_PS5 = "ps5";
    public static final String THEME_SWITCH = "switch";
    public static final String THEME_XBOX = "xbox";
    public static final String THEME_OFF = "off";

    private static SoundPool soundPool;
    private static int soundNav;
    private static int soundSelect;
    private static String activeTheme = THEME_PS5;
    private static boolean ready;

    private static float navVolume = 0.65f;
    private static float selectVolume = 0.85f;

    private SoundEffects() {
    }

    static void init(Context context) {
        if (context == null) return;
        SharedPreferences sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        activeTheme = sp.getString(PREF_THEME, THEME_PS5);
        loadSounds(context.getApplicationContext());
    }

    static void loadSounds(Context context) {
        if (soundPool != null) {
            soundPool.release();
            soundPool = null;
            soundNav = 0;
            soundSelect = 0;
            ready = false;
        }

        if (THEME_OFF.equalsIgnoreCase(activeTheme)) {
            return;
        }

        try {
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            soundPool = new SoundPool.Builder()
                    .setMaxStreams(4)
                    .setAudioAttributes(attrs)
                    .build();

            int navRes;
            int selRes;
            if (THEME_SWITCH.equalsIgnoreCase(activeTheme)) {
                navRes = R.raw.switch_nav;
                selRes = R.raw.switch_select;
                navVolume = 0.85f;
                selectVolume = 0.95f;
            } else if (THEME_XBOX.equalsIgnoreCase(activeTheme)) {
                navRes = R.raw.xbox_nav;
                selRes = R.raw.xbox_select;
                navVolume = 0.75f;
                selectVolume = 0.90f;
            } else { // PS5 default
                navRes = R.raw.ps5_nav;
                selRes = R.raw.ps5_select;
                navVolume = 0.65f;
                selectVolume = 0.85f;
            }

            soundNav = soundPool.load(context, navRes, 1);
            soundSelect = soundPool.load(context, selRes, 1);
            soundPool.setOnLoadCompleteListener((pool, sampleId, status) -> {
                if (status == 0) ready = true;
            });
            ready = true;
        } catch (Exception ignored) {
            ready = false;
        }
    }

    static void setTheme(Context context, String theme) {
        if (context == null) return;
        activeTheme = theme != null ? theme : THEME_PS5;

        SharedPreferences sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        sp.edit().putString(PREF_THEME, activeTheme).apply();

        loadSounds(context.getApplicationContext());
    }

    static String getActiveTheme(Context context) {
        if (context != null) {
            SharedPreferences sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            activeTheme = sp.getString(PREF_THEME, THEME_PS5);
        }
        return activeTheme != null ? activeTheme : THEME_PS5;
    }

    static String getThemeDisplayName(String theme) {
        if (theme == null) return "PlayStation 5";
        if (THEME_SWITCH.equalsIgnoreCase(theme)) return "Nintendo Switch";
        if (THEME_XBOX.equalsIgnoreCase(theme)) return "Xbox Series X/S";
        if (THEME_OFF.equalsIgnoreCase(theme)) return "Matikan Bunyi (Off)";
        return "PlayStation 5";
    }

    static void playNav() {
        if (!THEME_OFF.equalsIgnoreCase(activeTheme) && ready && soundPool != null && soundNav != 0) {
            soundPool.play(soundNav, navVolume, navVolume, 0, 0, 1.0f);
        }
    }

    static void playSelect() {
        if (!THEME_OFF.equalsIgnoreCase(activeTheme) && ready && soundPool != null && soundSelect != 0) {
            soundPool.play(soundSelect, selectVolume, selectVolume, 0, 0, 1.0f);
        }
    }

    static void playPreview(Context context, String theme) {
        if (THEME_OFF.equalsIgnoreCase(theme)) return;
        playSelect();
    }

    static void release() {
        if (soundPool != null) {
            soundPool.release();
            soundPool = null;
            soundNav = 0;
            soundSelect = 0;
            ready = false;
        }
    }
}
