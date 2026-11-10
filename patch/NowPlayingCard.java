package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.media.MediaMetadata;
import android.media.browse.MediaBrowser;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.LruCache;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The now-playing card on Home (config.xml, now_playing): cover, title, artist, transport controls and a
 * progress line, in one glass panel.
 *
 * <p>The card never starts playback itself. It binds to the media session of BitChord TV while Home is
 * visible and mirrors whatever that session reports, so it stays honest when the app is gone (empty state).
 * Left/right on the remote change track, OK plays or pauses, holding OK opens the player, and in the empty
 * state OK opens BitChord.
 *
 * <p>Binding another app's MediaBrowserService means its process starts when Home does and is released
 * when Home stops, which is what keeps the card responsive without a notification-listener grant. The
 * service is exported and its session accepts external controllers.
 */
public class NowPlayingCard extends FrameLayout {

    /** BitChord TV: the phone build is a different package and has no media session for a TV remote. */
    private static final String PACKAGE = "com.music.bitchord.tv";
    private static final String SERVICE = "com.music.bitchord.playback.PlaybackService";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService artExecutor = Executors.newSingleThreadExecutor();
    private final LruCache<String, Bitmap> artCache = new LruCache<>(8);

    private ImageView art;
    private TextView title;
    private TextView artist;
    private ImageView previous;
    private ImageView play;
    private ImageView next;
    private ProgressBar progress;
    private TextView time;

    private MediaBrowser browser;
    private MediaController controller;
    private boolean hasSession;
    /** Artwork URL on screen (or being fetched); an empty string when there is none. */
    private String artUrl = "";

    /** The player creates its session lazily, so a token can be missing right after connecting. */
    private final Runnable sessionRetry = new Runnable() {
        @Override
        public void run() {
            if (browser == null || controller != null) return;
            attach(browser.getSessionToken());
        }
    };

    private final MediaBrowser.ConnectionCallback connectionCallback = new MediaBrowser.ConnectionCallback() {
        @Override
        public void onConnected() {
            if (browser == null) return;
            attach(browser.getSessionToken());
        }

        @Override
        public void onConnectionSuspended() {
            releaseController();
            showEmpty();
        }

        @Override
        public void onConnectionFailed() {
            releaseController();
            showEmpty();
        }
    };

    /** Mirrors the session, or keeps asking for it until the player has one. A null token must never reach MediaController. */
    private void attach(MediaSession.Token token) {
        if (token == null) {
            showEmpty();
            handler.removeCallbacks(sessionRetry);
            handler.postDelayed(sessionRetry, 4000L);
            return;
        }
        try {
            controller = new MediaController(getContext(), token);
            controller.registerCallback(controllerCallback);
            render();
        } catch (RuntimeException e) {
            controller = null;
            showEmpty();
            handler.removeCallbacks(sessionRetry);
            handler.postDelayed(sessionRetry, 4000L);
        }
    }

