package com.example.myapplication;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.example.myapplication.bluetooth.MiBandService;

public class DashboardActivity extends AppCompatActivity {

    private static final String TAG = "DashboardActivity";

    private TextView tvSteps;
    private TextView tvDistance;
    private TextView tvCalories;
    private TextView tvHeartRate;
    private TextView tvSleepTotal;
    private TextView tvSleepLight;
    private TextView tvSleepDeep;
    // NOTE: No toggle button — HR is only read when the user manually triggers it on the band.

    private MiBandService miBandService;
    private boolean isBound = false;

    // ─── No polling handler here — polling lives inside MiBandService ────────────
    // DashboardActivity just tells the Service which speed to use based on visibility.

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            MiBandService.LocalBinder binder = (MiBandService.LocalBinder) service;
            miBandService = binder.getService();
            isBound = true;
            Log.d(TAG, "MiBandService bound successfully to Dashboard");

            // Dashboard is currently visible — switch to real-time 3-second polling
            miBandService.setForegroundPolling(true);
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            isBound = false;
            miBandService = null;
        }
    };

    private final BroadcastReceiver dataReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (MiBandService.ACTION_STEPS_DATA.equals(action)) {
                int steps = intent.getIntExtra(MiBandService.EXTRA_STEPS, 0);
                double distance = intent.getDoubleExtra(MiBandService.EXTRA_DISTANCE, 0.0);
                int calories = intent.getIntExtra(MiBandService.EXTRA_CALORIES, 0);

                runOnUiThread(() -> {
                    tvSteps.setText(String.valueOf(steps));
                    tvDistance.setText(String.format("%.2f km", distance));
                    tvCalories.setText(String.format("%d kcal", calories));
                });

            } else if (MiBandService.ACTION_HEART_RATE_DATA.equals(action)) {
                int hr = intent.getIntExtra(MiBandService.EXTRA_HEART_RATE, 0);

                runOnUiThread(() -> tvHeartRate.setText(hr + " bpm"));

            } else if (MiBandService.ACTION_SLEEP_DATA.equals(action)) {
                int totalMin = intent.getIntExtra(MiBandService.EXTRA_SLEEP_TOTAL_MIN, -1);
                int lightMin = intent.getIntExtra(MiBandService.EXTRA_SLEEP_LIGHT_MIN, 0);
                int deepMin  = intent.getIntExtra(MiBandService.EXTRA_SLEEP_DEEP_MIN,  0);

                runOnUiThread(() -> {
                    if (totalMin < 0) {
                        tvSleepTotal.setText("--h --m");
                        tvSleepLight.setText("--m");
                        tvSleepDeep.setText("--m");
                    } else {
                        tvSleepTotal.setText(String.format("%dh %02dm", totalMin / 60, totalMin % 60));
                        tvSleepLight.setText(lightMin + "m");
                        tvSleepDeep.setText(deepMin + "m");
                    }
                });
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dashboard);

        tvSteps      = findViewById(R.id.tvSteps);
        tvDistance   = findViewById(R.id.tvDistance);
        tvCalories   = findViewById(R.id.tvCalories);
        tvHeartRate  = findViewById(R.id.tvHeartRate);
        tvSleepTotal = findViewById(R.id.tvSleepTotal);
        tvSleepLight = findViewById(R.id.tvSleepLight);
        tvSleepDeep  = findViewById(R.id.tvSleepDeep);

        // Bind to the running MiBandService
        Intent intent = new Intent(this, MiBandService.class);
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);

        // Register Broadcast Receiver for all data updates
        IntentFilter filter = new IntentFilter();
        filter.addAction(MiBandService.ACTION_STEPS_DATA);
        filter.addAction(MiBandService.ACTION_HEART_RATE_DATA);
        filter.addAction(MiBandService.ACTION_SLEEP_DATA);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(dataReceiver, filter, RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(dataReceiver, filter);
        }
    }

    // ─── Foreground / Background lifecycle ───────────────────────────────────────

    @Override
    protected void onResume() {
        super.onResume();
        // App came to foreground — switch to real-time 3-second polling for the "wow" effect
        if (isBound && miBandService != null) {
            miBandService.setForegroundPolling(true);
            Log.d(TAG, "onResume → FOREGROUND polling (3s)");
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // App went to background — drop to 15-minute polling to save band battery
        if (isBound && miBandService != null) {
            miBandService.setForegroundPolling(false);
            Log.d(TAG, "onPause → BACKGROUND polling (15min)");
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(dataReceiver);

        // NOTE: We do NOT call stopPolling() here.
        // The Service continues background polling even after the Activity is gone,
        // keeping data fresh without hammering the BLE radio.
        if (isBound) {
            unbindService(serviceConnection);
            isBound = false;
        }
    }
}
