package com.example.autoplus;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    public static final String PREFS = "autoplus_prefs";
    public static final String KEY_ENABLED = "automation_enabled";

    private SharedPreferences prefs;
    private Switch autoSwitch;
    private TextView statusText;
    private TextView testStatus;
    private Button testDelayButton;

    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        autoSwitch = findViewById(R.id.autoSwitch);
        statusText = findViewById(R.id.statusText);
        testStatus = findViewById(R.id.testStatus);
        testDelayButton = findViewById(R.id.testDelayButton);
        Button openAccessibilityButton = findViewById(R.id.openAccessibilityButton);
        Button openSignalButton = findViewById(R.id.openSignalButton);

        autoSwitch.setChecked(prefs.getBoolean(KEY_ENABLED, false));

        autoSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean(KEY_ENABLED, isChecked).apply();

            if (isChecked && !isAccessibilityServiceEnabled()) {
                new AlertDialog.Builder(this)
                        .setTitle("Потрібен доступ")
                        .setMessage("Увімкни сервіс AutoPlus у спеціальних можливостях Android. Без цього програма не може бачити елементи Signal і натискати реакцію.")
                        .setPositiveButton("Відкрити налаштування", (dialog, which) -> openAccessibilitySettings())
                        .setNegativeButton("Пізніше", null)
                        .show();
            }
        });

        openAccessibilityButton.setOnClickListener(v -> openAccessibilitySettings());

        openSignalButton.setOnClickListener(v -> {
            Intent launchIntent = getPackageManager().getLaunchIntentForPackage("org.thoughtcrime.securesms");
            if (launchIntent == null) {
                Toast.makeText(this, "Signal не знайдено", Toast.LENGTH_SHORT).show();
                return;
            }
            startActivity(launchIntent);
        });

        testDelayButton.setOnClickListener(v -> runDelayTest());
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateServiceStatus();
        autoSwitch.setChecked(prefs.getBoolean(KEY_ENABLED, false));
    }

    private void openAccessibilitySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (Exception e) {
            Toast.makeText(this, "Не вдалося відкрити налаштування", Toast.LENGTH_SHORT).show();
        }
    }

    private void updateServiceStatus() {
        boolean enabled = isAccessibilityServiceEnabled();
        if (enabled) {
            statusText.setText("Увімкнений • готовий працювати в Signal");
            statusText.setTextColor(Color.rgb(54, 194, 117));
        } else {
            statusText.setText("Вимкнений • дозвіл ще не надано");
            statusText.setTextColor(Color.rgb(224, 91, 102));
        }
    }

    private boolean isAccessibilityServiceEnabled() {
        int accessibilityEnabled = 0;
        try {
            accessibilityEnabled = Settings.Secure.getInt(
                    getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED
            );
        } catch (Settings.SettingNotFoundException ignored) {
        }

        if (accessibilityEnabled != 1) return false;

        String enabledServices = Settings.Secure.getString(
                getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        );

        if (TextUtils.isEmpty(enabledServices)) return false;

        ComponentName expected = new ComponentName(this, AutoPlusAccessibilityService.class);
        TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
        splitter.setString(enabledServices);

        while (splitter.hasNext()) {
            ComponentName enabled = ComponentName.unflattenFromString(splitter.next());
            if (expected.equals(enabled)) return true;
        }
        return false;
    }

    private void runDelayTest() {
        testDelayButton.setEnabled(false);
        testStatus.setText("Тест: 5…");

        final int[] left = {5};

        Runnable tick = new Runnable() {
            @Override
            public void run() {
                left[0]--;

                if (left[0] <= 0) {
                    testStatus.setText("✓ 5 секунд минуло. Затримка працює.");
                    testStatus.setTextColor(Color.rgb(54, 194, 117));
                    testDelayButton.setEnabled(true);
                    return;
                }

                testStatus.setText("Тест: " + left[0] + "…");
                handler.postDelayed(this, 1000);
            }
        };

        handler.postDelayed(tick, 1000);
    }
}
