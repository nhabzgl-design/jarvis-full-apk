package com.jarvis.ai;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaMetadata;
import android.media.MediaRecorder;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.AlarmClock;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Base64;
import android.view.KeyEvent;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;

public class JarvisTools {
    private final Context ctx;

    JarvisTools(Context c) {
        ctx = c;
    }


    private boolean startActivitySafe(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { ctx.startActivity(i); return true; } catch (Exception e) { return false; }
    }

    private boolean startUrl(String url) {
        if (url == null || url.length() == 0) return false;
        return startActivitySafe(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
    }

    private String launchByName(String q) {
        PackageManager pm = ctx.getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN);
        main.addCategory(Intent.CATEGORY_LAUNCHER);
        ResolveInfo best = null;
        int bestScore = 0;
        for (ResolveInfo ri : pm.queryIntentActivities(main, 0)) {
            String label = String.valueOf(ri.loadLabel(pm)).toLowerCase(Locale.ROOT);
            String pkg = ri.activityInfo.packageName.toLowerCase(Locale.ROOT);
            int score = 0;
            if (label.equals(q) || pkg.equals(q)) score = 100;
            else if (label.startsWith(q)) score = 80;
            else if (label.contains(q)) score = 60;
            else if (pkg.contains(q)) score = 40;
            if (score > bestScore) { bestScore = score; best = ri; }
        }
        if (best == null) return null;
        Intent li = pm.getLaunchIntentForPackage(best.activityInfo.packageName);
        if (li == null) return null;
        if (!startActivitySafe(li)) return null;
        return String.valueOf(best.loadLabel(pm));
    }

