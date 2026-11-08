package com.gathin.tvhome;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioManager;
import android.media.MediaDescription;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import android.view.KeyEvent;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MediaNotificationListener extends NotificationListenerService {

    private static final String TAG = "TVHomeMedia";

    public interface OnMediaStateChangedListener {
        void onMediaStateChanged(MediaInfo info);
    }

    public static final class MediaInfo {
        public final String title;
        public final String artist;
        public final String appName;
        public final String packageName;
        public final Bitmap albumArt;
        public final boolean isPlaying;

        public MediaInfo(String title, String artist, String appName, String packageName, Bitmap albumArt, boolean isPlaying) {
            this.title = title;
            this.artist = artist;
            this.appName = appName;
            this.packageName = packageName;
            this.albumArt = albumArt;
            this.isPlaying = isPlaying;
        }
    }

    private static MediaNotificationListener instance;
    private static OnMediaStateChangedListener callback;
    private static MediaInfo latestMediaInfo = null;
    private static MediaController activeController = null;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final List<MediaController> registeredControllers = new ArrayList<>();
    private MediaSessionManager.OnActiveSessionsChangedListener sessionsListener;

    private final MediaController.Callback controllerCallback = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            Log.d(TAG, "onPlaybackStateChanged: " + (state != null ? state.getState() : "null"));
            queryActiveMedia();
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            Log.d(TAG, "onMetadataChanged");
            queryActiveMedia();
        }

        @Override
        public void onSessionDestroyed() {
            Log.d(TAG, "onSessionDestroyed");
            queryActiveMedia();
        }
    };

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        instance = this;
        Log.d(TAG, "onListenerConnected");
        initSessionListener();
        queryActiveMedia();
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        Log.d(TAG, "onListenerDisconnected");
        detachControllerCallbacks();
        if (instance == this) instance = null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        detachControllerCallbacks();
        if (instance == this) instance = null;
    }

    private void initSessionListener() {
        try {
            MediaSessionManager mm = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (mm == null) return;
            ComponentName cn = new ComponentName(this, MediaNotificationListener.class);
            if (sessionsListener == null) {
                sessionsListener = controllers -> {
                    Log.d(TAG, "onActiveSessionsChanged count=" + (controllers != null ? controllers.size() : 0));
                    attachControllerCallbacks(controllers);
                    queryActiveMedia();
                };
                mm.addOnActiveSessionsChangedListener(sessionsListener, cn);
            }
            List<MediaController> list = mm.getActiveSessions(cn);
            attachControllerCallbacks(list);
        } catch (Exception e) {
            Log.w(TAG, "initSessionListener failed", e);
        }
    }

    private synchronized void attachControllerCallbacks(List<MediaController> controllers) {
        detachControllerCallbacks();
        if (controllers != null) {
            for (MediaController c : controllers) {
                try {
                    c.registerCallback(controllerCallback);
                    registeredControllers.add(c);
                } catch (Exception ignored) { }
            }
        }
    }

    private synchronized void detachControllerCallbacks() {
        for (MediaController c : registeredControllers) {
            try {
                c.unregisterCallback(controllerCallback);
            } catch (Exception ignored) { }
        }
        registeredControllers.clear();
    }

    public static void setCallback(OnMediaStateChangedListener listener) {
        callback = listener;
        if (callback != null && latestMediaInfo != null) {
            callback.onMediaStateChanged(latestMediaInfo);
        }
        if (instance != null) {
            instance.queryActiveMedia();
        }
    }

    public static MediaInfo getLatestMediaInfo() {
        return latestMediaInfo;
    }

    public static void skipToPrevious() {
        if (activeController != null) {
            try {
                PlaybackState ps = activeController.getPlaybackState();
                if (ps != null && (ps.getActions() & PlaybackState.ACTION_SKIP_TO_PREVIOUS) != 0) {
                    activeController.getTransportControls().skipToPrevious();
                } else {
                    sendMediaKeyEvent(KeyEvent.KEYCODE_MEDIA_PREVIOUS);
                }
                return;
            } catch (Exception e) {
                Log.w(TAG, "skipToPrevious error", e);
            }
        }
        sendMediaKeyEvent(KeyEvent.KEYCODE_MEDIA_PREVIOUS);
    }

    public static void togglePlayPause() {
        if (activeController != null) {
            try {
                PlaybackState ps = activeController.getPlaybackState();
                if (ps != null) {
                    int state = ps.getState();
                    if (state == PlaybackState.STATE_PLAYING) {
                        activeController.getTransportControls().pause();
                    } else {
                        activeController.getTransportControls().play();
                    }
                    return;
                }
            } catch (Exception e) {
                Log.w(TAG, "togglePlayPause error", e);
            }
        }
        sendMediaKeyEvent(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
    }

    public static void skipToNext() {
        if (activeController != null) {
            try {
                PlaybackState ps = activeController.getPlaybackState();
                if (ps != null && (ps.getActions() & PlaybackState.ACTION_SKIP_TO_NEXT) != 0) {
                    activeController.getTransportControls().skipToNext();
                } else {
                    sendMediaKeyEvent(KeyEvent.KEYCODE_MEDIA_NEXT);
                }
                return;
            } catch (Exception e) {
                Log.w(TAG, "skipToNext error", e);
            }
        }
        sendMediaKeyEvent(KeyEvent.KEYCODE_MEDIA_NEXT);
    }

    public static void sendMediaKeyEvent(int keyCode) {
        if (activeController != null) {
            try {
                long now = SystemClock.uptimeMillis();
                activeController.dispatchMediaButtonEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0));
                activeController.dispatchMediaButtonEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0));
                return;
            } catch (Exception ignored) { }
        }
        if (instance != null) {
            try {
                AudioManager am = (AudioManager) instance.getSystemService(Context.AUDIO_SERVICE);
                if (am != null) {
                    long now = SystemClock.uptimeMillis();
                    am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0));
                    am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0));
                }
            } catch (Exception ignored) { }
        }
    }

    public static void openActiveApp(Context context) {
        if (activeController != null) {
            PendingIntent sessionIntent = activeController.getSessionActivity();
            if (sessionIntent != null) {
                try {
                    sessionIntent.send();
                    return;
                } catch (Exception e) {
                    Log.w(TAG, "Failed to send session activity intent", e);
                }
            }
            String pkg = activeController.getPackageName();
            if (pkg != null) {
                Intent launch = context.getPackageManager().getLaunchIntentForPackage(pkg);
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                    try {
                        context.startActivity(launch);
                        return;
                    } catch (Exception e) {
                        Log.w(TAG, "Failed to launch app package " + pkg, e);
                    }
                }
            }
        }
        if (latestMediaInfo != null && latestMediaInfo.packageName != null) {
            Intent launch = context.getPackageManager().getLaunchIntentForPackage(latestMediaInfo.packageName);
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                try {
                    context.startActivity(launch);
                } catch (Exception ignored) { }
            }
        }
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        queryActiveMedia();
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        queryActiveMedia();
    }

    public void queryActiveMedia() {
        executor.execute(() -> {
            try {
                MediaSessionManager mm = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
                if (mm == null) {
                    dispatchMedia(null);
                    return;
                }
                ComponentName cn = new ComponentName(this, MediaNotificationListener.class);
                List<MediaController> controllers = null;
                try {
                    controllers = mm.getActiveSessions(cn);
                } catch (Exception e) {
                    Log.w(TAG, "getActiveSessions failed", e);
                }

                MediaController bestController = null;
                if (controllers != null && !controllers.isEmpty()) {
                    Log.d(TAG, "Found active sessions: " + controllers.size());
                    // 1. Look for actively PLAYING controller
                    for (MediaController mc : controllers) {
                        PlaybackState ps = mc.getPlaybackState();
                        if (ps != null && ps.getState() == PlaybackState.STATE_PLAYING) {
                            bestController = mc;
                            break;
                        }
                    }
                    // 2. If no playing controller, look for paused controller with valid metadata
                    if (bestController == null) {
                        for (MediaController mc : controllers) {
                            PlaybackState ps = mc.getPlaybackState();
                            MediaMetadata md = mc.getMetadata();
                            if ((ps != null && ps.getState() == PlaybackState.STATE_PAUSED) || md != null) {
                                bestController = mc;
                                break;
                            }
                        }
                    }
                    // 3. Fallback to first controller
                    if (bestController == null && !controllers.isEmpty()) {
                        bestController = controllers.get(0);
                    }
                }

                if (bestController != null) {
                    activeController = bestController;
                    MediaInfo info = extractMediaInfo(bestController);
                    if (info != null && (info.title != null || info.artist != null)) {
                        dispatchMedia(info);
                        return;
                    }
                }

                // If controllers returned nothing, try notification fallback
                MediaInfo notifInfo = extractFromNotifications();
                if (notifInfo != null) {
                    dispatchMedia(notifInfo);
                    return;
                }

                dispatchMedia(null);
            } catch (Exception e) {
                Log.w(TAG, "queryActiveMedia error", e);
                dispatchMedia(null);
            }
        });
    }

    private MediaInfo extractMediaInfo(MediaController mc) {
        if (mc == null) return null;
        String pkg = mc.getPackageName();
        PlaybackState ps = mc.getPlaybackState();
        MediaMetadata md = mc.getMetadata();
        boolean isPlaying = ps != null && ps.getState() == PlaybackState.STATE_PLAYING;

        String appName = "Media";
        try {
            appName = getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(pkg, 0)).toString();
        } catch (Exception ignored) { }

        String title = null;
        String artist = null;
        Bitmap art = null;
        Uri artUri = null;

        if (md != null) {
            title = md.getString(MediaMetadata.METADATA_KEY_TITLE);
            if (title == null) title = md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE);

            artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST);
            if (artist == null) artist = md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
            if (artist == null) artist = md.getString(MediaMetadata.METADATA_KEY_AUTHOR);
            if (artist == null) artist = md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);

            art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (art == null) art = md.getBitmap(MediaMetadata.METADATA_KEY_ART);

            String uriStr = md.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI);
            if (uriStr == null) uriStr = md.getString(MediaMetadata.METADATA_KEY_ART_URI);
            if (uriStr != null) {
                try {
                    artUri = Uri.parse(uriStr);
                } catch (Exception ignored) { }
            }

            MediaDescription desc = md.getDescription();
            if (desc != null) {
                if (title == null && desc.getTitle() != null) title = desc.getTitle().toString();
                if (artist == null && desc.getSubtitle() != null) artist = desc.getSubtitle().toString();
                if (art == null && desc.getIconBitmap() != null) art = desc.getIconBitmap();
                if (artUri == null && desc.getIconUri() != null) artUri = desc.getIconUri();
            }
        }

        if (art == null && artUri != null) {
            art = loadBitmapUri(artUri);
        }

        Log.d(TAG, "extractMediaInfo: app=" + appName + " title=" + title + " artist=" + artist + " playing=" + isPlaying + " hasArt=" + (art != null));
        return new MediaInfo(title, artist, appName, pkg, art, isPlaying);
    }

    private Bitmap loadBitmapUri(Uri uri) {
        if (uri == null) return null;
        try {
            String scheme = uri.getScheme();
            if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
                HttpURLConnection conn = (HttpURLConnection) new URL(uri.toString()).openConnection();
                conn.setConnectTimeout(4000);
                conn.setReadTimeout(4000);
                conn.setDoInput(true);
                conn.connect();
                try (InputStream is = conn.getInputStream()) {
                    return BitmapFactory.decodeStream(is);
                }
            } else if ("content".equalsIgnoreCase(scheme) || "android.resource".equalsIgnoreCase(scheme) || "file".equalsIgnoreCase(scheme)) {
                try (InputStream is = getContentResolver().openInputStream(uri)) {
                    return BitmapFactory.decodeStream(is);
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "Failed to load art URI: " + uri, e);
        }
        return null;
    }

    private MediaInfo extractFromNotifications() {
        try {
            StatusBarNotification[] sbns = getActiveNotifications();
            if (sbns == null) return null;
            for (StatusBarNotification sbn : sbns) {
                Notification n = sbn.getNotification();
                if (n == null || n.extras == null) continue;
                String pkg = sbn.getPackageName();
                boolean isMedia = Notification.CATEGORY_TRANSPORT.equals(n.category)
                        || n.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)
                        || pkg.contains("spotify") || pkg.contains("music") || pkg.contains("audio") || pkg.contains("youtube");
                if (!isMedia) continue;

                CharSequence titleCs = n.extras.getCharSequence(Notification.EXTRA_TITLE);
                CharSequence textCs = n.extras.getCharSequence(Notification.EXTRA_TEXT);
                if (titleCs == null && textCs == null) continue;

                String title = titleCs != null ? titleCs.toString() : null;
                String artist = textCs != null ? textCs.toString() : null;

                Bitmap art = null;
                if (n.extras.containsKey(Notification.EXTRA_LARGE_ICON)) {
                    Object iconObj = n.extras.get(Notification.EXTRA_LARGE_ICON);
                    if (iconObj instanceof Bitmap) {
                        art = (Bitmap) iconObj;
                    }
                }

                String appName = pkg;
                try {
                    appName = getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(pkg, 0)).toString();
                } catch (Exception ignored) { }

                Log.d(TAG, "extractFromNotifications: " + appName + " title=" + title + " artist=" + artist);
                return new MediaInfo(title, artist, appName, pkg, art, true);
            }
        } catch (Exception e) {
            Log.w(TAG, "extractFromNotifications error", e);
        }
        return null;
    }

    private void dispatchMedia(MediaInfo info) {
        latestMediaInfo = info;
        mainHandler.post(() -> {
            if (callback != null) {
                callback.onMediaStateChanged(info);
            }
        });
    }

    public static MediaNotificationListener getInstance() {
        return instance;
    }
}
