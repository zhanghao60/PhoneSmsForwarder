package com.app.smsAuto;

import android.app.Notification;
import android.content.Intent;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SmsNotificationListener extends NotificationListenerService {
    private static final String TAG = "SmsNotificationListener";

    private static final long CODE_DEDUP_WINDOW_MS = 30_000L;
    private static String lastDedupKey = null;
    private static long lastDedupAt = 0L;

    private static final Pattern SIX_DIGITS = Pattern.compile("(?<!\\d)(\\d{6})(?!\\d)");
    private static final Pattern OTP_DIGITS = Pattern.compile("(?<!\\d)(\\d{4,8})(?!\\d)");
    private static final String BROADCAST_ACTION = "com.app.smsAuto.CODE_RECEIVED";
    private static boolean isConnected = false;

    public static boolean isServiceConnected() {
        return isConnected;
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        isConnected = true;
        Log.d(TAG, "★★★ 通知监听服务已连接 ★★★");
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        isConnected = false;
        Log.d(TAG, "★★★ 通知监听服务已断开 ★★★");
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        super.onNotificationPosted(sbn);
        if (sbn == null) {
            return;
        }

        String packageName = sbn.getPackageName();
        Notification notification = sbn.getNotification();
        Bundle extras = notification.extras;
        String title = extras.getString(Notification.EXTRA_TITLE, "未知标题");

        String content = buildCandidateText(notification, extras);
        if (content == null || content.trim().isEmpty()) content = "无通知内容";

        String code = extractOtp(content);
        if (code == null) code = extractOtp(title);

        if (code != null) {
            String dedupKey = packageName + "|" + title + "|" + code;
            long now = System.currentTimeMillis();
            if (dedupKey.equals(lastDedupKey) && (now - lastDedupAt) < CODE_DEDUP_WINDOW_MS) {
                return;
            }
            lastDedupKey = dedupKey;
            lastDedupAt = now;
        }

        sendCodeToMainActivity(code, title, content, packageName);
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        super.onNotificationRemoved(sbn);
    }

    private String buildCandidateText(Notification notification, Bundle extras) {
        if (extras == null) return "";

        java.util.HashSet<String> seenLines = new java.util.HashSet<>();
        StringBuilder sb = new StringBuilder();

        appendIfNotEmptyDedup(sb, seenLines, extras.getCharSequence(Notification.EXTRA_TEXT));
        appendIfNotEmptyDedup(sb, seenLines, extras.getCharSequence(Notification.EXTRA_BIG_TEXT));
        appendIfNotEmptyDedup(sb, seenLines, extras.getCharSequence("android.text"));
        appendIfNotEmptyDedup(sb, seenLines, extras.getCharSequence("android.bigText"));
        appendIfNotEmptyDedup(sb, seenLines, extras.getCharSequence("android.summaryText"));
        appendIfNotEmptyDedup(sb, seenLines, extras.getCharSequence("android.subText"));

        try {
            java.util.ArrayList<?> messages = extras.getParcelableArrayList("android.messages");
            if (messages != null && !messages.isEmpty()) {
                for (int i = 0; i < messages.size(); i++) {
                    Object msgObj = messages.get(i);
                    String line = null;
                    if (msgObj instanceof Notification.MessagingStyle.Message) {
                        Notification.MessagingStyle.Message m = (Notification.MessagingStyle.Message) msgObj;
                        CharSequence t = m.getText();
                        if (t != null) line = t.toString();
                    } else if (msgObj instanceof Bundle) {
                        Bundle b = (Bundle) msgObj;
                        CharSequence t = b.getCharSequence("text");
                        if (t != null) line = t.toString();
                    }
                    if (line == null) continue;
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    appendLineDedup(sb, seenLines, line);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "解析失败", e);
        }

        try {
            for (String key : extras.keySet()) {
                Object v = extras.get(key);
                if (v instanceof CharSequence) {
                    String s = v.toString();
                    s = s == null ? "" : s.trim();
                    if (s.isEmpty()) continue;
                    String low = s.toLowerCase();
                    boolean isMatch = s.matches(".*\\d{4,8}.*") || low.contains("验证码") || low.contains("code") || low.contains("otp");
                    if (isMatch) {
                        appendLineDedup(sb, seenLines, s);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "扫描失败", e);
        }

        return sb.toString();
    }

    private void appendIfNotEmptyDedup(StringBuilder sb, java.util.HashSet<String> seen, CharSequence cs) {
        if (cs == null) return;
        String s = cs.toString();
        s = s == null ? "" : s.trim();
        if (s.isEmpty()) return;
        appendLineDedup(sb, seen, s);
    }

    private void appendLineDedup(StringBuilder sb, java.util.HashSet<String> seen, String line) {
        if (line == null) return;
        line = line.trim();
        if (line.isEmpty()) return;
        if (!seen.add(line)) return;
        if (sb.length() > 0) sb.append("\n");
        sb.append(line);
    }

    private String normalizeForOtp(String content) {
        if (content == null) return "";
        StringBuilder sb = new StringBuilder(content.length());
        for (int i = 0; i < content.length(); i++) {
            char ch = content.charAt(i);
            if (ch >= '０' && ch <= '９') {
                sb.append((char) ('0' + (ch - '０')));
            } else {
                sb.append(ch);
            }
        }
        String s = sb.toString();
        s = s.replaceAll("(\\d)\\s+(\\d)", "$1$2");
        s = s.replaceAll("(\\d)[\\-－](\\d)", "$1$2");
        return s;
    }

    private boolean looksLikeOtpText(String text) {
        if (text == null) return false;
        return text.contains("验证码") ||
                text.toLowerCase().contains("verification") ||
                text.toLowerCase().contains(" code ") ||
                text.toLowerCase().contains("otp");
    }

    private String extractOtp(String rawContent) {
        String content = normalizeForOtp(rawContent);
        if (content.isEmpty()) return null;

        Matcher m6 = SIX_DIGITS.matcher(content);
        if (m6.find()) return m6.group(1);

        if (!looksLikeOtpText(content)) return null;

        Matcher m = OTP_DIGITS.matcher(content);
        if (m.find()) return m.group(1);
        return null;
    }

    private void sendCodeToMainActivity(String code, String title, String content, String packageName) {
        Intent intent = new Intent(BROADCAST_ACTION);
        intent.putExtra("code", code);
        intent.putExtra("sender", title);
        intent.putExtra("smsContent", content);
        intent.putExtra("source", "notificationListener");
        intent.putExtra("packageName", packageName);

        try {
            androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(this)
                    .sendBroadcast(intent);
        } catch (Exception e) {
            sendBroadcast(intent);
        }
    }


}