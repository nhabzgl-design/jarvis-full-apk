package com.jarvis.ai;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import android.content.SharedPreferences;
import android.provider.AlarmClock;
import android.provider.Settings;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

@CapacitorPlugin(name = "JarvisNative")
public class JarvisNativePlugin extends Plugin {

    @Override
    public void load() {
        JarvisNotifService.plugin = this;
    }

    void emitNotif(String app, String title, String text) {
        JSObject o = new JSObject();
        o.put("app", app);
        o.put("title", title);
        o.put("text", text);
        notifyListeners("notif", o);
    }

    private SharedPreferences prefs() {
        return getContext().getSharedPreferences("jarvis", 0);
    }

    @PluginMethod
    public void setAlarm(PluginCall call) {
        Integer hour = call.getInt("hour");
        Integer minute = call.getInt("minute");
        if (hour == null || minute == null) { call.reject("hour and minute required"); return; }
        Intent i = new Intent(AlarmClock.ACTION_SET_ALARM);
        i.putExtra(AlarmClock.EXTRA_HOUR, hour.intValue());
        i.putExtra(AlarmClock.EXTRA_MINUTES, minute.intValue());
        i.putExtra(AlarmClock.EXTRA_MESSAGE, call.getString("label", "Jarvis"));
        i.putExtra(AlarmClock.EXTRA_SKIP_UI, true);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { getContext().startActivity(i); call.resolve(); } catch (Exception e) { call.reject(String.valueOf(e.getMessage())); }
    }

    @PluginMethod
    public void setTimer(PluginCall call) {
        Integer secs = call.getInt("seconds");
        if (secs == null) { call.reject("seconds required"); return; }
        Intent i = new Intent(AlarmClock.ACTION_SET_TIMER);
        i.putExtra(AlarmClock.EXTRA_LENGTH, secs.intValue());
        i.putExtra(AlarmClock.EXTRA_MESSAGE, call.getString("label", "Jarvis"));
        i.putExtra(AlarmClock.EXTRA_SKIP_UI, true);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { getContext().startActivity(i); call.resolve(); } catch (Exception e) { call.reject(String.valueOf(e.getMessage())); }
    }

