package com.gathin.tvhome;

import android.content.Context;

/**
 * Jakarta-only location for the prayer and weather widgets.
 *
 * The upstream fork carried a full location picker (GPS, manual city, station ids). This build is
 * meant for one TV in Jakarta, so the coordinates are fixed and the Kemenag timetable is fetched
 * for the city name below. Keeping the same method names as the fork lets PrayerTimes and
 * WeatherAirQuality stay close to their upstream source.
 */
final class AppLocationManager {

    private static final double LATITUDE = -6.2088;
    private static final double LONGITUDE = 106.8456;
    private static final String NAME = "Jakarta";
    /** Not a JAS Malaysia station: PrayerTimes reads "CUSTOM" and uses the Kemenag timetable. */
    private static final String STATION_ID = "CUSTOM_JAKARTA";

    private AppLocationManager() {
    }

    static double getLatitude(Context context) {
        return LATITUDE;
    }

    static double getLongitude(Context context) {
        return LONGITUDE;
    }

    static String getLocationName(Context context) {
        return NAME;
    }

    static String getStationId(Context context) {
        return STATION_ID;
    }

    static String getStationPlace(Context context) {
        return NAME;
    }
}
