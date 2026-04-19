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
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.example.myapplication.bluetooth.MiBandService;
// Neural Network Background removed for Neo-Brutalism theme
import com.google.android.material.bottomnavigation.BottomNavigationView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class WorkoutActivity extends AppCompatActivity {

    private static final String TAG = "WorkoutActivity";

    private LinearLayout workoutList;
    private TextView tvLazyHuman;
    private View workoutScrollView;
    private BottomNavigationView bottomNavigation;
    private java.util.Random random = new java.util.Random();

    private MiBandService miBandService;
    private boolean isBound = false;

    // We store parsed workouts here
    public static class WorkoutSession {
        public String type; // Treadmill or Exercise
        public int durationMinutes;
        public int avgHeartRate;
        public int totalSteps;
        public int totalCalories;
    }

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            MiBandService.LocalBinder binder = (MiBandService.LocalBinder) service;
            miBandService = binder.getService();
            isBound = true;

            // Optional: Request workout data if the service supports it
            // For now, we rely on the broadcasted parsed workout data from the activity sync.
            Log.d(TAG, "MiBandService bound successfully to WorkoutActivity");
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            isBound = false;
            miBandService = null;
        }
    };

    private final BroadcastReceiver workoutReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (MiBandService.ACTION_WORKOUT_DATA.equals(intent.getAction())) {
                String type = intent.getStringExtra(MiBandService.EXTRA_WORKOUT_TYPE);
                int duration = intent.getIntExtra(MiBandService.EXTRA_WORKOUT_DURATION, 0);
                int avgHr = intent.getIntExtra(MiBandService.EXTRA_WORKOUT_AVG_HR, 0);
                int steps = intent.getIntExtra(MiBandService.EXTRA_WORKOUT_STEPS, 0);
                int calories = intent.getIntExtra(MiBandService.EXTRA_WORKOUT_CALORIES, 0);

                WorkoutSession ws = new WorkoutSession();
                ws.type = type != null ? type : "Exercise";
                ws.durationMinutes = duration;
                ws.avgHeartRate = avgHr;
                ws.totalSteps = steps;
                ws.totalCalories = calories;

                runOnUiThread(() -> {
                    tvLazyHuman.setVisibility(View.GONE);
                    workoutScrollView.setVisibility(View.VISIBLE);
                    addWorkoutCard(ws);
                });
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_workout);

        workoutList = findViewById(R.id.workoutList);
        tvLazyHuman = findViewById(R.id.tvLazyHuman);
        workoutScrollView = findViewById(R.id.workoutScrollView);
        // Background animation removed for Neo-Brutalism theme
        bottomNavigation = findViewById(R.id.bottomNavigation);

        tvLazyHuman.setOnClickListener(v -> moveLazyHuman());

        // Initial check
        updateWorkoutVisibility();

        // Set the current tab to workout
        bottomNavigation.setSelectedItemId(R.id.nav_workout);

        bottomNavigation.setOnItemSelectedListener(item -> {
            int itemId = item.getItemId();
            if (itemId == R.id.nav_home) {
                startActivity(new Intent(this, DashboardActivity.class));
                overridePendingTransition(0, 0);
                finish();
                return true;
            } else if (itemId == R.id.nav_workout) {
                return true;
            }
            // Add other tabs here later
            return false;
        });

        // Bind Service
        Intent intent = new Intent(this, MiBandService.class);
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);

        // Register Receiver
        IntentFilter filter = new IntentFilter(MiBandService.ACTION_WORKOUT_DATA);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(workoutReceiver, filter, RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(workoutReceiver, filter);
        }

        // Only real workout data from the broadcast receiver will be added here.
    }

    private void updateWorkoutVisibility() {
        if (workoutList.getChildCount() == 0) {
            tvLazyHuman.setVisibility(View.VISIBLE);
            workoutScrollView.setVisibility(View.GONE);
        } else {
            tvLazyHuman.setVisibility(View.GONE);
            workoutScrollView.setVisibility(View.VISIBLE);
        }
    }

    private void moveLazyHuman() {
        // Find the parent view to know its size
        View parent = (View) tvLazyHuman.getParent();
        if (parent == null) return;

        int parentWidth = parent.getWidth();
        int parentHeight = parent.getHeight();
        int itemWidth = tvLazyHuman.getWidth();
        int itemHeight = tvLazyHuman.getHeight();

        if (parentWidth <= itemWidth || parentHeight <= itemHeight) return;

        // Calculate random position within bounds
        int maxX = parentWidth - itemWidth;
        int maxY = parentHeight - itemHeight;

        // For Neo-Brutalism, let's also give it a random rotation
        float randomX = random.nextInt(maxX);
        float randomY = random.nextInt(maxY);
        float randomRotation = random.nextFloat() * 20 - 10; // -10 to 10 degrees

        // Remove constraints so translation works freely relative to top/left
        // Or just use translations if it was centered.
        // Since it's in a ConstraintLayout and centered, translations are relative to center.
        // It's easier to just set translationX/Y from the center point.
        
        float centerX = parentWidth / 2f;
        float centerY = parentHeight / 2f;
        
        float targetTranslationX = randomX - (centerX - itemWidth/2f);
        float targetTranslationY = randomY - (centerY - itemHeight/2f);

        tvLazyHuman.animate()
                .translationX(targetTranslationX)
                .translationY(targetTranslationY)
                .rotation(randomRotation)
                .setDuration(300)
                .start();
    }

    private void addWorkoutCard(WorkoutSession ws) {
        if (ws.durationMinutes <= 0) return; // Skip invalid workouts

        LayoutInflater inflater = LayoutInflater.from(this);
        View card = inflater.inflate(R.layout.item_workout_card, workoutList, false);

        TextView tvType = card.findViewById(R.id.tvWorkoutType);
        TextView tvDuration = card.findViewById(R.id.tvWorkoutDuration);
        TextView tvAvgHr = card.findViewById(R.id.tvWorkoutAvgHr);
        TextView tvSteps = card.findViewById(R.id.tvWorkoutSteps);
        TextView tvCalories = card.findViewById(R.id.tvWorkoutCalories);

        tvType.setText(ws.type != null ? ws.type : "Activity");
        tvDuration.setText(ws.durationMinutes + " min");
        tvAvgHr.setText(ws.avgHeartRate > 0 ? ws.avgHeartRate + " bpm" : "--");
        tvSteps.setText(ws.totalSteps > 0 ? String.valueOf(ws.totalSteps) : "--");
        tvCalories.setText(ws.totalCalories > 0 ? ws.totalCalories + " kcal" : "--");

        // Add to top of list
        workoutList.addView(card, 0);
    }

    // Background touch logic removed

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(workoutReceiver);
        if (isBound) {
            unbindService(serviceConnection);
            isBound = false;
        }
    }
}
