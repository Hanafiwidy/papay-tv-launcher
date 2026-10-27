package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Jadwal salat Jakarta from Kemenag (api.myquran.com), cached locally.
 * Fetches the Kemenag timetable, caches it, and includes
 * an accurate solar-calculation fallback so it never fails offline.
 */
final class PrayerTimes {
    private static final String TAG = "TvHome.Prayer";
    private static final String PREFS_NAME = "prayer_times_cache";
    private static final String KEY_JSON = "takwim_jakarta";
    private static final String KEY_MONTH = "takwim_month";

    // Coordinates for Putrajaya
    private static final double LATITUDE = -6.2088;
    private static final double LONGITUDE = 106.8456;

    /** Device UTC offset in hours, so the offline solar calculation is right outside Malaysia too. */
    private static double tzHours() {
        return java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 3600000.0;
    }

    private static final String[] PRAYER_NAMES = {"Subuh", "Terbit", "Dzuhur", "Ashar", "Maghrib", "Isya"};
    private static final String[] PRAYER_KEYS = {"fajr", "syuruk", "dhuhr", "asr", "maghrib", "isha"};

    private PrayerTimes() {
    }

    /** Forces an immediate background sync for the active location/zone. */
    static void sync(Context context, Runnable onUpdated) {
        syncInternal(context, onUpdated, true);
    }

    /** Refresh timetable in background if needed (once a month / day). */
    static void syncIfDue(Context context, Runnable onUpdated) {
        syncInternal(context, onUpdated, false);
    }

    private static void syncInternal(Context context, Runnable onUpdated, boolean force) {
        new Thread(() -> {
            try {
                SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                Calendar now = Calendar.getInstance();
                int currentMonth = now.get(Calendar.YEAR) * 100 + (now.get(Calendar.MONTH) + 1);
                boolean custom = isCustomLocation(context);
                String zone = custom ? "CUSTOM" : getJakimZone(context);
                String cachedZone = prefs.getString("cached_zone", "");

                if (!force && zone.equals(cachedZone) && prefs.getInt(KEY_MONTH, 0) == currentMonth && prefs.contains(KEY_JSON)) {
                    return; // already have this month's takwim
                }

                String body = custom ? fetchCustomPrayers(context, now) : fetchWaktusolat(zone);
                if (body != null) {
                    prefs.edit()
                            .putString(KEY_JSON, body)
                            .putInt(KEY_MONTH, currentMonth)
                            .putString("cached_zone", zone)
                            .apply();

                    if (onUpdated != null) {
                        new Handler(Looper.getMainLooper()).post(onUpdated);
                    }
                    Log.i(TAG, "Synced prayer times for " + zone + " successfully");
                }
            } catch (Exception e) {
                Log.w(TAG, "Prayer sync offline/failed: " + e.getMessage());
            }
        }, "prayer-sync").start();
    }

    /** A location outside Malaysia has no JAKIM zone: it is stored with the station id CUSTOM. */
    private static boolean isCustomLocation(Context context) {
        String id = AppLocationManager.getStationId(context);
        return id != null && id.toUpperCase(Locale.ROOT).startsWith("CUSTOM");
    }

