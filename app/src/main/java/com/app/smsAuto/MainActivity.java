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
import android.util.Log;
import android.util.TypedValue;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

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
    // 日志标签
    private static final String TAG = "SmsReaderApp";
    // 权限请求码
    private static final int REQUEST_ALL_PERMISSIONS = 1001;
    // 用于展示内容的TextView
    private TextView tvLatestSms;
    // 验证码广播接收器
    private CodeBroadcastReceiver codeBroadcastReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());

            // 仅 systemBars 的 top 不包含 ActionBar/标题栏高度，Edge-to-Edge 下会出现内容被标题挡住
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

        // 初始化控件
        tvLatestSms = findViewById(R.id.tv_latest_sms);
        tvLatestSms.setText("正在初始化...");

        // 初始化通知权限按钮
        Button btnOpenNotificationSettings = findViewById(R.id.btn_open_notification_settings);
        btnOpenNotificationSettings.setOnClickListener(v -> openNotificationListenerSettings());

        // 初始化文件访问权限按钮
        Button btnOpenFileAccess = findViewById(R.id.btn_open_file_access);
        btnOpenFileAccess.setOnClickListener(v -> openFileAccessSettings());

        // 初始化删除验证码文件按钮
        Button btnDeleteVerificationFile = findViewById(R.id.btn_delete_verification_file);
        btnDeleteVerificationFile.setOnClickListener(v -> deleteVerificationCodeFile());

        // 1. 强制申请所有文件访问权限（Android 11+）
        checkManageExternalStoragePermission();

        // 2. 强制申请其他权限
        requestAllPermissions();

        // 3. 检查通知监听权限，强制跳转开启
        checkNotificationPermission();

        // 4. 注册局部广播接收器
        registerCodeReceiver();
    }

    /**
     * 检查并申请所有文件访问权限（Android 11+）
     */
    private void checkManageExternalStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                Toast.makeText(this, "请开启「所有文件访问」权限以保存验证码", Toast.LENGTH_LONG).show();
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                Uri uri = Uri.fromParts("package", getPackageName(), null);
                intent.setData(uri);
                startActivity(intent);
            } else {
                Log.d(TAG, "所有文件访问权限已开启");
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 每次返回时重新检查通知监听权限
        checkNotificationPermission();
        // 更新服务连接状态显示
        updateServiceStatus();
    }

    /**
     * 更新服务连接状态显示
     */
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

    /**
     * 强制申请所有需要的权限
     */
    private void requestAllPermissions() {
        List<String> permissionsNeeded = new ArrayList<>();

        // 外部存储权限（Android 13以下）
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            }
        }

        // 申请权限
        if (!permissionsNeeded.isEmpty()) {
            ActivityCompat.requestPermissions(this,
                    permissionsNeeded.toArray(new String[0]),
                    REQUEST_ALL_PERMISSIONS);
        } else {
            Log.d(TAG, "所有权限已获取");
        }
    }

    /**
     * 强制跳转：检查并开启通知监听权限
     */
    private void checkNotificationPermission() {
        String pkgName = getPackageName();
        String flat = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        if (flat == null || !flat.contains(pkgName)) {
            Toast.makeText(this, "请开启通知监听权限以获取验证码", Toast.LENGTH_LONG).show();
            openNotificationListenerSettings();
        } else {
            Log.d(TAG, "通知监听权限已开启");
        }
    }

    /**
     * 跳转到通知监听权限设置页面
     */
    private void openNotificationListenerSettings() {
        Intent intent = new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    /**
     * 跳转到所有文件访问权限设置页面
     */
    private void openFileAccessSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ 跳转到所有文件访问权限页面
            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
            Uri uri = Uri.fromParts("package", getPackageName(), null);
            intent.setData(uri);
            startActivity(intent);
        } else {
            // Android 10及以下跳转到应用设置页面
            openAppSettings();
        }
    }

    /**
     * 删除验证码文件：/storage/emulated/0/verification_code.json
     */
    private void deleteVerificationCodeFile() {
        try {
            File file = new File("/storage/emulated/0/verification_code.json");
            if (!file.exists()) {
                Toast.makeText(this, "文件不存在：verification_code.json", Toast.LENGTH_SHORT).show();
                return;
            }
            boolean deleted = file.delete();
            if (deleted) {
                Toast.makeText(this, "已删除：verification_code.json", Toast.LENGTH_SHORT).show();
                Log.d(TAG, "已删除文件：" + file.getAbsolutePath());
            } else {
                Toast.makeText(this, "删除失败，请检查「所有文件访问」权限", Toast.LENGTH_LONG).show();
                Log.w(TAG, "删除失败：" + file.getAbsolutePath());
            }
        } catch (Exception e) {
            Toast.makeText(this, "删除异常：" + e.getMessage(), Toast.LENGTH_LONG).show();
            Log.e(TAG, "删除文件异常：", e);
        }
    }

    /**
     * 注册局部广播接收器
     */
    private void registerCodeReceiver() {
        codeBroadcastReceiver = new CodeBroadcastReceiver();
        IntentFilter filter = new IntentFilter();
        filter.addAction("com.app.smsAuto.CODE_RECEIVED");
        LocalBroadcastManager.getInstance(this).registerReceiver(codeBroadcastReceiver, filter);
    }

    /**
     * 广播接收器：接收6位验证码，写入外部存储绝对路径（可直接查看）
     */
    class CodeBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("com.app.smsAuto.CODE_RECEIVED".equals(intent.getAction())) {
                // 提取广播参数
                String code = intent.getStringExtra("code");
                String sender = intent.getStringExtra("sender");
                String content = intent.getStringExtra("smsContent");
                String packageName = intent.getStringExtra("packageName");

                // 获取当前时间
                SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.CHINA);
                String time = timeFormat.format(new Date());

                // 组装展示内容
                String notificationInfo = "\n[" + time + "] 【新通知】\n" +
                        "应用：" + packageName + "\n" +
                        "标题：" + sender + "\n" +
                        "内容：" + content + "\n" +
                        "验证码：" + (code == null ? "未识别到6位数字" : code);

                // 追加展示到界面
                runOnUiThread(() -> tvLatestSms.setText(tvLatestSms.getText() + notificationInfo));
                
                Log.d(TAG, "收到通知广播: " + notificationInfo);

                // 仅6位验证码写入外部存储（JSON文件，覆盖模式）
                if (code != null) {
                    final String finalCode = code;
                    final String finalSender = sender;
                    new Thread(() -> {
                        try {
                            // 直接写入根目录：/storage/emulated/0/verification_code.json
                            File file = new File("/storage/emulated/0/verification_code.json");

                            // JSON内容：{"发送方":"xxxx","验证码":"xxxxx"}
                            String jsonContent = "{\"发送方\":\"" + finalSender + "\",\"验证码\":\"" + finalCode + "\"}";

                            // 覆盖模式写入文件（false表示不追加）
                            FileWriter writer = new FileWriter(file, false);
                            writer.write(jsonContent);
                            writer.flush();
                            writer.close();

                            // 打印文件绝对路径（便于调试）
                            Log.d(TAG, "验证码JSON写入成功：" + file.getAbsolutePath() + " 内容：" + jsonContent);
                        } catch (Exception e) {
                            Log.e(TAG, "验证码JSON写入失败：", e);
                        }
                    }).start();

                    Toast.makeText(MainActivity.this, "提取到6位验证码：" + code, Toast.LENGTH_SHORT).show();
                    Log.d(TAG, "6位验证码提取成功：" + code);
                }
            }
        }
    }

    /**
     * 权限申请结果回调
     */
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_ALL_PERMISSIONS) {
            boolean allGranted = true;
            for (int i = 0; i < grantResults.length; i++) {
                if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    Log.w(TAG, "权限被拒绝: " + permissions[i]);
                }
            }

            if (!allGranted) {
                // 如果权限被拒绝，跳转到应用设置页面
                Toast.makeText(this, "请在设置中手动开启所需权限", Toast.LENGTH_LONG).show();
                openAppSettings();
            } else {
                Log.d(TAG, "所有权限授权成功");
                Toast.makeText(this, "权限授权成功", Toast.LENGTH_SHORT).show();
            }
        }
    }

    /**
     * 跳转到应用设置页面
     */
    private void openAppSettings() {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        Uri uri = Uri.fromParts("package", getPackageName(), null);
        intent.setData(uri);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    /**
     * 注销广播接收器，避免内存泄漏
     */
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (codeBroadcastReceiver != null) {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(codeBroadcastReceiver);
        }
    }
}