    @PluginMethod
    public void openApp(PluginCall call) {
        String q = call.getString("name", "");
        q = q == null ? "" : q.toLowerCase(Locale.ROOT).trim();
        if (q.isEmpty()) { call.reject("name required"); return; }
        PackageManager pm = getContext().getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN);
        main.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> apps = pm.queryIntentActivities(main, 0);
        ResolveInfo best = null;
        int bestScore = 0;
        for (ResolveInfo ri : apps) {
            String label = String.valueOf(ri.loadLabel(pm)).toLowerCase(Locale.ROOT);
            String pkg = ri.activityInfo.packageName.toLowerCase(Locale.ROOT);
            int score = 0;
            if (label.equals(q) || pkg.equals(q)) score = 100;
            else if (label.startsWith(q)) score = 80;
            else if (label.contains(q)) score = 60;
            else if (pkg.contains(q)) score = 40;
            if (score > bestScore) { bestScore = score; best = ri; }
        }
        if (best == null) { call.reject("App not found: " + q); return; }
        String pkgName = best.activityInfo.packageName;
        Intent li = pm.getLaunchIntentForPackage(pkgName);
        if (li == null) { call.reject("Cannot launch " + pkgName); return; }
        li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            getContext().startActivity(li);
            JSObject o = new JSObject();
            o.put("label", String.valueOf(best.loadLabel(pm)));
            o.put("package", pkgName);
            call.resolve(o);
        } catch (Exception e) { call.reject(String.valueOf(e.getMessage())); }
    }

    @PluginMethod
    public void flashlight(PluginCall call) {
        boolean on = call.getBoolean("on", true);
        try {
            CameraManager cm = (CameraManager) getContext().getSystemService(Context.CAMERA_SERVICE);
            String id = null;
            for (String cid : cm.getCameraIdList()) {
                CameraCharacteristics c = cm.getCameraCharacteristics(cid);
                Boolean flash = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                if (Boolean.TRUE.equals(flash) && facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) { id = cid; break; }
            }
            if (id == null) { call.reject("No flashlight found"); return; }
            cm.setTorchMode(id, on);
            call.resolve();
        } catch (Exception e) { call.reject(String.valueOf(e.getMessage())); }
    }

    private MediaController activeController() {
        try {
            MediaSessionManager msm = (MediaSessionManager) getContext().getSystemService(Context.MEDIA_SESSION_SERVICE);
            List<MediaController> cs = msm.getActiveSessions(new ComponentName(getContext(), JarvisNotifService.class));
            if (cs != null && !cs.isEmpty()) return cs.get(0);
        } catch (Exception ignored) { }
        return null;
    }

    private String nz(String v) { return v == null ? "" : v; }

    @PluginMethod
    public void openMusic(PluginCall call) {
        try {
            Intent i = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(i);
            call.resolve();
            return;
        } catch (ActivityNotFoundException e) {
            // fall through to name search
        } catch (Exception e) {
            call.reject(String.valueOf(e.getMessage()));
            return;
        }
        PackageManager pm = getContext().getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN);
        main.addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(main, 0)) {
            String label = String.valueOf(ri.loadLabel(pm)).toLowerCase(Locale.ROOT);
            if (label.contains("music") || label.contains("musik") || label.contains("player") || label.contains("mp3")) {
                Intent li = pm.getLaunchIntentForPackage(ri.activityInfo.packageName);
                if (li != null) {
                    li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try { getContext().startActivity(li); call.resolve(); return; } catch (Exception ignored) { }
                }
            }
        }
        call.reject("Walang music player na nahanap");
    }

    @PluginMethod
    public void media(PluginCall call) {
        String cmd = call.getString("cmd", "");
        int code;
        if ("play".equals(cmd)) code = KeyEvent.KEYCODE_MEDIA_PLAY;
        else if ("pause".equals(cmd)) code = KeyEvent.KEYCODE_MEDIA_PAUSE;
        else if ("toggle".equals(cmd)) code = KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
        else if ("next".equals(cmd)) code = KeyEvent.KEYCODE_MEDIA_NEXT;
        else if ("previous".equals(cmd)) code = KeyEvent.KEYCODE_MEDIA_PREVIOUS;
        else if ("stop".equals(cmd)) code = KeyEvent.KEYCODE_MEDIA_STOP;
        else { call.reject("unknown cmd"); return; }
        MediaController mc = activeController();
        JSObject o = new JSObject();
        o.put("hasSession", mc != null);
        try {
            if (mc != null) {
                MediaController.TransportControls tc = mc.getTransportControls();
                if ("play".equals(cmd)) tc.play();
                else if ("pause".equals(cmd)) tc.pause();
                else if ("next".equals(cmd)) tc.skipToNext();
                else if ("previous".equals(cmd)) tc.skipToPrevious();
                else if ("stop".equals(cmd)) tc.stop();
                else {
                    PlaybackState ps = mc.getPlaybackState();
                    if (ps != null && ps.getState() == PlaybackState.STATE_PLAYING) tc.pause(); else tc.play();
                }
                o.put("source", "session");
            } else {
                AudioManager am = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
                long t = SystemClock.uptimeMillis();
                am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0));
                am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0));
                o.put("source", "keyevent");
            }
            call.resolve(o);
        } catch (Exception e) { call.reject(String.valueOf(e.getMessage())); }
    }

    @PluginMethod
    public void nowPlaying(PluginCall call) {
        MediaController mc = activeController();
        if (mc == null) { call.reject("Walang aktibong music, o kailangan i-on ang Notification Access"); return; }
        JSObject o = new JSObject();
        MediaMetadata md = mc.getMetadata();
        if (md != null) {
            o.put("title", nz(md.getString(MediaMetadata.METADATA_KEY_TITLE)));
            o.put("artist", nz(md.getString(MediaMetadata.METADATA_KEY_ARTIST)));
        }
        PlaybackState ps = mc.getPlaybackState();
        o.put("playing", ps != null && ps.getState() == PlaybackState.STATE_PLAYING);
        o.put("app", nz(mc.getPackageName()));
        call.resolve(o);
    }

    @PluginMethod
    public void volume(PluginCall call) {
        String dir = call.getString("dir", "up");
        try {
            AudioManager am = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
            int d = "down".equals(dir) ? AudioManager.ADJUST_LOWER : AudioManager.ADJUST_RAISE;
            for (int k = 0; k < 2; k++) am.adjustStreamVolume(AudioManager.STREAM_MUSIC, d, k == 1 ? AudioManager.FLAG_SHOW_UI : 0);
            call.resolve();
        } catch (Exception e) { call.reject(String.valueOf(e.getMessage())); }
    }

    private TextToSpeech tts;
    private boolean ttsReady = false;
    private boolean ttsIniting = false;
    private final List<Runnable> ttsQueue = new ArrayList<Runnable>();
    private final Map<String, PluginCall> speakCalls = new HashMap<String, PluginCall>();

    private void finishSpeech(String id, String err) {
        PluginCall c;
        synchronized (speakCalls) { c = speakCalls.remove(id); }
        if (c == null) return;
        if (err == null) c.resolve(); else c.reject(err);
    }

    private void endAllSpeech(String err) {
        List<PluginCall> all;
        synchronized (speakCalls) {
            all = new ArrayList<PluginCall>(speakCalls.values());
            speakCalls.clear();
        }
        for (PluginCall c : all) { if (err == null) c.resolve(); else c.reject(err); }
    }

    private void withTts(final Runnable r) {
        if (ttsReady && tts != null) { r.run(); return; }
        ttsQueue.add(r);
        if (ttsIniting) return;
        ttsIniting = true;
        tts = new TextToSpeech(getContext(), new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(int status) {
                ttsIniting = false;
                if (status == TextToSpeech.SUCCESS) {
                    ttsReady = true;
                    tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                        @Override public void onStart(String id) { }
                        @Override public void onDone(String id) { finishSpeech(id, null); }
                        @Override public void onError(String id) { finishSpeech(id, "TTS error"); }
                    });
                    List<Runnable> copy = new ArrayList<Runnable>(ttsQueue);
                    ttsQueue.clear();
                    for (Runnable q : copy) q.run();
                } else {
                    ttsQueue.clear();
                    tts = null;
                    endAllSpeech("Walang text-to-speech engine sa phone (status " + status + ")");
                }
            }
        });
    }

    @PluginMethod
    public void speak(final PluginCall call) {
        final String text = call.getString("text", "");
        final String lang = call.getString("lang", "en-US");
        if (text == null || text.trim().isEmpty()) { call.resolve(); return; }
        final String id = "jv" + SystemClock.uptimeMillis();
        synchronized (speakCalls) { speakCalls.put(id, call); }
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                withTts(new Runnable() {
                    @Override
                    public void run() {
                        Locale loc = (lang.startsWith("fil") || lang.startsWith("tl")) ? new Locale("fil", "PH") : Locale.forLanguageTag(lang);
                        int r = tts.setLanguage(loc);
                        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) tts.setLanguage(Locale.US);
                        tts.setPitch(0.9f);
                        tts.setSpeechRate(1.0f);
                        int res = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id);
                        if (res != TextToSpeech.SUCCESS) finishSpeech(id, "tts.speak failed");
                    }
                });
            }
        });
    }

    @PluginMethod
    public void stopSpeak(PluginCall call) {
        try { if (tts != null) tts.stop(); } catch (Exception ignored) { }
        endAllSpeech(null);
        call.resolve();
    }

    @PluginMethod
    public void notifAccess(PluginCall call) {
        String flat = Settings.Secure.getString(getContext().getContentResolver(), "enabled_notification_listeners");
        boolean on = flat != null && flat.contains(getContext().getPackageName());
        JSObject o = new JSObject();
        o.put("enabled", on);
        o.put("autoRead", prefs().getBoolean("autoread", true));
        call.resolve(o);
    }

    @PluginMethod
    public void openNotifSettings(PluginCall call) {
        Intent i = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { getContext().startActivity(i); call.resolve(); } catch (Exception e) { call.reject(String.valueOf(e.getMessage())); }
    }

    @PluginMethod
    public void setAutoRead(PluginCall call) {
        boolean e = call.getBoolean("enabled", true);
        prefs().edit().putBoolean("autoread", e).apply();
        call.resolve();
    }

    @PluginMethod
    public void getNotifs(PluginCall call) {
        JSArray arr = new JSArray();
        synchronized (JarvisNotifService.recent) {
            for (String s : JarvisNotifService.recent) arr.put(s);
        }
        JSObject o = new JSObject();
        o.put("items", arr);
        call.resolve(o);
    }

    @PluginMethod
    public void startAlwaysOn(PluginCall call) {
        SharedPreferences.Editor e = prefs().edit();
        e.putString("base", call.getString("base", ""));
        e.putString("pass", call.getString("pass", "1234"));
        e.putString("system", call.getString("system", ""));
        e.putString("wake", call.getString("wake", "tala,talla,tahla,taller"));
        e.putString("tools", call.getString("tools", "[]"));
        e.apply();
        if (Build.VERSION.SDK_INT >= 33) {
            try { getActivity().requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 4711); } catch (Exception ignored) { }
        }
        Intent i = new Intent(getContext(), JarvisListenService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) getContext().startForegroundService(i); else getContext().startService(i);
            call.resolve();
        } catch (Exception ex) { call.reject(String.valueOf(ex.getMessage())); }
    }

    @PluginMethod
    public void stopAlwaysOn(PluginCall call) {
        try {
            prefs().edit().putBoolean("aon", false).apply();
            getContext().stopService(new Intent(getContext(), JarvisListenService.class));
            call.resolve();
        } catch (Exception ex) { call.reject(String.valueOf(ex.getMessage())); }
    }

    @PluginMethod
    public void alwaysOnStatus(PluginCall call) {
        JSObject o = new JSObject();
        o.put("running", JarvisListenService.running);
        boolean overlay = true;
        if (Build.VERSION.SDK_INT >= 23) overlay = Settings.canDrawOverlays(getContext());
        o.put("overlay", overlay);
        call.resolve(o);
    }

    @PluginMethod
    public void openOverlaySettings(PluginCall call) {
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getContext().getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(i);
            call.resolve();
        } catch (Exception ex) { call.reject(String.valueOf(ex.getMessage())); }
    }

    @PluginMethod
    public void openBatterySettings(PluginCall call) {
        try {
            Intent i = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(i);
            call.resolve();
        } catch (Exception ex) { call.reject(String.valueOf(ex.getMessage())); }
    }
}
