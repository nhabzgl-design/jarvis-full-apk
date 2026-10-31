package com.jarvis.ai;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.speech.tts.TextToSpeech;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

public class JarvisNotifService extends NotificationListenerService implements TextToSpeech.OnInitListener {

    static final List<String> recent = Collections.synchronizedList(new ArrayList<String>());
    static JarvisNativePlugin plugin;

    private static final Pattern TL = Pattern.compile(
        "\\b(ang|ng|mga|po|opo|ako|ikaw|hindi|naman|sa|ko|mo|natin|salamat|boss|gusto|kasi|pero|yung|ano|oo|sige|ayos|tapos|na|ba|lang|nga|kita|tayo|bukas|mamaya|ngayon|kumain|wala|meron|kayo|siya|namin|nyo|niyo|po)\\b",
        Pattern.CASE_INSENSITIVE);

    private TextToSpeech tts;
    private boolean ttsReady = false;
    private final Map<String, Long> seen = new HashMap<String, Long>();

    @Override
    public void onCreate() {
        super.onCreate();
        tts = new TextToSpeech(getApplicationContext(), this);
    }

    @Override
    public void onInit(int status) {
        ttsReady = (status == TextToSpeech.SUCCESS);
    }

    @Override
    public void onDestroy() {
        if (tts != null) { tts.stop(); tts.shutdown(); }
        super.onDestroy();
    }

    private String clean(CharSequence cs) {
        if (cs == null) return "";
        String s = cs.toString().replaceAll("[\\p{So}\\p{Cs}]", "").replaceAll("\\s+", " ").trim();
        if (s.length() > 300) s = s.substring(0, 300);
        return s;
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        try {
            String pkg = sbn.getPackageName();
            String app = null;
            if ("com.facebook.orca".equals(pkg)) app = "Messenger";
            else if ("com.facebook.mlite".equals(pkg)) app = "Messenger";
            else if ("com.facebook.katana".equals(pkg)) app = "Facebook";
            else if ("com.facebook.lite".equals(pkg)) app = "Facebook";
            if (app == null) return;

            Notification n = sbn.getNotification();
            if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;
            if (sbn.isOngoing()) return;

            Bundle e = n.extras;
            String title = clean(e.getCharSequence(Notification.EXTRA_TITLE));
            String text = clean(e.getCharSequence(Notification.EXTRA_TEXT));
            if (text.isEmpty()) return;
            if (text.toLowerCase(Locale.ROOT).contains("chat heads active")) return;

            String key = pkg + "|" + title + "|" + text;
            long now = System.currentTimeMillis();
            synchronized (seen) {
                Long last = seen.get(key);
                if (last != null && now - last < 60000) return;
                seen.put(key, now);
                if (seen.size() > 200) seen.clear();
            }

            recent.add(0, app + " | " + (title.isEmpty() ? "?" : title) + ": " + text);
            while (recent.size() > 20) recent.remove(recent.size() - 1);

            if (plugin != null) {
                try { plugin.emitNotif(app, title, text); } catch (Exception ignored) { }
            }

            boolean auto = getSharedPreferences("jarvis", 0).getBoolean("autoread", true);
            if (!auto || !ttsReady) return;

            boolean tl = TL.matcher(text).find();
            String line;
            if (app.equals("Messenger")) {
                String who = title.isEmpty() ? "" : title;
                if (tl) line = "Boss, may mensahe sa Messenger" + (who.isEmpty() ? "" : " galing kay " + who) + ". " + text;
                else line = "Boss, new Messenger message" + (who.isEmpty() ? "" : " from " + who) + ". " + text;
            } else {
                if (tl) line = "Boss, may notification sa Facebook. " + text;
                else line = "Boss, new Facebook notification. " + text;
            }

            int r = tts.setLanguage(tl ? new Locale("fil", "PH") : Locale.US);
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts.setLanguage(Locale.US);
            }
            tts.setPitch(0.9f);
            tts.speak(line, TextToSpeech.QUEUE_ADD, null, "jv" + now);
        } catch (Exception ignored) { }
    }
}
