package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.media.MediaMetadata;
import android.media.browse.MediaBrowser;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.View;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The screensaver's lyrics card (layout dream_clock, dreamLyricsCard): the song BitChord is playing,
 * synced to its position. It mirrors the same session as the now-playing card, and shows nothing at
 * all unless the session is actually playing, so an idle screensaver keeps only clock and weather.
 *
 * Synced lyrics come from LRCLIB (free, no key, no account), fetched once per track and cached in
 * memory; a track without a match keeps the card with "No lyrics" rather than a blank half-screen.
 */
final class DreamLyrics {

    private static final long TICK_MS = 500L;
    /** Lines on the card: the current one bright, the next ones dim. */
    private static final int LINES = 4;
    /** A stored duration this close to the session's picks the right version of the song. */
    private static final long DURATION_SLACK_MS = 5000L;
    private static final Pattern LRC_TIME = Pattern.compile("\\[(\\d{1,2}):(\\d{2})(?:[.:](\\d{1,3}))?\\]");

    private static final class Line {
        final long at;
        final String text;

        Line(long at, String text) {
            this.at = at;
            this.text = text;
        }
    }

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final LruCache<String, List<Line>> cache = new LruCache<>(16);
    private final View card;
    private final TextView title;
    private final TextView[] lineViews = new TextView[LINES];

    private ExecutorService fetcher;
    private MediaBrowser browser;
    private MediaController controller;
    /** Track the card is showing; a fetch that returns after the track changed drops its result. */
    private String trackKey = "";
    private final List<Line> lines = new ArrayList<>();
    /** The fetch for trackKey has finished, with or without a match. */
    private boolean fetched;