    private boolean openMusic() {
        try {
            Intent i = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC);
            if (startActivitySafe(i)) return true;
        } catch (Exception ignored) { }
        PackageManager pm = ctx.getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN);
        main.addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(main, 0)) {
            String label = String.valueOf(ri.loadLabel(pm)).toLowerCase(Locale.ROOT);
            if (label.contains("music") || label.contains("musik") || label.contains("player") || label.contains("mp3")) {
                Intent li = pm.getLaunchIntentForPackage(ri.activityInfo.packageName);
                if (li != null && startActivitySafe(li)) return true;
            }
        }
        return false;
    }

    private String toolOpenApp(String app, String query) {
        String key = app.toLowerCase(Locale.ROOT).trim();
        if (key.contains("music") || key.contains("musika") || key.contains("musik") || key.contains("player") || key.contains("mp3") || key.contains("kanta")) {
            return openMusic() ? "opened the phone music player" : "no music player found";
        }
        if (query != null && query.length() > 0) {
            String q = Uri.encode(query);
            if (key.contains("youtube")) {
                if (startUrl("vnd.youtube://results?search_query=" + q) || startUrl("https://www.youtube.com/results?search_query=" + q)) return "opened youtube search: " + query;
            } else if (key.contains("facebook")) {
                if (startUrl("https://www.facebook.com/search/top/?q=" + q)) return "opened facebook search: " + query;
            } else if (key.contains("google")) {
                if (startUrl("https://www.google.com/search?q=" + q)) return "opened google search: " + query;
            } else if (key.contains("maps")) {
                if (startUrl("geo:0,0?q=" + q)) return "opened maps search: " + query;
            }
        }
        String label = launchByName(key);
        if (label != null) return "opened " + label;
        return "app not found or could not be opened: " + app;
    }

    private MediaController activeController() {
        try {
            MediaSessionManager msm = (MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
            List<MediaController> cs = msm.getActiveSessions(new ComponentName(ctx, JarvisNotifService.class));
            if (cs != null && !cs.isEmpty()) return cs.get(0);
        } catch (Exception ignored) { }
        return null;
    }

    private void keyEvent(int code) {
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        long t = SystemClock.uptimeMillis();
        am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0));
        am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0));
    }

    private String toolMusic(String action) {
        String a = action.toLowerCase(Locale.ROOT).trim();
        if ("open".equals(a)) return openMusic() ? "opened the phone music player" : "no music player found";
        MediaController mc = activeController();
        if ("now_playing".equals(a)) {
            if (mc == null) return "nothing is playing, or Notification Access is off";
            MediaMetadata md = mc.getMetadata();
            PlaybackState ps = mc.getPlaybackState();
            String title = md == null ? "" : String.valueOf(md.getString(MediaMetadata.METADATA_KEY_TITLE));
            String artist = md == null ? "" : String.valueOf(md.getString(MediaMetadata.METADATA_KEY_ARTIST));
            boolean playing = ps != null && ps.getState() == PlaybackState.STATE_PLAYING;
            return (playing ? "playing: " : "paused: ") + title + (artist.length() > 0 && !"null".equals(artist) ? " by " + artist : "");
        }
        int code;
        if ("play".equals(a)) code = KeyEvent.KEYCODE_MEDIA_PLAY;
        else if ("pause".equals(a)) code = KeyEvent.KEYCODE_MEDIA_PAUSE;
        else if ("toggle".equals(a)) code = KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
        else if ("next".equals(a)) code = KeyEvent.KEYCODE_MEDIA_NEXT;
        else if ("previous".equals(a)) code = KeyEvent.KEYCODE_MEDIA_PREVIOUS;
        else if ("stop".equals(a)) code = KeyEvent.KEYCODE_MEDIA_STOP;
        else return "unknown music action";
        if (mc != null) {
            MediaController.TransportControls tc = mc.getTransportControls();
            if ("play".equals(a)) tc.play();
            else if ("pause".equals(a)) tc.pause();
            else if ("next".equals(a)) tc.skipToNext();
            else if ("previous".equals(a)) tc.skipToPrevious();
            else if ("stop".equals(a)) tc.stop();
            else {
                PlaybackState ps = mc.getPlaybackState();
                if (ps != null && ps.getState() == PlaybackState.STATE_PLAYING) tc.pause(); else tc.play();
            }
            return "sent " + a;
        }
        if ("play".equals(a)) {
            openMusic();
            SystemClock.sleep(2500);
        }
        keyEvent(code);
        return "sent " + a + " (media key)";
    }

    private String toolFlashlight(boolean on) {
        try {
            CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
            String id = null;
            for (String cid : cm.getCameraIdList()) {
                CameraCharacteristics c = cm.getCameraCharacteristics(cid);
                Boolean flash = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                if (Boolean.TRUE.equals(flash) && facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) { id = cid; break; }
            }
            if (id == null) return "no flashlight found";
            cm.setTorchMode(id, on);
            return "flashlight " + (on ? "ON" : "OFF");
        } catch (Exception e) {
            return "flashlight failed: " + e.getMessage();
        }
    }

    private String toolPhone(JSONObject in) {
        String kind = in.optString("kind", "").toLowerCase(Locale.ROOT);
        String target = in.optString("target", "");
        String body = in.optString("body", "");
        Intent i;
        if ("whatsapp".equals(kind)) {
            i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/" + target.replaceAll("[^0-9]", "") + "?text=" + Uri.encode(body)));
        } else if ("mailto".equals(kind)) {
            i = new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + target));
            i.putExtra(Intent.EXTRA_TEXT, body);
        } else if ("sms".equals(kind)) {
            i = new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + target));
            i.putExtra("sms_body", body);
        } else {
            i = new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + target));
        }
        return startActivitySafe(i) ? "prepared " + kind + " (the user must confirm on screen)" : "failed";
    }

    String run(String name, JSONObject in) {
        try {
            if ("open_app".equals(name)) return toolOpenApp(in.optString("app", ""), in.optString("query", ""));
            if ("open_url".equals(name)) return startUrl(in.optString("url", "")) ? "opened" : "failed";
            if ("phone_action".equals(name)) return toolPhone(in);
            if ("music_player".equals(name)) return toolMusic(in.optString("action", ""));
            if ("flashlight".equals(name)) return toolFlashlight(in.optBoolean("on", true));
            if ("current_time".equals(name)) return new java.util.Date().toString();
            if ("get_device_info".equals(name)) return Build.MANUFACTURER + " " + Build.MODEL + ", Android " + Build.VERSION.RELEASE;
            if ("set_timer".equals(name)) {
                Intent i = new Intent(AlarmClock.ACTION_SET_TIMER);
                i.putExtra(AlarmClock.EXTRA_LENGTH, (int) in.optDouble("seconds", 60));
                i.putExtra(AlarmClock.EXTRA_MESSAGE, in.optString("label", "Jarvis"));
                i.putExtra(AlarmClock.EXTRA_SKIP_UI, true);
                return startActivitySafe(i) ? "timer set" : "timer failed";
            }
            if ("set_alarm".equals(name)) {
                Intent i = new Intent(AlarmClock.ACTION_SET_ALARM);
                i.putExtra(AlarmClock.EXTRA_HOUR, (int) in.optDouble("hour", 7));
                i.putExtra(AlarmClock.EXTRA_MINUTES, (int) in.optDouble("minute", 0));
                i.putExtra(AlarmClock.EXTRA_MESSAGE, in.optString("label", "Jarvis"));
                i.putExtra(AlarmClock.EXTRA_SKIP_UI, true);
                return startActivitySafe(i) ? "alarm set" : "alarm failed";
            }
            if ("volume".equals(name)) {
                AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
                int d = "down".equals(in.optString("dir", "up")) ? AudioManager.ADJUST_LOWER : AudioManager.ADJUST_RAISE;
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, d, 0);
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, d, AudioManager.FLAG_SHOW_UI);
                return "volume " + in.optString("dir", "up");
            }
            if ("get_battery".equals(name)) {
                Intent b = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                if (b == null) return "battery unavailable";
                int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                int status = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL;
                return "battery " + (level * 100 / Math.max(1, scale)) + "%, " + (charging ? "charging" : "not charging");
            }
            if ("remember".equals(name)) {
                SharedPreferences p = ctx.getSharedPreferences("jarvis", 0);
                JSONArray arr = new JSONArray(p.getString("notes", "[]"));
                String note = in.optString("note", "");
                if (note.length() > 0) {
                    arr.put(note);
                    p.edit().putString("notes", arr.toString()).apply();
                    return "saved";
                }
                return arr.toString();
            }
            if ("read_notifications".equals(name)) {
                StringBuilder sb = new StringBuilder();
                synchronized (JarvisNotifService.recent) {
                    int n = Math.min(5, JarvisNotifService.recent.size());
                    for (int k = 0; k < n; k++) sb.append(JarvisNotifService.recent.get(k)).append("\n");
                }
                return sb.length() == 0 ? "no recent Messenger or Facebook notifications" : sb.toString();
            }
            if ("set_auto_read".equals(name)) {
                boolean e = in.optBoolean("enabled", true);
                ctx.getSharedPreferences("jarvis", 0).edit().putBoolean("autoread", e).apply();
                return "auto read " + (e ? "ON" : "OFF");
            }
            return "that tool is not available in background mode";
        } catch (Exception e) {
            return "error: " + e.getMessage();
        }
    }
}
