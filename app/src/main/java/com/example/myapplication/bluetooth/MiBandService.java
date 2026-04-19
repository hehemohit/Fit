package com.example.myapplication.bluetooth;

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Intent;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import java.util.Arrays;
import java.util.UUID;

public class MiBandService extends Service {

    private static final String TAG = "MiBandService";

    // UUID for the Client Characteristic Configuration Descriptor (CCCD) to enable notifications
    private static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    // Broadcast action constants for updating UI in MainActivity
    public static final String ACTION_CONNECTION_STATE = "com.example.myapplication.CONNECTION_STATE";
    public static final String ACTION_AUTH_COMPLETE    = "com.example.myapplication.AUTH_COMPLETE";
    public static final String ACTION_STEPS_DATA       = "com.example.myapplication.STEPS_DATA";
    public static final String ACTION_HEART_RATE_DATA  = "com.example.myapplication.HEART_RATE_DATA";
    public static final String ACTION_SLEEP_DATA       = "com.example.myapplication.SLEEP_DATA";
    public static final String ACTION_WORKOUT_DATA     = "com.example.myapplication.WORKOUT_DATA";
    
    public static final String EXTRA_STATE            = "state";
    public static final String EXTRA_STEPS            = "steps";
    public static final String EXTRA_DISTANCE         = "distance";
    public static final String EXTRA_CALORIES         = "calories";
    public static final String EXTRA_HEART_RATE       = "heart_rate";
    public static final String EXTRA_SLEEP_TOTAL_MIN  = "sleep_total_min";
    public static final String EXTRA_SLEEP_DEEP_MIN   = "sleep_deep_min";
    public static final String EXTRA_SLEEP_LIGHT_MIN  = "sleep_light_min";
    public static final String EXTRA_WORKOUT_TYPE     = "workout_type";
    public static final String EXTRA_WORKOUT_DURATION = "workout_duration";
    public static final String EXTRA_WORKOUT_AVG_HR   = "workout_avg_hr";
    public static final String EXTRA_WORKOUT_STEPS    = "workout_steps";
    public static final String EXTRA_WORKOUT_CALORIES = "workout_calories";
    
    public static final String STATE_CONNECTED        = "CONNECTED";
    public static final String STATE_DISCONNECTED     = "DISCONNECTED";
    public static final String STATE_AUTHENTICATED    = "AUTHENTICATED";
    public static final String STATE_AUTH_FAILED      = "AUTH_FAILED";

    // Handler to ensure all GATT writes are sequential on the main thread
    private final Handler handler = new Handler(Looper.getMainLooper());

    private BluetoothGatt bluetoothGatt;
    private BluetoothGattCharacteristic authCharacteristic;

    // Sleep data accumulation state
    // Mi Band 3 streams activity packets; we accumulate minutes per sleep stage.
    // Activity type 0x01 = light sleep, 0x04 = deep sleep.
    private int sleepLightMinutes = 0;
    private int sleepDeepMinutes  = 0;
    private boolean isFetchingActivity = false;

    // Workout extraction state
    private int activeWorkoutType = 0; // 0=none, 0x11=treadmill, 0x12=exercise
    private int activeWorkoutDuration = 0;
    private int activeWorkoutSteps = 0;
    private int activeWorkoutHrSum = 0;
    private int activeWorkoutHrCount = 0;

    // Binder for MainActivity / DashboardActivity to bind to this service
    private final IBinder binder = new LocalBinder();