    /** Moves the lyrics while something plays; stops itself when the session pauses. */
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            PlaybackState state = controller == null ? null : controller.getPlaybackState();
            if (state == null || state.getState() != PlaybackState.STATE_PLAYING) {
                hide();
                return;
            }
            show();
            render(position(state));
            handler.postDelayed(this, TICK_MS);
        }
    };

    private final MediaBrowser.ConnectionCallback connection = new MediaBrowser.ConnectionCallback() {
        @Override
        public void onConnected() {
            if (browser == null) return;
            MediaSession.Token token = browser.getSessionToken();
            if (token == null) return;   // the player creates its session lazily
            try {
                controller = new MediaController(context, token);
                controller.registerCallback(callback);
                onState();
            } catch (RuntimeException e) {
                controller = null;
            }
        }

        @Override
        public void onConnectionSuspended() {
            release();
        }

        @Override
        public void onConnectionFailed() {
            release();
        }
    };

    private final MediaController.Callback callback = new MediaController.Callback() {
        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            onState();
        }

        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            onState();
        }

        @Override
        public void onSessionDestroyed() {
            release();
        }
    };

    DreamLyrics(Context context, View root) {
        this.context = context;
        card = root.findViewById(R.id.dreamLyricsCard);
        title = root.findViewById(R.id.dreamLyricsTitle);
        lineViews[0] = root.findViewById(R.id.dreamLyricsLine0);
        lineViews[1] = root.findViewById(R.id.dreamLyricsLine1);
        lineViews[2] = root.findViewById(R.id.dreamLyricsLine2);
        lineViews[3] = root.findViewById(R.id.dreamLyricsLine3);
        hide();
    }

    /** Bind while the screensaver is visible. This may start the player's service, but shows nothing by itself. */
    void start() {
        if (card == null || browser != null) return;
        if (!NowPlayingCard.installed(context)) return;
        if (fetcher == null) fetcher = Executors.newSingleThreadExecutor();
        browser = new MediaBrowser(context, new ComponentName(NowPlayingCard.PACKAGE, NowPlayingCard.SERVICE),
                connection, null);
        try {
            browser.connect();
        } catch (RuntimeException e) {
            browser = null;
        }
    }

    void stop() {
        handler.removeCallbacks(tick);
        release();
        if (browser != null) {
            try {
                browser.disconnect();
            } catch (RuntimeException ignored) {
                // already gone
            }
            browser = null;
        }
        if (fetcher != null) {
            fetcher.shutdownNow();
            fetcher = null;
        }
        hide();
    }

    private void release() {
        handler.removeCallbacks(tick);
        if (controller != null) {
            controller.unregisterCallback(callback);
            controller = null;
        }
        hide();
    }

    private void onState() {
        PlaybackState state = controller == null ? null : controller.getPlaybackState();
        if (state == null || state.getState() != PlaybackState.STATE_PLAYING) {
            handler.removeCallbacks(tick);
            hide();
            return;
        }
        syncTrack();
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    private void syncTrack() {
        MediaMetadata metadata = controller == null ? null : controller.getMetadata();
        if (metadata == null) return;
        CharSequence titleText = metadata.getText(MediaMetadata.METADATA_KEY_TITLE);
        CharSequence artistText = metadata.getText(MediaMetadata.METADATA_KEY_ARTIST);
        if ((titleText == null || titleText.length() == 0) && metadata.getDescription() != null) {
            titleText = metadata.getDescription().getTitle();
            if (artistText == null || artistText.length() == 0) artistText = metadata.getDescription().getSubtitle();
        }
        String name = titleText == null ? "" : titleText.toString();
        if (name.isEmpty()) return;
        String artist = artistText == null ? "" : artistText.toString();
        long duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
        String key = name + "\n" + artist + "\n" + duration;
        if (key.equals(trackKey)) return;
        trackKey = key;

        if (title != null) title.setText(artist.isEmpty() ? name : name + " — " + artist);
        lines.clear();
        List<Line> cached = cache.get(key);
        fetched = cached != null;
        if (cached != null) lines.addAll(cached);
        for (int i = 0; i < lineViews.length; i++) setLine(i, null, false);

        if (cached != null || fetcher == null) return;
        fetcher.execute(() -> {
            List<Line> found = fetch(name, artist, duration);
            handler.post(() -> {
                if (!key.equals(trackKey)) return;   // the track changed while this was in flight
                if (found != null) {
                    cache.put(key, found);
                    lines.addAll(found);
                }
                fetched = true;
            });
        });
    }

    private void render(long position) {
        if (!fetched) return;   // the card is up, the first lines arrive with the fetch
        if (lines.isEmpty()) {
            setLine(0, context.getString(R.string.dream_no_lyrics), true);
            for (int i = 1; i < lineViews.length; i++) setLine(i, null, false);
            return;
        }
        int index = 0;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).at > position) break;
            index = i;
        }
        for (int i = 0; i < lineViews.length; i++) {
            int at = index + i;
            setLine(i, at < lines.size() ? lines.get(at).text : null, i == 0);
        }
    }

    /** One row; null empties it so the card holds only the lines still to come. */
    private void setLine(int index, String text, boolean current) {
        TextView view = lineViews[index];
        if (view == null) return;
        if (text == null) {
            view.setText("");
            view.setVisibility(View.GONE);
            return;
        }
        view.setVisibility(View.VISIBLE);
        if (!text.contentEquals(view.getText())) {
            view.setText(text);
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 25f);
        }
        view.setTextColor(current ? 0xFFFFFFFF : 0x80FFFFFF);
        fitLine(view);
    }

    /** Long line: one row, shrunk to fit rather than wrapped or ended with "...". */
    private static void fitLine(TextView view) {
        float max = view.getWidth() - view.getPaddingLeft() - view.getPaddingRight();
        if (max <= 0) return;   // not laid out yet; the next tick fits it
        float width = view.getPaint().measureText(view.getText().toString());
        if (width > max) view.setTextSize(TypedValue.COMPLEX_UNIT_SP, Math.max(11f, 25f * max / width));
    }

    private static long position(PlaybackState state) {
        long position = state.getPosition();
        long since = state.getLastPositionUpdateTime();
        if (since > 0) position += SystemClock.elapsedRealtime() - since;
        return Math.max(0L, position);
    }

    private void show() {
        if (card != null) card.setVisibility(View.VISIBLE);
    }

    private void hide() {
        if (card != null) card.setVisibility(View.GONE);
    }

    /** LRCLIB's best synced match for this title, artist and duration, or null. */
    private static List<Line> fetch(String name, String artist, long durationMs) {
        HttpURLConnection connection = null;
        try {
            String query = artist.isEmpty() ? name : name + " " + artist;
            connection = (HttpURLConnection) new URL("https://lrclib.net/api/search?q="
                    + URLEncoder.encode(query, "UTF-8")).openConnection();
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(8000);
            connection.setRequestProperty("User-Agent",
                    "PapayTVLauncher/1.0 (https://github.com/Hanafiwidy/papay-tv-launcher)");
            if (connection.getResponseCode() != 200) return null;
            StringBuilder json = new StringBuilder();
            try (BufferedReader in = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                String row;
                while ((row = in.readLine()) != null) json.append(row);
            }
            JSONArray results = new JSONArray(json.toString());
            JSONObject best = null;
            long bestDelta = Long.MAX_VALUE;
            for (int i = 0; i < results.length(); i++) {
                JSONObject item = results.optJSONObject(i);
                if (item == null || item.optString("syncedLyrics", "").isEmpty()) continue;
                long delta = 0L;
                if (durationMs > 0 && item.has("duration")) {
                    delta = Math.abs(Math.round(item.optDouble("duration", 0) * 1000) - durationMs);
                    // The stored duration is what tells the album version from a cover or a live take.
                    if (delta > DURATION_SLACK_MS) continue;
                }
                if (delta < bestDelta) {
                    bestDelta = delta;
                    best = item;
                }
            }
            return best == null ? null : parse(best.optString("syncedLyrics", ""));
        } catch (Exception e) {
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    /** LRC text to timed lines: [mm:ss.xx] before the words, sometimes several tags on one line. */
    private static List<Line> parse(String lrc) {
        List<Line> out = new ArrayList<>();
        for (String raw : lrc.split("\n")) {
            Matcher m = LRC_TIME.matcher(raw);
            List<Long> times = new ArrayList<>();
            int end = 0;
            while (m.find()) {
                long minutes = Long.parseLong(m.group(1));
                long seconds = Long.parseLong(m.group(2));
                String fraction = m.group(3);
                long millis = fraction == null ? 0L
                        : fraction.length() == 1 ? Long.parseLong(fraction) * 100L
                        : fraction.length() == 2 ? Long.parseLong(fraction) * 10L
                        : Long.parseLong(fraction);
                times.add(minutes * 60_000L + seconds * 1_000L + millis);
                end = m.end();
            }
            String text = raw.substring(end).trim();
            if (text.isEmpty()) continue;
            for (long at : times) out.add(new Line(at, text));
        }
        out.sort((a, b) -> Long.compare(a.at, b.at));
        return out;
    }
}
