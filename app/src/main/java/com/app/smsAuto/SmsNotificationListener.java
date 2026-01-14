package com.app.smsAuto;

import android.app.Notification;
import android.content.Intent;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

// 通知监听服务：监听所有应用通知，仅提取6位数验证码
public class SmsNotificationListener extends NotificationListenerService {
    private static final String TAG = "SmsNotificationListener";
    // 仅匹配6位连续数字
    private static final Pattern CODE_PATTERN = Pattern.compile("\\d{6}");
    private static final String BROADCAST_ACTION = "com.app.smsAuto.CODE_RECEIVED";
    // 服务连接状态
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

        // 获取通知所属应用包名
        String packageName = sbn.getPackageName();
        Log.d(TAG, "监听到通知：包名=" + packageName);

        // 提取通知核心内容
        Notification notification = sbn.getNotification();
        Bundle extras = notification.extras;
        String title = extras.getString(Notification.EXTRA_TITLE, "未知标题");
        String content = extras.getString(Notification.EXTRA_TEXT, "");

        // 兼容大文本通知（长短信、长消息）
        if (content.isEmpty()) {
            content = extras.getString(Notification.EXTRA_BIG_TEXT, "无通知内容");
        }

        Log.d(TAG, "通知详情：标题=" + title + "，内容=" + content);

        // 仅提取6位数字验证码
        String code = extractCode(content);

        // 发送广播回传到MainActivity
        sendCodeToMainActivity(code, title, content, packageName);
    }

    // 正则提取6位验证码（仅返回6位数字或null）
    private String extractCode(String content) {
        if (content == null || content.isEmpty()) {
            return null;
        }
        Matcher matcher = CODE_PATTERN.matcher(content);
        if (matcher.find()) {
            return matcher.group();
        }
        return null;
    }

    // 局部广播发送（应用内通信，无安全警告）
    private void sendCodeToMainActivity(String code, String title, String content, String packageName) {
        Intent intent = new Intent(BROADCAST_ACTION);
        intent.putExtra("code", code);
        intent.putExtra("sender", title);
        intent.putExtra("smsContent", content);
        intent.putExtra("packageName", packageName);

        try {
            androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(this)
                    .sendBroadcast(intent);
            Log.d(TAG, "广播发送成功：验证码=" + (code == null ? "未提取到6位数字" : code));
        } catch (Exception e) {
            Log.e(TAG, "广播发送失败：", e);
            // 备用方案：全局广播
            sendBroadcast(intent);
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        super.onNotificationRemoved(sbn);
    }
}