    public class LocalBinder extends Binder {
        public MiBandService getService() {
            return MiBandService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    // ─── Public API ─────────────────────────────────────────────────────────────

    /**
     * Connects to the Mi Band 3 using the provided MAC address.
     * Must be called from the main thread.
     */
    public void connect(String macAddress) {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || macAddress == null) {
            Log.e(TAG, "connect(): BluetoothAdapter is null or macAddress is null");
            return;
        }

        // Disconnect any existing connection cleanly before reconnecting
        if (bluetoothGatt != null) {
            try {
                bluetoothGatt.disconnect();
                bluetoothGatt.close();
                bluetoothGatt = null;
            } catch (SecurityException e) {
                Log.e(TAG, "SecurityException while closing existing GATT: " + e.getMessage());
            }
        }

        BluetoothDevice device = adapter.getRemoteDevice(macAddress);
        Log.d(TAG, "Connecting to device: " + macAddress);

        try {
            // TRANSPORT_LE forces BLE connection (critical for Mi Band 3)
            bluetoothGatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
        } catch (SecurityException e) {
            Log.e(TAG, "SecurityException in connect(): " + e.getMessage());
        }
    }

    /**
     * Sends a vibrate command to the Mi Band by writing 0x02 to the Alert Characteristic.
     */
    public void vibrate() {
        if (bluetoothGatt == null) {
            Log.e(TAG, "vibrate(): bluetoothGatt is null – not connected yet");
            return;
        }

        BluetoothGattService alertService = bluetoothGatt.getService(UUID.fromString(MiBandConstants.ALERT_SERVICE));
        if (alertService == null) {
            Log.e(TAG, "vibrate(): Alert Service not found");
            return;
        }

        BluetoothGattCharacteristic alertChar = alertService.getCharacteristic(UUID.fromString(MiBandConstants.ALERT_CHARACTERISTIC));
        if (alertChar == null) {
            Log.e(TAG, "vibrate(): Alert Characteristic not found");
            return;
        }

        alertChar.setValue(new byte[]{0x02});
        alertChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);

        handler.post(() -> {
            try {
                boolean success = bluetoothGatt.writeCharacteristic(alertChar);
                Log.d(TAG, "Vibrate command sent: " + success);
            } catch (SecurityException e) {
                Log.e(TAG, "SecurityException in vibrate(): " + e.getMessage());
            }
        });
    }

    // ─── Dashboard API ──────────────────────────────────────────────────────────

    /**
     * Reads the current step count from the band.
     */
    public void requestSteps() {
        if (bluetoothGatt == null) return;
        try {
            BluetoothGattService stepsService = bluetoothGatt.getService(UUID.fromString(MiBandConstants.STEPS_SERVICE));
            if (stepsService != null) {
                BluetoothGattCharacteristic stepChar = stepsService.getCharacteristic(UUID.fromString(MiBandConstants.STEPS_CHARACTERISTIC));
                if (stepChar != null) {
                    bluetoothGatt.readCharacteristic(stepChar);
                } else {
                    Log.e(TAG, "requestSteps: Steps characteristic not found");
                }
            } else {
                Log.e(TAG, "requestSteps: Steps service not found");
            }
        } catch (SecurityException e) {
            Log.e(TAG, "requestSteps SecurityException: " + e.getMessage());
        }
    }