    private static String httpGet(String urlStr) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent", "Mozilla/5.0");
        if (conn.getResponseCode() != 200) return null;
        BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) sb.append(line);
        reader.close();
        return sb.toString();
    }

    private static String fetchWaktusolat(String zone) throws Exception {
        return httpGet("https://api.waktusolat.app/v2/solat/" + zone);
    }

    /**
     * Prayer times outside Malaysia. Official Kemenag (Indonesia) timetable first, since that is what
     * mosques in Jakarta follow; Aladhan's Kemenag-method calendar as the fallback for other countries.
     */
    private static String fetchCustomPrayers(Context context, Calendar now) throws Exception {
        String kemenag = fetchKemenag(context, now);
        if (kemenag != null) return kemenag;
        return fetchAladhan(context, now);
    }

    /** api.myquran.com, the Kemenag timetable: the city is searched by the location's own name. */
    private static String fetchKemenag(Context context, Calendar now) throws Exception {
        String name = AppLocationManager.getLocationName(context);
        if (name == null || name.trim().isEmpty()) return null;
        String city = name.split(",")[0].trim();
        String search = httpGet("https://api.myquran.com/v2/sholat/kota/cari/"
                + java.net.URLEncoder.encode(city, "UTF-8"));
        if (search == null) return null;
        JSONArray cities = new JSONObject(search).optJSONArray("data");
        if (cities == null || cities.length() == 0) return null;
        String cityId = cities.getJSONObject(0).getString("id");

        int year = now.get(Calendar.YEAR);
        int month = now.get(Calendar.MONTH) + 1;
        String body = httpGet(String.format(Locale.US,
                "https://api.myquran.com/v2/sholat/jadwal/%s/%d/%d", cityId, year, month));
        if (body == null) return null;

        JSONArray days = new JSONObject(body).getJSONObject("data").getJSONArray("jadwal");
        JSONArray prayers = new JSONArray();
        for (int i = 0; i < days.length(); i++) {
            JSONObject d = days.getJSONObject(i);
            String date = d.getString("date");
            int day = Integer.parseInt(date.substring(8, 10));
            JSONObject p = new JSONObject();
            p.put("day", day);
            p.put("fajr", toEpoch(d.getString("subuh"), year, month, day));
            p.put("syuruk", toEpoch(d.getString("terbit"), year, month, day));
            p.put("dhuhr", toEpoch(d.getString("dzuhur"), year, month, day));
            p.put("asr", toEpoch(d.getString("ashar"), year, month, day));
            p.put("maghrib", toEpoch(d.getString("maghrib"), year, month, day));
            p.put("isha", toEpoch(d.getString("isya"), year, month, day));
            prayers.put(p);
        }
        JSONObject out = new JSONObject();
        out.put("zone", "CUSTOM");
        out.put("source", "kemenag");
        out.put("prayers", prayers);
        return out.toString();
    }

    /**
     * Prayer times outside Malaysia: Aladhan's calendar, method 20 (Kemenag, Indonesia), stored in the
     * same shape the JAKIM response has (prayers[].day plus epoch seconds per prayer) so the rest of
     * this class and the UI do not change.
     */
    private static String fetchAladhan(Context context, Calendar now) throws Exception {
        double lat = AppLocationManager.getLatitude(context);
        double lon = AppLocationManager.getLongitude(context);
        int year = now.get(Calendar.YEAR);
        int month = now.get(Calendar.MONTH) + 1;
        String body = httpGet(String.format(Locale.US,
                "https://api.aladhan.com/v1/calendar?latitude=%.4f&longitude=%.4f&method=20&month=%d&year=%d",
                lat, lon, month, year));
        if (body == null) return null;

        JSONArray data = new JSONObject(body).getJSONArray("data");
        JSONArray prayers = new JSONArray();
        for (int i = 0; i < data.length(); i++) {
            JSONObject entry = data.getJSONObject(i);
            int day = entry.getJSONObject("date").getJSONObject("gregorian").getInt("day");
            JSONObject timings = entry.getJSONObject("timings");
            JSONObject p = new JSONObject();
            p.put("day", day);
            p.put("fajr", toEpoch(timings.getString("Fajr"), year, month, day));
            p.put("syuruk", toEpoch(timings.getString("Sunrise"), year, month, day));
            p.put("dhuhr", toEpoch(timings.getString("Dhuhr"), year, month, day));
            p.put("asr", toEpoch(timings.getString("Asr"), year, month, day));
            p.put("maghrib", toEpoch(timings.getString("Maghrib"), year, month, day));
            p.put("isha", toEpoch(timings.getString("Isha"), year, month, day));
            prayers.put(p);
        }
        JSONObject out = new JSONObject();
        out.put("zone", "CUSTOM");
        out.put("prayers", prayers);
        return out.toString();
    }

    /** "04:25" or "04:25 (WIB)" on the given date, in the device's own timezone, to epoch seconds. */
    private static long toEpoch(String time, int year, int month, int day) {
        try {
            String hhmm = time.trim().split("\\s+")[0];
            String[] parts = hhmm.split(":");
            Calendar c = Calendar.getInstance();
            c.clear();
            c.set(year, month - 1, day, Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), 0);
            return c.getTimeInMillis() / 1000L;
        } catch (Exception e) {
            return 0L;
        }
    }

    public static class PrayerZone {
        public final String code;
        public final String state;
        public final String name;
        public final double lat;
        public final double lon;

        public PrayerZone(String code, String state, String name, double lat, double lon) {
            this.code = code;
            this.state = state;
            this.name = name;
            this.lat = lat;
            this.lon = lon;
        }

        public String getDisplayName() {
            return name + " (" + state + " • " + code + ")";
        }
    }

    private static final List<PrayerZone> PRESET_ZONES = new ArrayList<>();

    static {
        // W.P. & Putrajaya
        PRESET_ZONES.add(new PrayerZone("WLY01", "W.P.", "Jakarta", 2.9158, 101.6909));
        PRESET_ZONES.add(new PrayerZone("WLY02", "W.P.", "Labuan", 5.3347, 115.2289));

        // Selangor
        PRESET_ZONES.add(new PrayerZone("SGR01", "Selangor", "Shah Alam, Petaling, Gombak, Sepang", 3.0683, 101.5058));
        PRESET_ZONES.add(new PrayerZone("SGR02", "Selangor", "Kuala Selangor, Sabak Bernam", 3.3500, 101.2500));
        PRESET_ZONES.add(new PrayerZone("SGR03", "Selangor", "Klang, Kuala Langat, Banting", 3.0108, 101.4081));

        // Melaka
        PRESET_ZONES.add(new PrayerZone("MLK01", "Melaka", "Bandaraya Melaka, Alor Gajah, Jasin", 2.1909, 102.2571));

        // Negeri Sembilan
        PRESET_ZONES.add(new PrayerZone("NGS02", "Negeri Sembilan", "Seremban, Port Dickson, Nilai", 2.6953, 101.9056));
        PRESET_ZONES.add(new PrayerZone("NGS01", "Negeri Sembilan", "Tampin, Jempol", 2.4700, 102.2300));

        // Johor
        PRESET_ZONES.add(new PrayerZone("JHR02", "Johor", "Johor Bahru, Pasir Gudang, Kulai", 1.4947, 103.7419));
        PRESET_ZONES.add(new PrayerZone("JHR03", "Johor", "Batu Pahat, Muar, Tangkak, Segamat", 1.9191, 102.8658));
        PRESET_ZONES.add(new PrayerZone("JHR04", "Johor", "Kluang, Mersing", 2.0306, 103.3214));

        // Pulau Pinang
        PRESET_ZONES.add(new PrayerZone("PNG01", "Pulau Pinang", "Seluruh Negeri Pulau Pinang", 5.3942, 100.3983));

        // Perak
        PRESET_ZONES.add(new PrayerZone("PRK02", "Perak", "Ipoh, Batu Gajah, Kampar", 4.6397, 101.1083));
        PRESET_ZONES.add(new PrayerZone("PRK03", "Perak", "Taiping, Bagan Serai, Parit Buntar", 4.8569, 100.7417));
        PRESET_ZONES.add(new PrayerZone("PRK04", "Perak", "Lumut, Manjung, Pangkor, Sitiawan", 4.1953, 100.6728));
        PRESET_ZONES.add(new PrayerZone("PRK01", "Perak", "Tapah, Slim River, Tanjung Malim", 3.6800, 101.5200));

        // Kedah & Perlis
        PRESET_ZONES.add(new PrayerZone("KDH01", "Kedah", "Kota Setar, Pokok Sena, Kubang Pasu", 6.1371, 100.3469));
        PRESET_ZONES.add(new PrayerZone("KDH02", "Kedah", "Kuala Muda, Yan, Pendang, Sungai Petani", 5.6297, 100.4677));
        PRESET_ZONES.add(new PrayerZone("KDH07", "Kedah", "Pulau Langkawi", 6.3316, 99.8585));
        PRESET_ZONES.add(new PrayerZone("PLS01", "Perlis", "Kangar, Padang Besar, Arau", 6.4299, 100.2109));

        // Pahang
        PRESET_ZONES.add(new PrayerZone("PHG02", "Pahang", "Kuantan, Pekan, Muadzam Shah", 3.8192, 103.2966));
        PRESET_ZONES.add(new PrayerZone("PHG03", "Pahang", "Jerantut, Temerloh, Maran, Bera", 3.4478, 102.4178));
        PRESET_ZONES.add(new PrayerZone("PHG04", "Pahang", "Bentong, Raub, Lipis", 3.5200, 101.9000));
        PRESET_ZONES.add(new PrayerZone("PHG06", "Pahang", "Cameron Highlands", 4.4700, 101.3800));

        // Terengganu & Kelantan
        PRESET_ZONES.add(new PrayerZone("TRG01", "Terengganu", "Kuala Terengganu, Marang, Kuala Nerus", 5.3117, 103.1325));
        PRESET_ZONES.add(new PrayerZone("TRG04", "Terengganu", "Kemaman, Dungun", 4.2622, 103.4258));
        PRESET_ZONES.add(new PrayerZone("KTN01", "Kelantan", "Kota Bharu, Bachok, Pasir Puteh, Tumpat", 6.1256, 102.2386));
        PRESET_ZONES.add(new PrayerZone("KTN02", "Kelantan", "Machang, Pasir Mas, Tanah Merah", 5.7600, 102.2100));

        // Sabah
        PRESET_ZONES.add(new PrayerZone("SBH07", "Sabah", "Kota Kinabalu, Penampang, Tuaran, Papar", 6.0971, 116.1665));
        PRESET_ZONES.add(new PrayerZone("SBH03", "Sabah", "Tawau, Balung, Merotai, Kalabakan", 4.2564, 117.8931));
        PRESET_ZONES.add(new PrayerZone("SBH05", "Sabah", "Kudat, Kota Marudu, Pitas, Sandakan", 5.8456, 118.0678));

        // Sarawak
        PRESET_ZONES.add(new PrayerZone("SWK08", "Sarawak", "Kuching, Bau, Lundu, Samarahan, Serian", 1.5622, 110.3889));
        PRESET_ZONES.add(new PrayerZone("SWK04", "Sarawak", "Bintulu, Tatau, Sebauh", 3.1772, 113.0412));
        PRESET_ZONES.add(new PrayerZone("SWK02", "Sarawak", "Miri, Niah, Bekenu, Sibuti", 4.4947, 114.0434));
        PRESET_ZONES.add(new PrayerZone("SWK05", "Sarawak", "Sibu, Mukah, Kanowit, Song", 2.2981, 111.8344));
    }

    public static List<PrayerZone> getPresetZones() {
        return Collections.unmodifiableList(PRESET_ZONES);
    }

    public static String getActiveZone(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (prefs.contains("manual_prayer_zone")) {
            return prefs.getString("manual_prayer_zone", "WLY01");
        }
        if (isCustomLocation(context)) return "CUSTOM";
        return getJakimZone(context);
    }

    public static String getActiveZoneName(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (prefs.contains("manual_prayer_zone_name")) {
            return prefs.getString("manual_prayer_zone_name", "Jakarta");
        }
        // A non-Malaysian location has no JAKIM zone: label it with the place the person chose.
        if (isCustomLocation(context)) {
            String loc = AppLocationManager.getLocationName(context);
            if (loc != null && !loc.trim().isEmpty()) return loc;
        }
        String zone = getActiveZone(context);
        for (PrayerZone pz : PRESET_ZONES) {
            if (pz.code.equalsIgnoreCase(zone)) return pz.name;
        }
        return AppLocationManager.getLocationName(context);
    }

    public static String getShortZoneName(Context context) {
        String name = getActiveZoneName(context);
        if (name == null || name.trim().isEmpty()) return "Jakarta";
        int cut = name.indexOf(',');
        if (cut > 0) name = name.substring(0, cut);
        cut = name.indexOf('&');
        if (cut > 0) name = name.substring(0, cut);
        cut = name.indexOf('(');
        if (cut > 0) name = name.substring(0, cut);
        name = name.trim();
        return name.isEmpty() ? "Jakarta" : name;
    }

    public static void clearManualZone(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit()
                .remove("manual_prayer_zone")
                .remove("manual_prayer_zone_name")
                .remove("manual_lat")
                .remove("manual_lon")
                .apply();
    }

    public static void setPrayerZone(Context context, PrayerZone zone) {
        if (zone == null) return;
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit()
                .putString("manual_prayer_zone", zone.code)
                .putString("manual_prayer_zone_name", zone.name)
                .putString("manual_lat", String.valueOf(zone.lat))
                .putString("manual_lon", String.valueOf(zone.lon))
                .apply();
        sync(context, null);
    }

    private static String getJakimZone(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (prefs.contains("manual_prayer_zone")) {
            return prefs.getString("manual_prayer_zone", "WLY01");
        }
        String loc = AppLocationManager.getLocationName(context).toLowerCase(Locale.ROOT);
        String stationId = AppLocationManager.getStationId(context).toUpperCase(Locale.ROOT);

        if (loc.contains("putrajaya") || stationId.equals("CA17W")) return "WLY01";
        if (loc.contains("kuala lumpur") || loc.contains("cheras") || loc.contains("batu muda")) return "WLY01";
        if (loc.contains("labuan")) return "WLY02";
        if (loc.contains("melaka") || loc.contains("alor gajah") || loc.contains("rambai")) return "MLK01";
        if (loc.contains("petaling") || loc.contains("shah alam")) return "SGR01";
        if (loc.contains("klang") || loc.contains("banting")) return "SGR03";
        if (loc.contains("seremban") || loc.contains("nilai") || loc.contains("port dickson")) return "NGS02";
        if (loc.contains("johor") || loc.contains("larkin") || loc.contains("pasir gudang")) return "JHR02";
        if (loc.contains("batu pahat")) return "JHR03";
        if (loc.contains("kluang")) return "JHR04";
        if (loc.contains("tangkak")) return "JHR01";
        if (loc.contains("pinang") || loc.contains("seberang") || loc.contains("minden")) return "PNG01";
        if (loc.contains("ipoh") || loc.contains("tasek") || loc.contains("taiping")) return "PRK02";
        if (loc.contains("manjung")) return "PRK01";
        if (loc.contains("alor setar")) return "KDH01";
        if (loc.contains("sungai petani")) return "KDH02";
        if (loc.contains("langkawi")) return "KDH07";
        if (loc.contains("perlis") || loc.contains("kangar")) return "PLS01";
        if (loc.contains("kuantan")) return "PHG02";
        if (loc.contains("temerloh")) return "PHG04";
        if (loc.contains("terengganu") || loc.contains("kemaman")) return "TRG01";
        if (loc.contains("kota bharu") || loc.contains("kelantan")) return "KTN01";
        if (loc.contains("kota kinabalu")) return "SBH07";
        if (loc.contains("sandakan")) return "SBH05";
        if (loc.contains("tawau")) return "SBH03";
        if (loc.contains("kuching")) return "SWK08";
        if (loc.contains("miri")) return "SWK04";
        if (loc.contains("bintulu")) return "SWK06";
        if (loc.contains("sibu")) return "SWK05";

        return "WLY01"; // Default Putrajaya
    }

    static class PrayerItem {
        final String name;
        final String timeStr;
        final boolean isNext;
        final boolean isPast;

        PrayerItem(String name, String timeStr, boolean isNext, boolean isPast) {
            this.name = name;
            this.timeStr = timeStr;
            this.isNext = isNext;
            this.isPast = isPast;
        }
    }

    /** Returns all 6 prayers for today with formatted times and status. */
    static PrayerItem[] getPrayerItems(Context context) {
        Calendar now = Calendar.getInstance();
        int day = now.get(Calendar.DAY_OF_MONTH);
        long[] times = getTimesForToday(context, day);
        if (times == null || times.length < 6) {
            times = calculateFallback(context, now);
        }
        long nowMs = now.getTimeInMillis();
        int nextIdx = -1;
        for (int i = 0; i < times.length; i++) {
            if (i == 1) continue;
            if (times[i] > nowMs) {
                nextIdx = i;
                break;
            }
        }
        if (nextIdx == -1) nextIdx = 0;

        SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm", Locale.getDefault());
        PrayerItem[] items = new PrayerItem[6];
        for (int i = 0; i < 6; i++) {
            items[i] = new PrayerItem(
                    PRAYER_NAMES[i],
                    timeFmt.format(new Date(times[i])),
                    i == nextIdx,
                    times[i] < nowMs
            );
        }
        return items;
    }

    /** Returns formatted clean UI CharSequence with highlighted next prayer, bold and legible. */
    static CharSequence getFormatted(Context context) {
        Calendar now = Calendar.getInstance();
        int day = now.get(Calendar.DAY_OF_MONTH);
        long[] times = getTimesForToday(context, day);

        if (times == null || times.length < 6) {
            times = calculateFallback(context, now);
        }

        long nowMs = now.getTimeInMillis();
        int nextIdx = -1;

        // Find next prayer (skip Syuruk idx 1)
        for (int i = 0; i < times.length; i++) {
            if (i == 1) continue;
            if (times[i] > nowMs) {
                nextIdx = i;
                break;
            }
        }
        if (nextIdx == -1) nextIdx = 0; // After Isyak -> Subuh next morning
        // After Isya the next prayer is tomorrow's Subuh, not today's (already past).
        boolean nextTomorrow = nextIdx == 0 && times[0] < nowMs;

        SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm", Locale.getDefault());

        SpannableStringBuilder sb = new SpannableStringBuilder();

        // LINE 1: LOCATION  •  BERIKUTNYA: ASAR 16:18
        String nextName = PRAYER_NAMES[nextIdx].toUpperCase(Locale.ROOT);
        long displayTimeMs = times[nextIdx];
        if (nextTomorrow) {
            Calendar tomorrow = (Calendar) now.clone();
            tomorrow.add(Calendar.DAY_OF_MONTH, 1);
            long[] tomTimes = getTimesForToday(context, tomorrow.get(Calendar.DAY_OF_MONTH));
            if (tomTimes == null || tomTimes.length < 6) tomTimes = calculateFallback(context, tomorrow);
            if (tomTimes != null && tomTimes.length > 0) displayTimeMs = tomTimes[0];
        }
        String nextTime = timeFmt.format(new Date(displayTimeMs));

        int l1Start = sb.length();
        String zoneHeader = getShortZoneName(context).toUpperCase(Locale.ROOT);
        sb.append(zoneHeader).append("  •  BERIKUTNYA: ").append(nextName).append(" ").append(nextTime);
        if (nextTomorrow) sb.append(" (BESOK)");
        int l1End = sb.length();
        sb.setSpan(new ForegroundColorSpan(0xFFFFFFFF), l1Start, l1End, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new StyleSpan(Typeface.BOLD), l1Start, l1End, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        sb.append("\n");

        // LINE 2: SUBUH 05:49  •  SYURUK 06:55  •  ZOHOR 13:01  •  ASAR 16:18  •  MAGHRIB 19:00  •  ISYAK 20:10
        int[] displayIndices = {0, 1, 2, 3, 4, 5}; // Subuh, Syuruk, Zohor, Asar, Maghrib, Isyak
        for (int i = 0; i < displayIndices.length; i++) {
            int idx = displayIndices[i];
            boolean isNext = (idx == nextIdx) && !nextTomorrow;
            int start = sb.length();

            String name = PRAYER_NAMES[idx].toUpperCase(Locale.ROOT);
            String timeStr = timeFmt.format(new Date(times[idx]));

            sb.append(name).append(" ").append(timeStr);
            if (isNext) {
                sb.setSpan(new ForegroundColorSpan(0xFF4ADE80), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new StyleSpan(Typeface.BOLD), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else {
                sb.setSpan(new ForegroundColorSpan(0xE6FFFFFF), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new StyleSpan(Typeface.BOLD), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }

            if (i < displayIndices.length - 1) {
                int dotStart = sb.length();
                sb.append("  •  ");
                sb.setSpan(new ForegroundColorSpan(0x80FFFFFF), dotStart, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }

        return sb;
    }

    private static long[] getTimesForToday(Context context, int day) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String jsonStr = prefs.getString(KEY_JSON, null);
            if (jsonStr == null) return null;

            JSONObject obj = new JSONObject(jsonStr);
            JSONArray prayers = obj.getJSONArray("prayers");
            for (int i = 0; i < prayers.length(); i++) {
                JSONObject p = prayers.getJSONObject(i);
                if (p.optInt("day") == day) {
                    long[] res = new long[6];
                    for (int k = 0; k < 6; k++) {
                        res[k] = p.getLong(PRAYER_KEYS[k]) * 1000L;
                    }
                    return res;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** Astronomical calculation fallback based on user's active Malaysian location. */
    private static long[] calculateFallback(Context context, Calendar cal) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        double latitude = prefs.contains("manual_lat")
                ? Double.parseDouble(prefs.getString("manual_lat", String.valueOf(AppLocationManager.getLatitude(context))))
                : AppLocationManager.getLatitude(context);
        double longitude = prefs.contains("manual_lon")
                ? Double.parseDouble(prefs.getString("manual_lon", String.valueOf(AppLocationManager.getLongitude(context))))
                : AppLocationManager.getLongitude(context);

        int year = cal.get(Calendar.YEAR);
        int month = cal.get(Calendar.MONTH) + 1;
        int day = cal.get(Calendar.DAY_OF_MONTH);

        double d = 367.0 * year - Math.floor((7.0 * (year + Math.floor((month + 9.0) / 12.0))) / 4.0)
                + Math.floor((275.0 * month) / 9.0) + day - 730531.5;

        double L = (280.461 + 0.9856474 * d) % 360.0;
        if (L < 0) L += 360.0;
        double M = (357.528 + 0.9856003 * d) % 360.0;
        if (M < 0) M += 360.0;

        double lambda = L + 1.915 * Math.sin(Math.toRadians(M)) + 0.020 * Math.sin(Math.toRadians(2 * M));
        double epsilon = 23.439 - 0.0000004 * d;
        double alpha = Math.toDegrees(Math.atan2(Math.cos(Math.toRadians(epsilon)) * Math.sin(Math.toRadians(lambda)), Math.cos(Math.toRadians(lambda)))) / 15.0;
        double delta = Math.toDegrees(Math.asin(Math.sin(Math.toRadians(epsilon)) * Math.sin(Math.toRadians(lambda))));

        double noon = 12.0 + tzHours() - (longitude / 15.0) - (L / 15.0 - alpha);
        if (noon < 0) noon += 24.0;
        if (noon >= 24) noon -= 24.0;

        double fajrHour = noon - sunAngleHour(-20.0, latitude, delta);
        double sunriseHour = noon - sunAngleHour(-0.833, latitude, delta);
        double asrHour = noon + asrHour(1, latitude, delta);
        double sunsetHour = noon + sunAngleHour(-0.833, latitude, delta);
        double ishaHour = noon + sunAngleHour(-18.0, latitude, delta);

        // Malaysia standard rounding & +2 min ihtiyat
        double[] hours = {fajrHour + 0.05, sunriseHour, noon + 0.04, asrHour + 0.04, sunsetHour + 0.05, ishaHour + 0.05};
        long[] res = new long[6];
        Calendar base = (Calendar) cal.clone();
        base.set(Calendar.SECOND, 0);
        base.set(Calendar.MILLISECOND, 0);

        for (int i = 0; i < 6; i++) {
            int h = (int) hours[i];
            int m = (int) ((hours[i] - h) * 60.0);
            base.set(Calendar.HOUR_OF_DAY, h);
            base.set(Calendar.MINUTE, m);
            res[i] = base.getTimeInMillis();
        }
        return res;
    }

    private static double sunAngleHour(double angle, double lat, double delta) {
        double cosH = (Math.sin(Math.toRadians(angle)) - Math.sin(Math.toRadians(lat)) * Math.sin(Math.toRadians(delta)))
                / (Math.cos(Math.toRadians(lat)) * Math.cos(Math.toRadians(delta)));
        cosH = Math.max(-1.0, Math.min(1.0, cosH));
        return Math.toDegrees(Math.acos(cosH)) / 15.0;
    }

    private static double asrHour(int shadowFactor, double lat, double delta) {
        double phi = Math.abs(lat - delta);
        double cotA = shadowFactor + Math.tan(Math.toRadians(phi));
        double angle = Math.toDegrees(Math.atan(1.0 / cotA));
        return sunAngleHour(angle, lat, delta);
    }

    static class ScheduleItem {
        final String name;
        final String timeFormatted;
        final boolean isNext;
        final boolean isPast;

        ScheduleItem(String name, String timeFormatted, boolean isNext, boolean isPast) {
            this.name = name;
            this.timeFormatted = timeFormatted;
            this.isNext = isNext;
            this.isPast = isPast;
        }
    }

    /** Returns full day prayer list for popup display. */
    static java.util.List<ScheduleItem> getFullSchedule(Context context) {
        java.util.List<ScheduleItem> list = new java.util.ArrayList<>();
        Calendar now = Calendar.getInstance();
        long[] times = getTimesForToday(context, now.get(Calendar.DAY_OF_MONTH));
        if (times == null || times.length < 6) times = calculateFallback(context, now);

        long nowMs = now.getTimeInMillis();
        int nextIdx = -1;
        for (int i = 0; i < times.length; i++) {
            if (i == 1) continue; // Skip Syuruk (sunrise)
            if (times[i] > nowMs) {
                nextIdx = i;
                break;
            }
        }
        if (nextIdx == -1) nextIdx = 0;

        SimpleDateFormat sdf = new SimpleDateFormat("h:mm a", Locale.getDefault());
        for (int i = 0; i < times.length; i++) {
            boolean isNext = (i == nextIdx);
            boolean isPast = (times[i] < nowMs);
            list.add(new ScheduleItem(PRAYER_NAMES[i], sdf.format(new Date(times[i])), isNext, isPast));
        }
        return list;
    }

    /** Returns short summary of next prayer (e.g. for screensaver). */
    static String getNextPrayerSummary(Context context) {
        Calendar now = Calendar.getInstance();
        long[] times = getTimesForToday(context, now.get(Calendar.DAY_OF_MONTH));
        if (times == null || times.length < 6) times = calculateFallback(context, now);

        long nowMs = now.getTimeInMillis();
        int nextIdx = -1;
        for (int i = 0; i < times.length; i++) {
            if (i == 1) continue; // Skip Syuruk (sunrise)
            if (times[i] > nowMs) {
                nextIdx = i;
                break;
            }
        }

        long nextTimeMs;
        String prayerName;
        if (nextIdx != -1) {
            prayerName = PRAYER_NAMES[nextIdx];
            nextTimeMs = times[nextIdx];
        } else {
            // All prayers today have passed (after Isyak) -> Next is tomorrow's Subuh
            prayerName = PRAYER_NAMES[0];
            Calendar tomorrow = (Calendar) now.clone();
            tomorrow.add(Calendar.DAY_OF_MONTH, 1);
            long[] tomTimes = getTimesForToday(context, tomorrow.get(Calendar.DAY_OF_MONTH));
            if (tomTimes == null || tomTimes.length < 6) {
                tomTimes = calculateFallback(context, tomorrow);
            }
            nextTimeMs = (tomTimes != null && tomTimes.length > 0) ? tomTimes[0] : (times[0] + 86400000L);
        }

        SimpleDateFormat sdf = new SimpleDateFormat("h:mm a", Locale.getDefault());
        String zone = getShortZoneName(context);
        return prayerName + " • " + sdf.format(new Date(nextTimeMs)) + " (" + zone + ")";
    }

    /** Returns Islamic Hijrah date (e.g. "11 Jamadilawal 1448H"). */
    static String getHijriDate() {
        try {
            java.time.chrono.HijrahDate h = java.time.chrono.HijrahDate.now();
            int day = h.get(java.time.temporal.ChronoField.DAY_OF_MONTH);
            int month = h.get(java.time.temporal.ChronoField.MONTH_OF_YEAR);
            int year = h.get(java.time.temporal.ChronoField.YEAR);
            String[] months = {
                "Muharram", "Safar", "Rabiul Awal", "Rabiul Akhir",
                "Jumadil Awal", "Jumadil Akhir", "Rajab", "Syaban",
                "Ramadan", "Syawal", "Zulkaidah", "Zulhijjah"
            };
            String monthName = (month >= 1 && month <= 12) ? months[month - 1] : "Hijrah";
            return day + " " + monthName + " " + year + "H";
        } catch (Exception e) {
            return "";
        }
    }
}
