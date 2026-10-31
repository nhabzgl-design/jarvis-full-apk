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

public class JarvisListenService extends Service implements TextToSpeech.OnInitListener {

    static final String ACTION_STOP = "com.jarvis.ai.STOP";
    static final String CHANNEL = "jarvis_listen";
    static final int NOTIF_ID = 77;
    static volatile boolean running = false;

    private static final Pattern TL = Pattern.compile(
        "\\b(ang|ng|mga|po|opo|ako|ikaw|hindi|naman|sa|ko|mo|natin|salamat|boss|gusto|kasi|pero|yung|ano|oo|sige|ayos|tapos|na|ba|lang|nga|kita|tayo|bukas|mamaya|ngayon|wala|meron|kayo|siya|namin|nyo|niyo)\\b",
        Pattern.CASE_INSENSITIVE);

    private volatile boolean stopFlag = false;
    private Thread worker;
    private PowerManager.WakeLock wakeLock;
    private TextToSpeech tts;
    private volatile boolean ttsReady = false;
    private volatile CountDownLatch speakLatch;
    private JarvisTools toolRunner;
    private Model model;
    private Recognizer rec;

    private SharedPreferences prefs() {
        return getSharedPreferences("jarvis", 0);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Tala listening", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(ch);
        }
        toolRunner = new JarvisTools(this);
        tts = new TextToSpeech(getApplicationContext(), this);
    }

    @Override
    public void onInit(int status) {
        if (status != TextToSpeech.SUCCESS) return;
        try {
            tts.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
        } catch (Exception ignored) { }
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) { }
            @Override public void onDone(String id) { CountDownLatch l = speakLatch; if (l != null) l.countDown(); }
            @Override public void onError(String id) { CountDownLatch l = speakLatch; if (l != null) l.countDown(); }
        });
        ttsReady = true;
    }

    private Notification buildNotification(String text) {
        Intent open = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (open == null) open = new Intent();
        PendingIntent openPi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent stop = new Intent(this, JarvisListenService.class);
        stop.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) b = new Notification.Builder(this, CHANNEL);
        else b = new Notification.Builder(this);
        b.setContentTitle("T.A.L.A");
        b.setContentText(text);
        b.setSmallIcon(android.R.drawable.ic_btn_speak_now);
        b.setOngoing(true);
        b.setContentIntent(openPi);
        b.addAction(android.R.drawable.ic_menu_close_clear_cancel, "I-OFF", stopPi);
        return b.build();
    }

    private void setStatus(String text) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIF_ID, buildNotification(text));
        } catch (Exception ignored) { }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            prefs().edit().putBoolean("aon", false).apply();
            stopWorker();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        Notification n = buildNotification("Tala nakikinig. Sabihin: Tala");
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            else startForeground(NOTIF_ID, n);
        } catch (Exception e) {
            stopSelf();
            return START_NOT_STICKY;
        }
        prefs().edit().putBoolean("aon", true).apply();
        if (!running) {
            running = true;
            stopFlag = false;
            try {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tala:listen");
                wakeLock.acquire();
            } catch (Exception ignored) { }
            worker = new Thread(new Runnable() {
                @Override public void run() { runLoop(); }
            }, "jarvis-listen");
            worker.start();
        }
        return START_STICKY;
    }

    private void stopWorker() {
        stopFlag = true;
        try { if (worker != null) worker.join(1500); } catch (Exception ignored) { }
    }

    @Override
    public void onDestroy() {
        stopWorker();
        running = false;
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Exception ignored) { }
        try { if (tts != null) { tts.stop(); tts.shutdown(); } } catch (Exception ignored) { }
        super.onDestroy();
    }

    // ---------------------------------------------------------------- model

    private void copyAssets(String path, File dest) throws Exception {
        String[] list = getAssets().list(path);
        if (list == null || list.length == 0) {
            try {
                InputStream in = getAssets().open(path);
                dest.getParentFile().mkdirs();
                OutputStream out = new FileOutputStream(dest);
                byte[] buf = new byte[65536];
                int r;
                while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
                out.close();
                in.close();
            } catch (java.io.IOException e) {
                dest.mkdirs();
            }
            return;
        }
        dest.mkdirs();
        for (String name : list) copyAssets(path + "/" + name, new File(dest, name));
    }

    private void deleteRec(File f) {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRec(k);
        }
        f.delete();
    }

    private String[] wakeWords() {
        String w = prefs().getString("wake", "tala,talla,tahla,taller");
        String[] parts = w.toLowerCase(Locale.ROOT).split(",");
        List<String> out = new ArrayList<String>();
        for (String p : parts) { String t = p.trim(); if (t.length() > 0) out.add(t); }
        if (out.isEmpty()) out.add("tala");
        return out.toArray(new String[0]);
    }

    private String grammarJson() {
        JSONArray a = new JSONArray();
        for (String w : wakeWords()) a.put(w);
        a.put("[unk]");
        return a.toString();
    }

    private boolean hasWake(String vosk) {
        if (vosk == null) return false;
        try {
            JSONObject o = new JSONObject(vosk);
            String t = o.optString("text", o.optString("partial", "")).toLowerCase(Locale.ROOT);
            if (t.length() == 0) return false;
            String[] toks = t.split("\\s+");
            String[] ww = wakeWords();
            for (String tok : toks) for (String w : ww) if (tok.equals(w)) return true;
        } catch (Exception ignored) { }
        return false;
    }

    private boolean prepareModel() {
        try {
            File dir = new File(getFilesDir(), "model-en");
            File ok = new File(dir, ".ok");
            if (!ok.exists()) {
                setStatus("Inihahanda ang voice model (isang beses lang)...");
                deleteRec(dir);
                copyAssets("model-en", dir);
                new FileOutputStream(ok).close();
            }
            model = new Model(dir.getAbsolutePath());
            rec = new Recognizer(model, 16000.0f, grammarJson());
            return true;
        } catch (Throwable t) {
            setStatus("Walang voice model: " + t.getMessage());
            return false;
        }
    }

    // ---------------------------------------------------------------- main loop

    private void runLoop() {
        AudioRecord ar = null;
        try {
            if (!prepareModel()) return;
            int minBuf = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            int bufSize = Math.max(minBuf, 32000);
            ar = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize);
            if (ar.getState() != AudioRecord.STATE_INITIALIZED) {
                setStatus("Hindi mabuksan ang mic");
                return;
            }
            ar.startRecording();
            for (int w = 0; w < 30 && !ttsReady; w++) SystemClock.sleep(100);
            speak("Online na ako, boss. Sabihin mo lang ang Tala.");
            flush(ar);
            setStatus("Nakikinig... sabihin: Tala");

            short[] chunk = new short[1600];
            ArrayDeque<short[]> ring = new ArrayDeque<short[]>();
            while (!stopFlag) {
                int n = ar.read(chunk, 0, chunk.length);
                if (n <= 0) { SystemClock.sleep(20); continue; }
                short[] copy = Arrays.copyOf(chunk, n);
                ring.addLast(copy);
                while (ring.size() > 16) ring.removeFirst();
                boolean end = rec.acceptWaveForm(copy, n);
                String txt = end ? rec.getResult() : rec.getPartialResult();
                if (hasWake(txt)) {
                    rec.reset();
                    handleCommand(ar, ring);
                    rec.reset();
                    ring.clear();
                    flush(ar);
                    setStatus("Nakikinig... sabihin: Tala");
                }
            }
        } catch (Throwable t) {
            setStatus("Error: " + t.getMessage());
        } finally {
            try { if (ar != null) { ar.stop(); ar.release(); } } catch (Exception ignored) { }
            try { if (rec != null) rec.close(); } catch (Exception ignored) { }
            try { if (model != null) model.close(); } catch (Exception ignored) { }
            running = false;
            try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Exception ignored) { }
        }
    }

    private void flush(AudioRecord ar) {
        try { ar.stop(); ar.startRecording(); } catch (Exception ignored) { }
    }

    private static void writeShorts(ByteArrayOutputStream o, short[] c, int n) {
        for (int i = 0; i < n; i++) {
            o.write(c[i] & 0xff);
            o.write((c[i] >> 8) & 0xff);
        }
    }

    private static double rms(short[] c, int n) {
        double sum = 0;
        for (int i = 0; i < n; i++) sum += (double) c[i] * c[i];
        return Math.sqrt(sum / Math.max(1, n));
    }

    private void vibrate(long ms) {
        try {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null) return;
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
            else v.vibrate(ms);
        } catch (Exception ignored) { }
    }

    private void handleCommand(AudioRecord ar, ArrayDeque<short[]> ring) {
        vibrate(70);
        setStatus("Nakikinig sa utos mo...");
        ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        for (short[] c : ring) writeShorts(pcm, c, c.length);

        short[] chunk = new short[1600];
        long start = SystemClock.uptimeMillis();
        boolean spoke = false;
        int quiet = 0;
        int loudRun = 0;
        double floor = 1e9;
        while (!stopFlag) {
            int n = ar.read(chunk, 0, chunk.length);
            if (n <= 0) { SystemClock.sleep(20); continue; }
            writeShorts(pcm, chunk, n);
            double r = rms(chunk, n);
            if (r < floor) floor = r;
            long el = SystemClock.uptimeMillis() - start;
            double thr = Math.max(700.0, floor * 3.0);
            if (el >= 400) {
                if (r > thr) {
                    loudRun++;
                    quiet = 0;
                    if (loudRun >= 2) spoke = true;
                } else {
                    loudRun = 0;
                    if (spoke) quiet++;
                }
            }
            if (spoke && quiet >= 10) break;
            if (!spoke && el > 4500) break;
            if (el > 10000) break;
        }
        if (!spoke || stopFlag) return;

        setStatus("Iniisip...");
        try {
            byte[] wav = wav(pcm.toByteArray());
            String b64 = Base64.encodeToString(wav, Base64.NO_WRAP);
            String reply = askServer(b64);
            if (reply != null && reply.trim().length() > 0 && !reply.contains("[NOSPEECH]")) {
                setStatus("Sumasagot...");
                speak(reply);
            }
        } catch (Exception e) {
            speak("Boss, may problema sa koneksyon. Subukan mo ulit.");
        }
    }

    // ---------------------------------------------------------------- audio + server

    private static void le32(ByteArrayOutputStream o, int v) {
        o.write(v & 0xff);
        o.write((v >> 8) & 0xff);
        o.write((v >> 16) & 0xff);
        o.write((v >> 24) & 0xff);
    }

    private static void le16(ByteArrayOutputStream o, int v) {
        o.write(v & 0xff);
        o.write((v >> 8) & 0xff);
    }

    private static void str(ByteArrayOutputStream o, String s) {
        byte[] b = s.getBytes();
        o.write(b, 0, b.length);
    }

    private byte[] wav(byte[] pcm) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        str(o, "RIFF");
        le32(o, 36 + pcm.length);
        str(o, "WAVE");
        str(o, "fmt ");
        le32(o, 16);
        le16(o, 1);
        le16(o, 1);
        le32(o, 16000);
        le32(o, 32000);
        le16(o, 2);
        le16(o, 16);
        str(o, "data");
        le32(o, pcm.length);
        o.write(pcm, 0, pcm.length);
        return o.toByteArray();
    }

    private String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int r;
        while ((r = is.read(buf)) > 0) o.write(buf, 0, r);
        return new String(o.toByteArray(), "UTF-8");
    }

    private JSONObject postChat(JSONObject payload) throws Exception {
        URL u = new URL(prefs().getString("base", "") + "/chat");
        HttpURLConnection c = (HttpURLConnection) u.openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(20000);
        c.setReadTimeout(80000);
        c.setRequestProperty("content-type", "application/json");
        c.setRequestProperty("x-pass", prefs().getString("pass", "1234"));
        c.setDoOutput(true);
        byte[] body = payload.toString().getBytes("UTF-8");
        c.setFixedLengthStreamingMode(body.length);
        OutputStream os = c.getOutputStream();
        os.write(body);
        os.close();
        int code = c.getResponseCode();
        String s = readAll(code >= 400 ? c.getErrorStream() : c.getInputStream());
        if (code >= 400) throw new Exception("HTTP " + code + " " + s);
        return new JSONObject(s);
    }

    private String askServer(String b64) throws Exception {
        JSONArray messages = new JSONArray();
        JSONObject um = new JSONObject();
        um.put("role", "user");
        JSONArray content = new JSONArray();
        JSONObject audio = new JSONObject();
        audio.put("type", "audio");
        audio.put("mime", "audio/wav");
        audio.put("data", b64);
        content.put(audio);
        JSONObject txt = new JSONObject();
        txt.put("type", "text");
        txt.put("text", "(Voice command recorded right after the wake word. Understand it and act.)");
        content.put(txt);
        um.put("content", content);
        messages.put(um);

        JSONArray tools = new JSONArray(prefs().getString("tools", "[]"));
        String system = prefs().getString("system", "");
        String finalText = "";
        for (int round = 0; round < 4; round++) {
            JSONObject payload = new JSONObject();
            payload.put("system", system);
            payload.put("tools", tools);
            payload.put("messages", messages);
            JSONObject resp = postChat(payload);
            JSONArray rc = resp.getJSONArray("content");
            JSONObject am = new JSONObject();
            am.put("role", "assistant");
            am.put("content", rc);
            messages.put(am);

            JSONArray results = new JSONArray();
            for (int i = 0; i < rc.length(); i++) {
                JSONObject b = rc.getJSONObject(i);
                String type = b.optString("type", "");
                if ("text".equals(type)) {
                    finalText = b.optString("text", finalText);
                } else if ("tool_use".equals(type)) {
                    JSONObject in = b.optJSONObject("input");
                    if (in == null) in = new JSONObject();
                    String res = toolRunner.run(b.optString("name", ""), in);
                    JSONObject tr = new JSONObject();
                    tr.put("type", "tool_result");
                    tr.put("tool_use_id", b.optString("id", ""));
                    tr.put("content", res);
                    results.put(tr);
                }
            }
            if (results.length() == 0) break;
            JSONObject rm = new JSONObject();
            rm.put("role", "user");
            rm.put("content", results);
            messages.put(rm);
        }
        return finalText.replaceAll("[*#_`]", "");
    }

    // ---------------------------------------------------------------- speech

    private void speak(String text) {
        if (!ttsReady || text == null || text.trim().length() == 0) return;
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        AudioFocusRequest req = null;
        try {
            if (Build.VERSION.SDK_INT >= 26 && am != null) {
                req = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .build();
                am.requestAudioFocus(req);
            }
            boolean tl = TL.matcher(text).find();
            int r = tts.setLanguage(tl ? new Locale("fil", "PH") : Locale.US);
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) tts.setLanguage(Locale.US);
            tts.setPitch(0.9f);
            speakLatch = new CountDownLatch(1);
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "aon" + SystemClock.uptimeMillis());
            speakLatch.await(30, TimeUnit.SECONDS);
        } catch (Exception ignored) {
        } finally {
            try { if (req != null && am != null) am.abandonAudioFocusRequest(req); } catch (Exception ignored) { }
        }
    }
}
