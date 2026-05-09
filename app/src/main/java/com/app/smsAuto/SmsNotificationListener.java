package com.app.smsAuto;

import static android.app.Notification.EXTRA_MESSAGES;

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

        String content = getText(notification, extras);
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

    // 获取通知内容,利用extras
    private String getText(Notification notification, Bundle extras) {
        if (extras == null) return "";

        // 创建集合，用于去重
        java.util.HashSet<String> seenLines = new java.util.HashSet<>();
        // 字符串拼接工具，比直接用 + 效率高，专门用来拼接长文本
        StringBuilder sb = new StringBuilder();

        // 读取标准通知里的普通文本，sequence的意思是序列
        CharSequence cs1 = extras.getCharSequence(Notification.EXTRA_TEXT);
        Log.d("NotificationText", "EXTRA_TEXT = " + cs1);
        appendText(sb, seenLines, "EXTRA_TEXT", cs1);

        // 读取标准通知里的大文本，展开后内容
        CharSequence cs2 = extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
        Log.d("NotificationText", "EXTRA_BIG_TEXT = " + cs2);
        appendText(sb, seenLines, "EXTRA_BIG_TEXT", cs2);

        // 读取部分厂商/旧版系统用的自定义通知文本字段
        CharSequence cs3 = extras.getCharSequence("android.text");
        Log.d("NotificationText", "android.text = " + cs3);
        appendText(sb, seenLines, "android.text", cs3);

        CharSequence cs4 = extras.getCharSequence("android.bigText");
        Log.d("NotificationText", "android.bigText = " + cs4);
        appendText(sb, seenLines, "android.bigText", cs4);

        CharSequence cs5 = extras.getCharSequence("android.summaryText");
        Log.d("NotificationText", "android.summaryText = " + cs5);
        appendText(sb, seenLines, "android.summaryText", cs5);

        CharSequence cs6 = extras.getCharSequence("android.subText");
        Log.d("NotificationText", "android.subText = " + cs6);
        appendText(sb, seenLines, "android.subText", cs6);

        // 读取通知谷歌官方字段：android.messages
        try {
            java.util.ArrayList<?> messages = extras.getParcelableArrayList(EXTRA_MESSAGES);
            Log.d("NotificationText", "android.messages 列表长度 = " + (messages == null ? 0 : messages.size()));
            if (messages != null && !messages.isEmpty()) {
                for (int i = 0; i < messages.size(); i++) {
                    Object msgObj = messages.get(i);
                    String line = null;
                    if (msgObj instanceof Notification.MessagingStyle.Message) {
                        Notification.MessagingStyle.Message m = (Notification.MessagingStyle.Message) msgObj;
                        CharSequence t = m.getText();
                        Log.d("NotificationText", "MessagingStyle消息内容 = " + t);
                        if (t != null) line = t.toString();
                    } else if (msgObj instanceof Bundle) {
                        Bundle b = (Bundle) msgObj;
                        CharSequence t = b.getCharSequence("text");
                        Log.d("NotificationText", "Bundle消息text = " + t);
                        if (t != null) line = t.toString();
                    }
                    if (line == null) continue;
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    appendText(sb, seenLines, "android.messages[" + i + "]", line);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "解析失败", e);
        }

        // 遍历通知里所有的键值对
        try {
            for (String key : extras.keySet()) {
                Object v = extras.get(key);
                if (v instanceof CharSequence) {
                    String s = v.toString();
                    s = s == null ? "" : s.trim();
                    if (s.isEmpty()) continue;
                    String low = s.toLowerCase();
                    boolean isMatch = s.matches(".*\\d{4,8}.*") || low.contains("验证码") || low.contains("code") || low.contains("otp");
                    Log.d("NotificationText", "遍历extras的字段 key=" + key + "  value=" + s + "  isMatch=" + isMatch);
                    if (isMatch) {
                        appendText(sb, seenLines, key, s);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "扫描失败", e);
        }

        Log.d("NotificationText", "最终拼接结果 = " + sb.toString());
        return sb.toString();
    }

    // 拼接通知内容
    private void appendText(StringBuilder sb, java.util.HashSet<String> seen, String fieldName, CharSequence cs) {
        // 1. 空值判断
        if (cs == null) return;
        String line = cs.toString().trim();
        if (line.isEmpty()) return;

        // 2. 拼接格式：【字段名】内容
        String resultLine = "【" + fieldName + "】" + line;

        // 3. 去重
        if (!seen.add(resultLine)) return;

        // 4. 自动换行 + 拼接
        if (sb.length() > 0) {
            sb.append("\n");
        }
        sb.append(resultLine);
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