    private final MediaController.Callback controllerCallback = new MediaController.Callback() {
        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            render();
        }

        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            render();
        }

        @Override
        public void onSessionDestroyed() {
            releaseController();
            showEmpty();
            // A fresh session may appear later (the player keeps running); keep polling for one.
            handler.removeCallbacks(sessionRetry);
            handler.postDelayed(sessionRetry, 4000L);
        }
    };

    /** Keeps the progress line moving between session updates; only runs while something is playing. */
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (controller == null) return;
            PlaybackState state = controller.getPlaybackState();
            if (state == null || state.getState() != PlaybackState.STATE_PLAYING) return;
            updateProgress();
            handler.postDelayed(this, 1000L);
        }
    };

    public NowPlayingCard(Context context) {
        this(context, null);
    }

    public NowPlayingCard(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public NowPlayingCard(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setBackgroundResource(R.drawable.now_playing_bg);
        setFocusable(true);
        setFocusableInTouchMode(false);
        setClickable(true);
        LayoutInflater.from(getContext()).inflate(R.layout.now_playing_card, this, true);

        art = findViewById(R.id.nowPlayingArt);
        title = findViewById(R.id.nowPlayingTitle);
        artist = findViewById(R.id.nowPlayingArtist);
        previous = findViewById(R.id.nowPlayingPrev);
        play = findViewById(R.id.nowPlayingPlay);
        next = findViewById(R.id.nowPlayingNext);
        progress = findViewById(R.id.nowPlayingProgress);
        time = findViewById(R.id.nowPlayingTime);

        setOnClickListener(v -> {
            if (hasSession) toggle();
            else openPlayerApp();
        });
        setOnLongClickListener(v -> {
            if (!hasSession) return false;
            openPlayerApp();
            return true;
        });
        // The card is one focus target: left/right change track, so the rest of the remote keeps its
        // ordinary focus moves and Up/Down leave the card.
        setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN || !hasSession || controller == null) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                controller.getTransportControls().skipToPrevious();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                controller.getTransportControls().skipToNext();
                return true;
            }
            return false;
        });
        setOnFocusChangeListener((v, focused) -> v.animate()
                .scaleX(focused ? 1.02f : 1f)
                .scaleY(focused ? 1.02f : 1f)
                .setDuration(140L)
                .start());

        showEmpty();
    }

    /** Bind while Home is visible; MainActivity calls this from onStart. */
    void start() {
        if (browser != null) return;
        if (!installed()) {
            showEmpty();
            return;
        }
        browser = new MediaBrowser(getContext(), new ComponentName(PACKAGE, SERVICE),
                connectionCallback, null);
        try {
            browser.connect();
        } catch (RuntimeException e) {
            browser = null;
            showEmpty();
        }
    }

    /** Release the player's process when Home goes away; playback itself is untouched. */
    void stop() {
        handler.removeCallbacks(ticker);
        handler.removeCallbacks(sessionRetry);
        releaseController();
        if (browser != null) {
            try {
                browser.disconnect();
            } catch (RuntimeException ignored) {
                // already gone
            }
            browser = null;
        }
    }

    private void releaseController() {
        handler.removeCallbacks(ticker);
        if (controller != null) {
            controller.unregisterCallback(controllerCallback);
            controller = null;
        }
    }

    private void render() {
        if (controller == null) {
            showEmpty();
            return;
        }
        MediaMetadata metadata = controller.getMetadata();
        PlaybackState state = controller.getPlaybackState();
        CharSequence name = metadata != null && metadata.getDescription() != null
                ? metadata.getDescription().getTitle() : null;
        if (name == null || name.length() == 0) {
            showEmpty();
            return;
        }

        hasSession = true;
        CharSequence by = metadata.getDescription().getSubtitle();
        title.setText(name);
        artist.setText(by != null && by.length() > 0 ? by : "");
        setContentDescription(by != null && by.length() > 0 ? name + ", " + by : name.toString());

        boolean playing = state != null && state.getState() == PlaybackState.STATE_PLAYING;
        play.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        play.setContentDescription(getContext().getString(
                playing ? R.string.now_playing_pause : R.string.now_playing_play));

        long duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
        boolean timed = duration > 0;
        progress.setVisibility(timed ? View.VISIBLE : View.GONE);
        time.setVisibility(timed ? View.VISIBLE : View.GONE);

        setControlsAlpha(1f);
        loadArt(metadata);
        updateProgress();

        handler.removeCallbacks(ticker);
        if (playing) handler.postDelayed(ticker, 1000L);
    }

    private void updateProgress() {
        if (controller == null) return;
        MediaMetadata metadata = controller.getMetadata();
        if (metadata == null) return;
        long duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
        if (duration <= 0) return;

        PlaybackState state = controller.getPlaybackState();
        long position = state != null ? state.getPosition() : 0L;
        if (state != null && state.getState() == PlaybackState.STATE_PLAYING) {
            long since = state.getLastPositionUpdateTime();
            if (since > 0) position += SystemClock.elapsedRealtime() - since;
        }
        position = Math.max(0L, Math.min(position, duration));
        progress.setProgress((int) (1000L * position / duration));
        time.setText(format(position) + " / " + format(duration));
    }

    private void showEmpty() {
        hasSession = false;
        handler.removeCallbacks(ticker);
        title.setText(R.string.now_playing_nothing);
        artist.setText(R.string.now_playing_open);
        setContentDescription(getContext().getString(R.string.now_playing_nothing));
        play.setImageResource(R.drawable.ic_play);
        progress.setVisibility(View.GONE);
        time.setVisibility(View.GONE);
        setControlsAlpha(0.35f);

        artUrl = "";
        art.setImageResource(R.drawable.ic_music_note_large);
        art.setScaleType(ImageView.ScaleType.CENTER);
        art.setColorFilter(0x55FFFFFF);
    }

    private void setControlsAlpha(float alpha) {
        previous.setAlpha(alpha);
        play.setAlpha(alpha);
        next.setAlpha(alpha);
    }

    private void toggle() {
        if (controller == null) return;
        PlaybackState state = controller.getPlaybackState();
        boolean playing = state != null && state.getState() == PlaybackState.STATE_PLAYING;
        if (playing) controller.getTransportControls().pause();
        else controller.getTransportControls().play();
    }

    private void openPlayerApp() {
        if (controller != null) {
            try {
                android.app.PendingIntent intent = controller.getSessionActivity();
                if (intent != null) {
                    intent.send();
                    return;
                }
            } catch (Exception ignored) {
                // no session activity; fall back to the app's own launch intent
            }
        }
        try {
            Intent launch = getContext().getPackageManager().getLaunchIntentForPackage(PACKAGE);
            if (launch != null) getContext().startActivity(launch);
        } catch (RuntimeException ignored) {
            // the app is gone; the card stays in its empty state
        }
    }

    private boolean installed() {
        try {
            getContext().getPackageManager().getPackageInfo(PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private void loadArt(MediaMetadata metadata) {
        Bitmap inline = metadata.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (inline != null) {
            artUrl = "";
            setArt(inline);
            return;
        }
        Uri icon = metadata.getDescription() != null ? metadata.getDescription().getIconUri() : null;
        if (icon == null) {
            artUrl = "";
            showArtPlaceholder();
            return;
        }
        String url = icon.toString();
        if (url.equals(artUrl)) return;
        artUrl = url;

        Bitmap cached = artCache.get(url);
        if (cached != null) {
            setArt(cached);
            return;
        }
        showArtPlaceholder();
        artExecutor.execute(() -> {
            Bitmap bitmap = download(url);
            if (bitmap == null) return;
            handler.post(() -> {
                if (!url.equals(artUrl)) return;
                artCache.put(url, bitmap);
                setArt(bitmap);
            });
        });
    }

    private void showArtPlaceholder() {
        art.setImageResource(R.drawable.ic_music_note_large);
        art.setScaleType(ImageView.ScaleType.CENTER);
        art.setColorFilter(0x55FFFFFF);
    }

    private void setArt(Bitmap bitmap) {
        art.setColorFilter(null);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setImageBitmap(bitmap);
    }

    /** Cover from the session's artwork URL, cropped square and rounded, or null when it cannot be read. */
    private Bitmap download(String url) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(8000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", "Mozilla/5.0");
            try (InputStream in = connection.getInputStream()) {
                Bitmap raw = BitmapFactory.decodeStream(in);
                return raw == null ? null : squareRounded(raw);
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private Bitmap squareRounded(Bitmap raw) {
        float density = getResources().getDisplayMetrics().density;
        int size = Math.round(88 * density);
        int side = Math.min(raw.getWidth(), raw.getHeight());
        Bitmap square = side == raw.getWidth() && side == raw.getHeight()
                ? raw
                : Bitmap.createBitmap(raw, (raw.getWidth() - side) / 2, (raw.getHeight() - side) / 2, side, side);
        Bitmap scaled = Bitmap.createScaledBitmap(square, size, size, true);

        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setShader(new BitmapShader(scaled, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        float radius = 12f * density;
        canvas.drawRoundRect(new RectF(0, 0, size, size), radius, radius, paint);
        return out;
    }

    private static String format(long millis) {
        long seconds = Math.max(0L, millis / 1000L);
        return (seconds / 60) + ":" + (seconds % 60 < 10 ? "0" : "") + (seconds % 60);
    }
}
