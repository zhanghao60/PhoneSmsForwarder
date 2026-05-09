package com.app.smsAuto;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.util.TypedValue;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.view.View;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "SmsReaderApp";
    private static final int REQUEST_ALL_PERMISSIONS = 1001;
    private TextView tvLatestSms;
    private ScrollView svSmsLog;
    private CodeBroadcastReceiver codeBroadcastReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            int actionBarHeight = 0;
            if (getSupportActionBar() != null && getSupportActionBar().isShowing()) {
                TypedValue tv = new TypedValue();
                if (getTheme().resolveAttribute(android.R.attr.actionBarSize, tv, true)) {
                    actionBarHeight = TypedValue.complexToDimensionPixelSize(
                            tv.data, getResources().getDisplayMetrics()
                    );
                }
            }
            v.setPadding(systemBars.left, systemBars.top + actionBarHeight, systemBars.right, systemBars.bottom);
            return insets;
        });

        tvLatestSms = findViewById(R.id.tv_latest_sms);
        tvLatestSms.setText("正在初始化...");
        svSmsLog = findViewById(R.id.scroll_sms_log);

        Button btnOpenNotificationSettings = findViewById(R.id.btn_open_notification_settings);
        btnOpenNotificationSettings.setOnClickListener(v -> openNotificationListenerSettings());

        Button btnOpenFileAccess = findViewById(R.id.btn_open_file_access);
        btnOpenFileAccess.setOnClickListener(v -> openFileAccessSettings());

        Button btnDeleteVerificationFile = findViewById(R.id.btn_delete_verification_file);
        btnDeleteVerificationFile.setOnClickListener(v -> deleteVerificationCodeFile());

        checkManageExternalStoragePermission();
        requestAllPermissions();
        checkNotificationPermission();
        registerCodeReceiver();
    }

    private void checkManageExternalStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                Toast.makeText(this, "请开启「所有文件访问」权限以保存验证码", Toast.LENGTH_LONG).show();
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                Uri uri = Uri.fromParts("package", getPackageName(), null);
                intent.setData(uri);
                startActivity(intent);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkNotificationPermission();
        updateServiceStatus();
    }

    private void updateServiceStatus() {
        String pkgName = getPackageName();
        String flat = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        boolean permissionEnabled = flat != null && flat.contains(pkgName);
        boolean serviceConnected = SmsNotificationListener.isServiceConnected();

        String statusText = "【服务状态】\n" +
                "通知监听权限: " + (permissionEnabled ? "✓ 已开启" : "✗ 未开启") + "\n" +
                "服务连接状态: " + (serviceConnected ? "✓ 已连接" : "✗ 未连接") + "\n";

        if (permissionEnabled && !serviceConnected) {
            statusText += "⚠ 提示：请在设置中关闭再重新开启通知监听权限\n";
        }
        statusText += "\n———————— 通知监听日志 ————————";
        tvLatestSms.setText(statusText);
    }

    private void requestAllPermissions() {
        List<String> permissionsNeeded = new ArrayList<>();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            }
        }
        if (!permissionsNeeded.isEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsNeeded.toArray(new String[0]), REQUEST_ALL_PERMISSIONS);
        }
    }

    private void checkNotificationPermission() {
        String pkgName = getPackageName();
        String flat = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        if (flat == null || !flat.contains(pkgName)) {
            Toast.makeText(this, "请开启通知监听权限以获取验证码", Toast.LENGTH_LONG).show();
            openNotificationListenerSettings();
        }
    }

    private void openNotificationListenerSettings() {
        Intent intent = new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    private void openFileAccessSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
            Uri uri = Uri.fromParts("package", getPackageName(), null);
            intent.setData(uri);
            startActivity(intent);
        } else {
            openAppSettings();
        }
    }

    private void deleteVerificationCodeFile() {
        try {
            File file = new File("/storage/emulated/0/verification_code.json");
            if (!file.exists()) {
                Toast.makeText(this, "文件不存在", Toast.LENGTH_SHORT).show();
                return;
            }
            if (file.delete()) {
                Toast.makeText(this, "删除成功", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "删除失败", Toast.LENGTH_LONG).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "删除异常", Toast.LENGTH_LONG).show();
        }
    }

    private void registerCodeReceiver() {
        codeBroadcastReceiver = new CodeBroadcastReceiver();
        IntentFilter filter = new IntentFilter();
        filter.addAction("com.app.smsAuto.CODE_RECEIVED");
        LocalBroadcastManager.getInstance(this).registerReceiver(codeBroadcastReceiver, filter);
    }

    class CodeBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("com.app.smsAuto.CODE_RECEIVED".equals(intent.getAction())) {
                String code = intent.getStringExtra("code");
                String sender = intent.getStringExtra("sender");
                String content = intent.getStringExtra("smsContent");
                String source = intent.getStringExtra("source");
                String packageName = intent.getStringExtra("packageName");

                SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.CHINA);
                String time = timeFormat.format(new Date());

                String notificationInfo = "\n[" + time + "] 【新通知】\n" +
                        "应用：" + packageName + "\n" +
                        "标题：" + sender + "\n" +
                        "内容：" + content + "\n" +
                        "来源：" + (source == null ? "unknown" : source) + "\n" +
                        "验证码：" + (code == null ? "未识别到6位数字" : code);

                runOnUiThread(() -> tvLatestSms.setText(tvLatestSms.getText() + notificationInfo));

                runOnUiThread(() -> {
                    if (svSmsLog != null) {
                        svSmsLog.post(() -> svSmsLog.fullScroll(View.FOCUS_DOWN));
                    }
                });

                if (code != null) {
                    new Thread(() -> {
                        try {
                            File file = new File("/storage/emulated/0/verification_code.json");
                            String jsonContent = "{\"发送方\":\"" + sender + "\",\"验证码\":\"" + code + "\"}";
                            FileWriter writer = new FileWriter(file, false);
                            writer.write(jsonContent);
                            writer.flush();
                            writer.close();
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }).start();
                }
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        boolean allGranted = true;
        for (int result : grantResults) {
            if (result != PackageManager.PERMISSION_GRANTED) allGranted = false;
        }
        if (!allGranted) {
            Toast.makeText(this, "请开启权限", Toast.LENGTH_LONG).show();
            openAppSettings();
        }
    }

    private void openAppSettings() {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        Uri uri = Uri.fromParts("package", getPackageName(), null);
        intent.setData(uri);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (codeBroadcastReceiver != null) {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(codeBroadcastReceiver);
        }
    }
}