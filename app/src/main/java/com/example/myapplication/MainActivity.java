package com.example.myapplication;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.example.myapplication.bluetooth.MiBandService;
// Neural Network Background removed for Neo-Brutalism theme
import android.view.MotionEvent;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final int PERMISSION_REQUEST_CODE = 100;

    private TextView tvStatus;
    private Button btnScanConnect;
    private Button btnVibrate;
    // Background animation removed for Neo-Brutalism theme

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bluetoothLeScanner;
    private boolean isScanning = false;

    private MiBandService miBandService;
    private boolean isBound = false;

    // ─── Service Binding ─────────────────────────────────────────────────────────

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            MiBandService.LocalBinder binder = (MiBandService.LocalBinder) service;
            miBandService = binder.getService();
            isBound = true;
            Log.d(TAG, "MiBandService bound successfully");
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            isBound = false;
            miBandService = null;
        }
    };

    // ─── Broadcast Receiver (receives state updates FROM MiBandService) ──────────

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            String state  = intent.getStringExtra(MiBandService.EXTRA_STATE);
            if (state == null) return;

            switch (state) {
                case MiBandService.STATE_CONNECTED:
                    tvStatus.setText("Status: Connected → Running auth...");
                    break;
                case MiBandService.STATE_DISCONNECTED:
                    tvStatus.setText("Status: Disconnected");
                    break;
                case MiBandService.STATE_AUTHENTICATED:
                    tvStatus.setText("✓ Connected successfully!");
                    btnScanConnect.setVisibility(android.view.View.GONE);
                    Toast.makeText(MainActivity.this, "Mi Band 3 connected & authenticated!", Toast.LENGTH_LONG).show();

                    // Switch to Dashboard View
                    Intent dashboardIntent = new Intent(MainActivity.this, DashboardActivity.class);
                    startActivity(dashboardIntent);
                    break;
                case MiBandService.STATE_AUTH_FAILED:
                    tvStatus.setText("✗ Auth failed. Check your key.");
                    Toast.makeText(MainActivity.this, "Authentication failed!", Toast.LENGTH_LONG).show();
                    break;
            }
        }
    };

    // ─── Lifecycle ───────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvStatus       = findViewById(R.id.tvStatus);
        btnScanConnect = findViewById(R.id.btnScanConnect);
        btnVibrate     = findViewById(R.id.btnVibrate);
        // Background animation removed for Neo-Brutalism theme

        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter != null) {
            bluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();
        }

        btnScanConnect.setOnClickListener(v -> checkPermissionsAndScan());
        btnVibrate.setOnClickListener(v -> {
            if (isBound && miBandService != null) {
                miBandService.vibrate();
                Toast.makeText(this, "Vibrating...", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Not connected yet!", Toast.LENGTH_SHORT).show();
            }
        });

        // Request all BLE permissions upfront on open
        checkPermissions();

        // Bind to MiBandService
        Intent serviceIntent = new Intent(this, MiBandService.class);
        startService(serviceIntent); // Start it first so it survives unbind
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);

        // Register broadcast receiver to get state updates from MiBandService.
        // Android 13+ (API 33) requires explicit RECEIVER_NOT_EXPORTED flag for internal broadcasts.
        IntentFilter filter = new IntentFilter();
        filter.addAction(MiBandService.ACTION_CONNECTION_STATE);
        filter.addAction(MiBandService.ACTION_AUTH_COMPLETE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stateReceiver, filter, RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(stateReceiver, filter);
        }
    }

    // Background touch logic removed

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(stateReceiver);
        if (isBound) {
            unbindService(serviceConnection);
            isBound = false;
        }
        stopScanning();
    }

    // ─── Permissions ─────────────────────────────────────────────────────────────

    private void checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)    != PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {

                requestPermissions(new String[]{
                        Manifest.permission.BLUETOOTH_SCAN,
                        Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.ACCESS_FINE_LOCATION
                }, PERMISSION_REQUEST_CODE);
            }
        } else {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, PERMISSION_REQUEST_CODE);
            }
        }
    }

    private void checkPermissionsAndScan() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            checkPermissions();
            return;
        }
        startScan();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "Permissions granted.");
            } else {
                Toast.makeText(this, "Bluetooth permissions are required!", Toast.LENGTH_SHORT).show();
            }
        }
    }

    // ─── Scanning ────────────────────────────────────────────────────────────────

    private void startScan() {
        if (isScanning || bluetoothLeScanner == null) return;
        try {
            isScanning = true;
            tvStatus.setText("Status: Scanning for Mi Band 3...");
            bluetoothLeScanner.startScan(scanCallback);
            Log.d(TAG, "BLE scan started");
        } catch (SecurityException e) {
            Log.e(TAG, "SecurityException startScan: " + e.getMessage());
        }
    }

    private void stopScanning() {
        if (isScanning && bluetoothLeScanner != null) {
            try {
                bluetoothLeScanner.stopScan(scanCallback);
                isScanning = false;
            } catch (SecurityException e) {
                Log.e(TAG, "SecurityException stopScan: " + e.getMessage());
            }
        }
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            super.onScanResult(callbackType, result);
            try {
                BluetoothDevice device = result.getDevice();
                String name = device.getName();
                if ("Mi Band 3".equals(name)) {
                    Log.d(TAG, "Found Mi Band 3! MAC: " + device.getAddress());

                    // Stop the scan immediately
                    stopScanning();

                    // IMPORTANT: update UI and start connection ON THE MAIN THREAD
                    runOnUiThread(() -> {
                        tvStatus.setText("Status: Found Mi Band 3! Connecting...");
                        if (isBound && miBandService != null) {
                            miBandService.connect(device.getAddress());
                        } else {
                            Log.e(TAG, "Service not bound yet, cannot connect!");
                            tvStatus.setText("Status: Service not ready, try again.");
                        }
                    });
                }
            } catch (SecurityException e) {
                Log.e(TAG, "SecurityException onScanResult: " + e.getMessage());
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            super.onScanFailed(errorCode);
            Log.e(TAG, "BLE Scan failed with error code: " + errorCode);
            runOnUiThread(() -> tvStatus.setText("Status: Scan failed (error " + errorCode + ")"));
            isScanning = false;
        }
    };
}