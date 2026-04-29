package com.app.smsAuto;

import android.Manifest;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.util.Log;
import android.util.TypedValue;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
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

import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "SmsReaderApp";
    private static final int REQUEST_ALL_PERMISSIONS = 1001;
    private TextView tvLatestSms;
    private CodeBroadcastReceiver codeBroadcastReceiver;
    private static final String ACTIVATION_URL = "http://47.243.125.179/activation_code/verification/";
    private static final String SP_NAME = "ActivationSP";
    private static final String KEY_IS_ACTIVATED = "is_activated";
    private static final String KEY_EXPIRE_TIME = "expire_time";
    private static final String KEY_SAVED_CODE = "saved_code"; // 保存激活码
    private SharedPreferences sp;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sp = getSharedPreferences(SP_NAME, MODE_PRIVATE);

        // 每次打开都验证激活码
        checkActivationOnStart();

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

    // ==============================================
    // 每次打开APP都自动验证激活码
    // ==============================================
    private void checkActivationOnStart() {
        boolean activated = sp.getBoolean(KEY_IS_ACTIVATED, false);
        String savedCode = sp.getString(KEY_SAVED_CODE, "");

        if (!activated || savedCode.isEmpty()) {
            showActivationDialog();
        } else {
            // 自动验证保存的激活码
            verifySavedCodeAutomatically(savedCode);
        }
    }

    // 自动验证本地保存的激活码
    private void verifySavedCodeAutomatically(final String code) {
        new Thread(() -> {
            try {
                OkHttpClient client = new OkHttpClient();
                MediaType JSON = MediaType.get("application/json; charset=utf-8");
                JSONObject json = new JSONObject();
                json.put("code", code);

                RequestBody body = RequestBody.create(json.toString(), JSON);
                Request request = new Request.Builder()
                        .url(ACTIVATION_URL)
                        .post(body)
                        .build();

                try (Response response = client.newCall(request).execute()) {
                    if (response.isSuccessful()) {
                        String result = response.body().string();
                        JSONObject jsonObject = new JSONObject(result);
                        String msg = jsonObject.optString("msg", "");

                        if ("激活码有效".equals(msg)) {
                            runOnUiThread(() -> {
                                Toast.makeText(MainActivity.this, "激活状态有效", Toast.LENGTH_SHORT).show();
                            });
                        } else {
                            // 失效 → 清除状态 → 重新激活
                            sp.edit().clear().apply();
                            runOnUiThread(() -> {
                                Toast.makeText(MainActivity.this, "激活码已失效，请重新激活", Toast.LENGTH_LONG).show();
                                showActivationDialog();
                            });
                        }
                    } else {
                        runOnUiThread(() -> {
                            Toast.makeText(MainActivity.this, "服务器异常，继续使用上次状态", Toast.LENGTH_SHORT).show();
                        });
                    }
                }
            } catch (Exception e) {
                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this, "网络异常，继续使用上次状态", Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    private void showActivationDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("软件激活");
        builder.setMessage("请输入激活码以继续使用");

        EditText input = new EditText(this);
        input.setHint("请输入激活码");
        LinearLayout layout = new LinearLayout(this);
        layout.setPadding(40, 30, 40, 30);
        layout.addView(input);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        input.setLayoutParams(params);
        builder.setView(layout);

        builder.setPositiveButton("激活", (dialog, which) -> {
            String code = input.getText().toString().trim();
            if (code.isEmpty()) {
                Toast.makeText(this, "激活码不能为空", Toast.LENGTH_SHORT).show();
                showActivationDialog();
                return;
            }
            requestActivation(code);
        });

        builder.setNegativeButton("退出", (dialog, which) -> finish());
        builder.setCancelable(false);
        builder.show();
    }

    private void requestActivation(final String code) {
        new Thread(() -> {
            try {
                OkHttpClient client = new OkHttpClient();
                MediaType JSON = MediaType.get("application/json; charset=utf-8");
                JSONObject json = new JSONObject();
                json.put("code", code);

                RequestBody body = RequestBody.create(json.toString(), JSON);
                Request request = new Request.Builder()
                        .url(ACTIVATION_URL)
                        .post(body)
                        .build();

                try (Response response = client.newCall(request).execute()) {
                    if (!response.isSuccessful()) {
                        runOnUiThread(() -> {
                            Toast.makeText(MainActivity.this, "激活失败：服务器异常", Toast.LENGTH_LONG).show();
                            showActivationDialog();
                        });
                        return;
                    }

                    String result = response.body().string();
                    JSONObject jsonObject = new JSONObject(result);
                    String msg = jsonObject.optString("msg", "");

                    if ("激活码有效".equals(msg)) {
                        JSONObject data = jsonObject.optJSONObject("data");
                        String expire_time = data.optString("expire_time", "");

                        sp.edit()
                                .putBoolean(KEY_IS_ACTIVATED, true)
                                .putString(KEY_SAVED_CODE, code)  // 保存激活码
                                .putString(KEY_EXPIRE_TIME, expire_time)
                                .apply();

                        runOnUiThread(() -> {
                            Toast.makeText(MainActivity.this, "激活成功！", Toast.LENGTH_LONG).show();
                        });
                    } else {
                        runOnUiThread(() -> {
                            Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show();
                            showActivationDialog();
                        });
                    }
                }
            } catch (Exception e) {
                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this, "网络请求失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
                    showActivationDialog();
                });
            }
        }).start();
    }

    // ===================================================================================
    // 以下是你原来的代码，完全不变
    // ===================================================================================

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
                String packageName = intent.getStringExtra("packageName");

                SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.CHINA);
                String time = timeFormat.format(new Date());

                String notificationInfo = "\n[" + time + "] 【新通知】\n" +
                        "应用：" + packageName + "\n" +
                        "标题：" + sender + "\n" +
                        "内容：" + content + "\n" +
                        "验证码：" + (code == null ? "未识别到6位数字" : code);

                runOnUiThread(() -> tvLatestSms.setText(tvLatestSms.getText() + notificationInfo));

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
                            Log.e(TAG, "写入失败", e);
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