    /**
     * Fetches last-night's sleep data from the Mi Band.
     *
     * How it works:
     *  1. Enable BLE notifications on the Activity Data characteristic (0x0005).
     *  2. Write a fetch command to the Activity Control Point (0x0004).
     *     The command encodes a start timestamp so the band only sends recent data.
     *  3. The band streams packets: each 4-byte packet contains the activity type
     *     (category byte) and intensity for 1-minute intervals.
     *  4. onCharacteristicChanged() below parses each packet and accumulates
     *     light/deep sleep minutes, then broadcasts ACTION_SLEEP_DATA.
     */
    public void requestSleepData() {
        if (bluetoothGatt == null) return;
        try {
            BluetoothGattService feeService = bluetoothGatt.getService(UUID.fromString(MiBandConstants.STEPS_SERVICE));
            if (feeService == null) {
                Log.e(TAG, "requestSleepData: FEE0 service not found");
                return;
            }

            BluetoothGattCharacteristic dataChar = feeService.getCharacteristic(
                    UUID.fromString(MiBandConstants.ACTIVITY_DATA_CHAR));
            BluetoothGattCharacteristic ctrlChar = feeService.getCharacteristic(
                    UUID.fromString(MiBandConstants.ACTIVITY_CONTROL_CHAR));

            if (dataChar == null || ctrlChar == null) {
                Log.e(TAG, "requestSleepData: Activity chars not found");
                return;
            }

            // Reset accumulators before a fresh fetch
            sleepLightMinutes = 0;
            sleepDeepMinutes  = 0;
            activeWorkoutType = 0;
            activeWorkoutDuration = 0;
            activeWorkoutSteps = 0;
            activeWorkoutHrSum = 0;
            activeWorkoutHrCount = 0;
            isFetchingActivity   = true;

            // Step 1 — enable Android-side + band-side notifications on Activity Data
            bluetoothGatt.setCharacteristicNotification(dataChar, true);
            BluetoothGattDescriptor desc = dataChar.getDescriptor(
                    UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"));
            if (desc != null) {
                desc.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                bluetoothGatt.writeDescriptor(desc);
            }

            // Step 2 — build & write the fetch command after the descriptor write settles
            // Command format: [0x01, 0x01, <4-byte little-endian Unix timestamp of ~8h ago>]
            handler.postDelayed(() -> {
                try {
                    long startTimeSec = (System.currentTimeMillis() / 1000L) - (10 * 60 * 60); // 10h ago
                    byte[] cmd = new byte[6];
                    cmd[0] = 0x01;
                    cmd[1] = 0x01;
                    cmd[2] = (byte) ( startTimeSec        & 0xFF);
                    cmd[3] = (byte) ((startTimeSec >>  8) & 0xFF);
                    cmd[4] = (byte) ((startTimeSec >> 16) & 0xFF);
                    cmd[5] = (byte) ((startTimeSec >> 24) & 0xFF);
                    ctrlChar.setValue(cmd);
                    ctrlChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                    boolean sent = bluetoothGatt.writeCharacteristic(ctrlChar);
                    Log.d(TAG, "Sleep fetch command sent: " + sent);
                } catch (SecurityException e) {
                    Log.e(TAG, "requestSleepData write SecurityException: " + e.getMessage());
                }
            }, 500);

        } catch (SecurityException e) {
            Log.e(TAG, "requestSleepData SecurityException: " + e.getMessage());
        }
    }

    /**
     * Subscribes to Heart Rate notifications PASSIVELY.
     * We ONLY enable BLE notifications — we never write to the HR control point
     * to trigger a measurement. This means the app will receive HR data ONLY
     * when the user manually takes a reading directly on the Mi Band itself.
     */
    private void subscribeHeartRateNotifications() {
        if (bluetoothGatt == null) return;
        try {
            BluetoothGattService hrService = bluetoothGatt.getService(UUID.fromString(MiBandConstants.HEART_RATE_SERVICE));
            if (hrService == null) {
                Log.w(TAG, "subscribeHeartRateNotifications: HR service not found");
                return;
            }

            BluetoothGattCharacteristic hrChar = hrService.getCharacteristic(UUID.fromString(MiBandConstants.HEART_RATE_CHAR));
            if (hrChar == null) {
                Log.w(TAG, "subscribeHeartRateNotifications: HR characteristic not found");
                return;
            }

            // Enable local notifications on the Android side
            bluetoothGatt.setCharacteristicNotification(hrChar, true);

            // Enable remote notifications on the band side via CCCD descriptor
            // This tells the band to PUSH data to us — but ONLY when it has a reading.
            // Because we never write [0x15, 0x01, 0x01] to the control point,
            // the band will NEVER start measuring on its own due to our app.
            BluetoothGattDescriptor desc = hrChar.getDescriptor(CCCD_UUID);
            if (desc != null) {
                desc.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                handler.postDelayed(() -> {
                    try {
                        bluetoothGatt.writeDescriptor(desc);
                        Log.d(TAG, "HR notifications subscribed (passive mode — band-initiated only)");
                    } catch (SecurityException e) {
                        Log.e(TAG, "SecurityException writing HR CCCD: " + e.getMessage());
                    }
                }, 1500); // Run after stopAllHeartRateMeasurements finishes (which takes ~1200ms)
            }
        } catch (SecurityException e) {
            Log.e(TAG, "subscribeHeartRateNotifications SecurityException: " + e.getMessage());
        }
    }

    /**
     * Fully stops all continuous, sleep, and manual heart rate measurements.
     */
    private void stopAllHeartRateMeasurements() {
        if (bluetoothGatt == null) return;
        try {
            BluetoothGattService hrService = bluetoothGatt.getService(UUID.fromString(MiBandConstants.HEART_RATE_SERVICE));
            if (hrService == null) return;

            BluetoothGattCharacteristic hrCtrl = hrService.getCharacteristic(UUID.fromString(MiBandConstants.HEART_RATE_CTRL_CHAR));
            if (hrCtrl != null) {
                // Determine write type (NO_RESPONSE vs DEFAULT). Usually Control Point uses normal write, but let's be safe.
                hrCtrl.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                
                // 1) Stop Sleep/Periodic/Activity monitoring: 0x15, 0x00, 0x00
                hrCtrl.setValue(new byte[]{0x15, 0x00, 0x00});
                bluetoothGatt.writeCharacteristic(hrCtrl);
                
                // 2) Stop Continuous measurement: 0x15, 0x01, 0x00 (Spaced to 600ms to allow BLE stack to clear)
                handler.postDelayed(() -> {
                    try {
                        hrCtrl.setValue(new byte[]{0x15, 0x01, 0x00});
                        bluetoothGatt.writeCharacteristic(hrCtrl);
                    } catch (SecurityException e) {
                        Log.e(TAG, "Error stopping continuous HR: " + e.getMessage());
                    }
                }, 600);

                // 3) Stop Manual measurement: 0x15, 0x02, 0x00 (Spaced to 1200ms)
                handler.postDelayed(() -> {
                    try {
                        hrCtrl.setValue(new byte[]{0x15, 0x02, 0x00});
                        bluetoothGatt.writeCharacteristic(hrCtrl);
                        Log.d(TAG, "Force stopped all active heart rate measurements on connect.");
                    } catch (SecurityException e) {
                        Log.e(TAG, "Error stopping manual HR: " + e.getMessage());
                    }
                }, 1200);
            }
        } catch (SecurityException e) {
            Log.e(TAG, "stopAllHeartRateMeasurements exception: " + e.getMessage());
        }
    }

    // ─── GATT Callback ──────────────────────────────────────────────────────────

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {

        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            super.onConnectionStateChange(gatt, status, newState);

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "Connected to Mi Band. Discovering services...");
                broadcast(ACTION_CONNECTION_STATE, STATE_CONNECTED);
                try {
                    // Small delay before discoverServices reduces GATT 133 errors
                    handler.postDelayed(() -> {
                        try {
                            gatt.discoverServices();
                        } catch (SecurityException e) {
                            Log.e(TAG, "SecurityException discoverServices(): " + e.getMessage());
                        }
                    }, 600);
                } catch (Exception e) {
                    Log.e(TAG, "Error scheduling discoverServices: " + e.getMessage());
                }

            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "Disconnected from Mi Band. Status code: " + status);
                broadcast(ACTION_CONNECTION_STATE, STATE_DISCONNECTED);
                authCharacteristic = null;

