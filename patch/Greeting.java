package com.gathin.tvhome;

import android.content.Context;

import java.util.Calendar;
import java.util.Locale;

/**
 * The greeting's day part ("Selamat pagi", "Good evening"), shared by Home's clock and the clock
 * screensaver so both say the same thing at the same minute.
 */
final class Greeting {

    private Greeting() {
    }

    /** The string resource for the current hour in the device's locale. */
    static int res(Context context) {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        Locale locale = context.getResources().getConfiguration().getLocales().get(0);
        String lang = locale.getLanguage();
        String country = locale.getCountry();
        // Indonesian: Pagi 00-10, Siang 10-15, Sore 15-18, Malam 18-24.
        if ("in".equals(lang) || "id".equals(lang)) {
            if (hour < 10) return R.string.good_morning;
            if (hour < 15) return R.string.good_afternoon;
            if (hour < 18) return R.string.good_evening;
            return R.string.good_night;
        }
        // Spain (es-ES) and Catalan: "Buenas tardes" into the evening; "Buenas noches" from 21.
        if (("es".equals(lang) && "ES".equals(country)) || "ca".equals(lang)) {
            if (hour < 6) return R.string.good_night;
            if (hour < 12) return R.string.good_morning;
            if (hour < 21) return R.string.good_afternoon;
            return R.string.good_night;
        }
        // Latin American Spanish keeps the usual evening strings: "Buenas noches" from ~17.
        if (hour < 5) return R.string.good_night;
        if (hour < 12) return R.string.good_morning;
        if (hour < 17) return R.string.good_afternoon;
        if (hour < 22) return R.string.good_evening;
        return R.string.good_night;
    }
}
