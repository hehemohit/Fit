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
// Neural Network Background removed for Neo-Brutalism theme
import android.view.MotionEvent;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import com.google.android.material.bottomnavigation.BottomNavigationView;

public class DashboardActivity extends AppCompatActivity {

    private static final String TAG = "DashboardActivity";

    private TextView tvSteps;
    private TextView tvDistance;
    private TextView tvCalories;
    private TextView tvHeartRate;
    private TextView tvPeakBpm;
    private TextView tvLastWorkout;
    private com.google.android.material.progressindicator.CircularProgressIndicator progressDailyGoal;
    
    private TextView tvSleepTotal;
    private TextView tvSleepLight;
    private TextView tvSleepDeep;
    // Background animation removed for Neo-Brutalism theme
    private BottomNavigationView bottomNavigation;
    private SwipeRefreshLayout swipeRefreshLayout;
    
    private int highestBpm = 0;

    private MiBandService miBandService;
    private boolean isBound = false;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            MiBandService.LocalBinder binder = (MiBandService.LocalBinder) service;
            miBandService = binder.getService();
            isBound = true;
            Log.d(TAG, "MiBandService bound successfully to Dashboard");
            
            // Sync initial state once on bind if we haven't synced recently.
            // Or just let Swipe to refresh do it. We'll do an initial load just in case.
            miBandService.requestSteps();
            miBandService.requestSleepData();
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
                    
                    int goal = 8000;
                    int progress = (int) (((float)steps / goal) * 100);
                    if (progress > 100) progress = 100;
                    if (progressDailyGoal != null) progressDailyGoal.setProgress(progress);
                    
                    if (swipeRefreshLayout != null && swipeRefreshLayout.isRefreshing()) {
                        swipeRefreshLayout.setRefreshing(false);
                    }
                });

            } else if (MiBandService.ACTION_HEART_RATE_DATA.equals(action)) {
                int hr = intent.getIntExtra(MiBandService.EXTRA_HEART_RATE, 0);
                if (hr > highestBpm) {
                    highestBpm = hr;
                }

                runOnUiThread(() -> {
                    tvHeartRate.setText(hr + " bpm");
                    tvPeakBpm.setText(highestBpm + " bpm");
                });

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

            } else if (MiBandService.ACTION_WORKOUT_DATA.equals(action)) {
                String typeStr = intent.getStringExtra(MiBandService.EXTRA_WORKOUT_TYPE);
                if (typeStr == null) typeStr = "Workout";
                
                int duration = intent.getIntExtra(MiBandService.EXTRA_WORKOUT_DURATION, 0);
                
                final String displayType = typeStr;
                runOnUiThread(() -> tvLastWorkout.setText(displayType + "\n" + duration + " min"));
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
        tvPeakBpm    = findViewById(R.id.tvPeakBpm);
        tvLastWorkout= findViewById(R.id.tvLastWorkout);
        progressDailyGoal = findViewById(R.id.progressDailyGoal);
        
        tvSleepTotal = findViewById(R.id.tvSleepTotal);
        tvSleepLight = findViewById(R.id.tvSleepLight);
        tvSleepDeep  = findViewById(R.id.tvSleepDeep);
        // Background animation removed for Neo-Brutalism theme
        bottomNavigation = findViewById(R.id.bottomNavigation);
        swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout);

        swipeRefreshLayout.setColorSchemeResources(android.R.color.holo_blue_bright, android.R.color.holo_blue_dark);
        swipeRefreshLayout.setOnRefreshListener(() -> {
            if (isBound && miBandService != null) {
                // Request a manual sync
                miBandService.requestSteps();
                miBandService.requestSleepData();
            } else {
                swipeRefreshLayout.setRefreshing(false);
            }
        });

        // Setup bottom navigation
        bottomNavigation.setSelectedItemId(R.id.nav_home);
        bottomNavigation.setOnItemSelectedListener(item -> {
            int itemId = item.getItemId();
            if (itemId == R.id.nav_home) {
                return true;
            } else if (itemId == R.id.nav_workout) {
                startActivity(new Intent(this, WorkoutActivity.class));
                overridePendingTransition(0, 0);
                finish();
                return true;
            }
            return false;
        });

        // Bind to the running MiBandService
        Intent intent = new Intent(this, MiBandService.class);
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);

        // Register Broadcast Receiver for all data updates
        IntentFilter filter = new IntentFilter();
        filter.addAction(MiBandService.ACTION_STEPS_DATA);
        filter.addAction(MiBandService.ACTION_HEART_RATE_DATA);
        filter.addAction(MiBandService.ACTION_SLEEP_DATA);
        filter.addAction(MiBandService.ACTION_WORKOUT_DATA);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(dataReceiver, filter, RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(dataReceiver, filter);
        }
    }

    // Background touch logic removed

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(dataReceiver);

        if (isBound) {
            unbindService(serviceConnection);
            isBound = false;
        }
    }
}