                try {
                    gatt.close();
                } catch (SecurityException e) {
                    Log.e(TAG, "SecurityException closing GATT: " + e.getMessage());
                }
                bluetoothGatt = null;
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            super.onServicesDiscovered(gatt, status);

            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "onServicesDiscovered failed, status: " + status);
                return;
            }

            Log.d(TAG, "Services discovered. Starting auth setup...");

            BluetoothGattService authService = gatt.getService(UUID.fromString(MiBandConstants.AUTH_SERVICE));
            if (authService == null) {
                Log.e(TAG, "Auth Service not found!");
                return;
            }

            authCharacteristic = authService.getCharacteristic(UUID.fromString(MiBandConstants.AUTH_CHARACTERISTIC));
            if (authCharacteristic == null) {
                Log.e(TAG, "Auth Characteristic not found!");
                return;
            }

            try {
                // Step 1: Enable local notifications for the auth characteristic
                gatt.setCharacteristicNotification(authCharacteristic, true);
                Log.d(TAG, "Notifications enabled for Auth Characteristic");

                // Step 2: Write to CCCD descriptor to enable remote notifications on the band
                BluetoothGattDescriptor descriptor = authCharacteristic.getDescriptor(CCCD_UUID);
                if (descriptor != null) {
                    descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    handler.post(() -> {
                        try {
                            boolean result = gatt.writeDescriptor(descriptor);
                            Log.d(TAG, "Wrote CCCD descriptor: " + result);
                            // Step 3: After descriptor write, we send auth init command
                            // (via onDescriptorWrite callback below)
                        } catch (SecurityException e) {
                            Log.e(TAG, "SecurityException writing CCCD: " + e.getMessage());
                        }
                    });
                }
            } catch (SecurityException e) {
                Log.e(TAG, "SecurityException in onServicesDiscovered: " + e.getMessage());
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            super.onDescriptorWrite(gatt, descriptor, status);
            Log.d(TAG, "onDescriptorWrite status: " + status + " for UUID: " + descriptor.getCharacteristic().getUuid());

            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (descriptor.getCharacteristic().getUuid().equals(UUID.fromString(MiBandConstants.AUTH_CHARACTERISTIC))) {
                    // Try to authenticate an already paired band first over Step 2
                    // (Sending Step 1 [0x01, 0x08] on an already paired band causes a factory reset of today's steps!)
                    sendAuthStep2(gatt);
                }
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            super.onCharacteristicWrite(gatt, characteristic, status);
            Log.d(TAG, "onCharacteristicWrite status: " + status
                    + " data: " + Arrays.toString(characteristic.getValue()));
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            super.onCharacteristicRead(gatt, characteristic, status);
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (characteristic.getUuid().toString().equals(MiBandConstants.STEPS_CHARACTERISTIC)) {
                    byte[] data = characteristic.getValue();
                    if (data != null && data.length >= 3) {
                        int steps = (data[1] & 0xff) | ((data[2] & 0xff) << 8);
                        double distanceKm = (steps * 0.767) / 1000.0;
                        int calories = (int) (steps * 0.04);
                        
                        Log.d(TAG, "Steps: " + steps + ", Dist: " + distanceKm + ", Cal: " + calories);
                        
                        Intent intent = new Intent(ACTION_STEPS_DATA);
                        intent.setPackage(getPackageName());
                        intent.putExtra(EXTRA_STEPS, steps);
                        intent.putExtra(EXTRA_DISTANCE, distanceKm);
                        intent.putExtra(EXTRA_CALORIES, calories);
                        sendBroadcast(intent);
                    }
                }
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            super.onCharacteristicChanged(gatt, characteristic);

            if (characteristic.getUuid().toString().equals(MiBandConstants.HEART_RATE_CHAR)) {
                byte[] data = characteristic.getValue();
                if (data != null && data.length >= 2) {
                    int hrValue = data[1] & 0xFF; // typically byte 1
                    Log.d(TAG, "Heart Rate: " + hrValue + " bpm");
                    
                    Intent intent = new Intent(ACTION_HEART_RATE_DATA);
                    intent.setPackage(getPackageName());
                    intent.putExtra(EXTRA_HEART_RATE, hrValue);
                    sendBroadcast(intent);
                }

                return;
            }

            // ─── ACTIVITY / SLEEP DATA PACKETS ────────────────────────────────
            // Mi Band 3 streams 4-byte packets per minute of activity data.
            // Packet layout: [category, intensity, steps, heartrate]
            //   category: 0x00 = awake/not worn, 0x01 = light sleep, 0x04 = deep sleep
            //             0x80 = activity start marker, 0xFF = end-of-data marker
            if (isFetchingActivity && characteristic.getUuid().toString().equals(MiBandConstants.ACTIVITY_DATA_CHAR)) {
                byte[] data = characteristic.getValue();
                if (data == null) return;

                for (int i = 0; i + 3 < data.length; i += 4) {
                    int category = data[i] & 0xFF;
                    int intensity = data[i + 1] & 0xFF;
                    int stepsPerMin = data[i + 2] & 0xFF;
                    int hrPerMin = data[i + 3] & 0xFF;

                    if (category == 0xFF) {
                        // End-of-transfer marker — broadcast final totals
                        Log.d(TAG, "Activity fetch complete. Light=" + sleepLightMinutes + "m Deep=" + sleepDeepMinutes + "m");
                        broadcastSleepData();

                        // Flush any pending workout
                        if (activeWorkoutType > 0 && activeWorkoutDuration > 1) {
                            broadcastWorkout(activeWorkoutType, activeWorkoutDuration, activeWorkoutSteps, activeWorkoutHrSum, activeWorkoutHrCount);
                        }

                        isFetchingActivity = false;
                        return;
                    } 
                    
                    // Sleep tracking
                    if (category == 0x01) { sleepLightMinutes++; } 
                    else if (category == 0x04) { sleepDeepMinutes++; }

                    // Workout tracking (0x11 often Treadmill, 0x12 often Free Exercise, 0x1A etc.)
                    // Sometimes Mi Band uses a generic category (e.g. 0x10) and high intensity denotes exercise.
                    boolean isWorkoutCategory = (category == 0x11 || category == 0x12 || category == 0x1A || category == 0x10);
                    
                    if (isWorkoutCategory) {
                        if (activeWorkoutType == 0) {
                            activeWorkoutType = category; // Start new workout
                        }
                        // Accumulate
                        activeWorkoutDuration++;
                        activeWorkoutSteps += stepsPerMin;
                        if (hrPerMin > 0) {
                            activeWorkoutHrSum += hrPerMin;
                            activeWorkoutHrCount++;
                        }
                    } else {
                        // Sequence broken, finish previous workout if valid (> 3 mins)
                        if (activeWorkoutType > 0) {
                            if (activeWorkoutDuration > 3) {
                                broadcastWorkout(activeWorkoutType, activeWorkoutDuration, activeWorkoutSteps, activeWorkoutHrSum, activeWorkoutHrCount);
                            }
                            // Reset for next potential workout
                            activeWorkoutType = 0;
                            activeWorkoutDuration = 0;
                            activeWorkoutSteps = 0;
                            activeWorkoutHrSum = 0;
                            activeWorkoutHrCount = 0;
                        }
                    }
                }
                return;
            }

            if (!characteristic.getUuid().equals(UUID.fromString(MiBandConstants.AUTH_CHARACTERISTIC))) {
                return;
            }

            byte[] data = characteristic.getValue();
            if (data == null || data.length < 3) return;

            // ─── BAND RESPONSE: 0x10, 0x01, 0x01 or 0x10, 0x01, 0x04 ────────
            // Key validated — band accepted the key. Now request random number.
            if ((data[0] == 0x10 && data[1] == 0x01 && data[2] == 0x01) || 
                (data[0] == 0x10 && data[1] == 0x01 && data[2] == 0x04)) {
                Log.d(TAG, "✓ Key recognized. Requesting random number...");
                sendAuthStep2(gatt);
            }

            // ─── BAND RESPONSE: 0x10, 0x02, 0x01 ─────────────────────────────
            // Band sent 16-byte challenge → encrypt it and send back
            else if (data[0] == 0x10 && data[1] == 0x02 && data[2] == 0x01) {
                if (data.length < 19) {
                    Log.e(TAG, "Challenge response too short: " + data.length + " bytes (need 19)");
                    return;
                }
                Log.d(TAG, "✓ Challenge received. Encrypting and responding...");
                byte[] challenge = new byte[16];
                System.arraycopy(data, 3, challenge, 0, 16);
                sendAuthStep3(gatt, challenge);
            }

            // ─── BAND RESPONSE: 0x10, 0x03, 0x01 ─────────────────────────────
            // Authentication successful!
            else if (data[0] == 0x10 && data[1] == 0x03 && data[2] == 0x01) {
                Log.d(TAG, "✓✓✓ AUTHENTICATION SUCCESSFUL! Mi Band 3 is connected.");

                // Step A: Kill ALL active HR modes
                stopAllHeartRateMeasurements();

                // Step B: Subscribe to HR notifications passively
                subscribeHeartRateNotifications();

                // Step C: Fetch last night's sleep data (delayed so HR setup has settled)
                handler.postDelayed(() -> requestSleepData(), 2500);
                
                // Step D: Request initial steps
                handler.postDelayed(() -> requestSteps(), 3500);

                broadcast(ACTION_AUTH_COMPLETE, STATE_AUTHENTICATED);
            }

            // ─── BAND RESPONSE: 0x10, 0x02, 0x04 / 0x0F ──────────────────────
            // Error requesting random number. Means we are NOT paired properly.
            // Fall back to Step 1 (New Pair).
            else if (data[0] == 0x10 && data[1] == 0x02 && (data[2] == 0x04 || data[2] == 0x08 || data[2] == 0x0F)) {
                Log.d(TAG, "Not paired. Falling back to Auth Step 1 (New Pair)...");
                sendAuthStep1(gatt);
            }

            // ─── AUTH FAILED ───────────────────────────────────────────────────
            else if (data[0] == 0x10 && data[1] == 0x03 && data[2] == 0x04) {
                Log.e(TAG, "✗ Authentication FAILED. Wrong key?");
                broadcast(ACTION_AUTH_COMPLETE, STATE_AUTH_FAILED);
            }
        }
    };

    // ─── Auth Steps ─────────────────────────────────────────────────────────────

    /**
     * AUTH STEP 1: Send [0x01, 0x08] + 16-byte key.
     * This proves we have the key immediately, suppressing the "Tap to Pair" prompt.
     */
    private void sendAuthStep1(BluetoothGatt gatt) {
        if (authCharacteristic == null) return;

        Log.d(TAG, "Auth key hex  : " + MiBandAuthenticator.AUTH_KEY_HEX);
        byte[] authKey = MiBandConstants.hexStringToByteArray(MiBandAuthenticator.AUTH_KEY_HEX);
        Log.d(TAG, "Auth key bytes: " + authKey.length + " bytes → " + Arrays.toString(authKey));

        if (authKey.length != 16) {
            Log.e(TAG, "Auth key wrong length (" + authKey.length + "). Aborting.");
            return;
        }

        // [0x01, 0x08] followed by 16 bytes of the secret key
        byte[] payload = new byte[18];
        payload[0] = 0x01;
        payload[1] = 0x08;
        System.arraycopy(authKey, 0, payload, 2, 16);

        writeAuthCharacteristic(gatt, payload);
        Log.d(TAG, "Auth Step 1: Sent key authentication command [0x01, 0x08, ...]");
    }

    /**
     * AUTH STEP 2: Send [0x02, 0x08] to request a random number (challenge) from the band.
     */
    private void sendAuthStep2(BluetoothGatt gatt) {
        if (authCharacteristic == null) return;

        byte[] payload = new byte[]{0x02, 0x08}; // 0x08 implies AES encrypted flow
        writeAuthCharacteristic(gatt, payload);
        Log.d(TAG, "Auth Step 2: Requested random number [0x02, 0x08]");
    }



    /**
     * AUTH STEP 3: Encrypt the challenge and send [0x03, 0x08] + 16 encrypted bytes.
     */
    private void sendAuthStep3(BluetoothGatt gatt, byte[] challenge) {
        if (authCharacteristic == null) return;

        byte[] authKey = MiBandConstants.hexStringToByteArray(MiBandAuthenticator.AUTH_KEY_HEX);
        byte[] encryptedResponse = MiBandAuthenticator.handleChallenge(authKey, challenge);

        if (encryptedResponse == null) {
            Log.e(TAG, "Auth Step 3: Encryption failed!");
            return;
        }

        writeAuthCharacteristic(gatt, encryptedResponse);
        Log.d(TAG, "Auth Step 3: Sent encrypted challenge response");
    }

    /**
     * Helper that sets the value on the auth characteristic and triggers a write.
     */
    private void writeAuthCharacteristic(BluetoothGatt gatt, byte[] value) {
        if (authCharacteristic == null || gatt == null) return;

        authCharacteristic.setValue(value);
        // Mi Band 3 auth characteristic only supports WRITE_NO_RESPONSE (PROPERTY_WRITE_NO_RESPONSE).
        // Using WRITE_TYPE_DEFAULT returns GATT status 6 (REQUEST_NOT_SUPPORTED) and causes
        // the band to immediately terminate the connection with HCI error 19.
        authCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);

        handler.post(() -> {
            try {
                boolean sent = gatt.writeCharacteristic(authCharacteristic);
                Log.d(TAG, "writeAuthCharacteristic sent=" + sent
                        + " payload=" + Arrays.toString(value));
            } catch (SecurityException e) {
                Log.e(TAG, "SecurityException writeAuthCharacteristic: " + e.getMessage());
            }
        });
    }

    // ─── Broadcast Helper ────────────────────────────────────────────────────────

    private void broadcast(String action, String state) {
        Intent intent = new Intent(action);
        intent.setPackage(getPackageName());
        intent.putExtra(EXTRA_STATE, state);
        sendBroadcast(intent);
    }

    /**
     * Broadcasts the accumulated sleep totals to the Dashboard.
     * Called when the Mi Band signals end-of-activity-data (0xFF packet).
     */
    private void broadcastSleepData() {
        int totalMin = sleepLightMinutes + sleepDeepMinutes;
        Intent intent = new Intent(ACTION_SLEEP_DATA);
        intent.setPackage(getPackageName());
        intent.putExtra(EXTRA_SLEEP_TOTAL_MIN,  totalMin);
        intent.putExtra(EXTRA_SLEEP_LIGHT_MIN,  sleepLightMinutes);
        intent.putExtra(EXTRA_SLEEP_DEEP_MIN,   sleepDeepMinutes);
        sendBroadcast(intent);
        Log.d(TAG, "Sleep broadcast: total=" + totalMin + "min light=" + sleepLightMinutes + " deep=" + sleepDeepMinutes);
    }

    private void broadcastWorkout(int category, int duration, int steps, int hrSum, int hrCount) {
        String type = "Exercise";
        if (category == 0x11) type = "Treadmill";
        
        int avgHr = hrCount > 0 ? (hrSum / hrCount) : 0;
        int calories = (int) (steps * 0.05); // Rough caloric estimate for active workout

        Intent intent = new Intent(ACTION_WORKOUT_DATA);
        intent.setPackage(getPackageName());
        intent.putExtra(EXTRA_WORKOUT_TYPE, type);
        intent.putExtra(EXTRA_WORKOUT_DURATION, duration);
        intent.putExtra(EXTRA_WORKOUT_AVG_HR, avgHr);
        intent.putExtra(EXTRA_WORKOUT_STEPS, steps);
        intent.putExtra(EXTRA_WORKOUT_CALORIES, calories);
        sendBroadcast(intent);
        
        Log.d(TAG, "Workout broadcast: " + type + " duration=" + duration + " steps=" + steps + " avgHR=" + avgHr);
    }
}
