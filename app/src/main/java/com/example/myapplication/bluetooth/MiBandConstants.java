package com.example.myapplication.bluetooth;

public class MiBandConstants {

    public static final String AUTH_SERVICE = "0000fee1-0000-1000-8000-00805f9b34fb";
    public static final String AUTH_CHARACTERISTIC = "00000009-0000-3512-2118-0009af100700";
    
    public static final String ALERT_SERVICE = "00001802-0000-1000-8000-00805f9b34fb";
    public static final String ALERT_CHARACTERISTIC = "00002a06-0000-1000-8000-00805f9b34fb";
    
    public static final String HEART_RATE_SERVICE = "0000180d-0000-1000-8000-00805f9b34fb";
    public static final String HEART_RATE_CHAR = "00002a37-0000-1000-8000-00805f9b34fb";
    public static final String HEART_RATE_CTRL_CHAR = "00002a39-0000-1000-8000-00805f9b34fb";

    public static final String STEPS_SERVICE = "0000fee0-0000-1000-8000-00805f9b34fb";
    public static final String STEPS_CHARACTERISTIC = "00000007-0000-3512-2118-0009af100700";

    // Activity / Sleep data fetch (also on FEE0 service)
    // Control Point: write fetch command here to trigger data transfer
    // Data:          band streams activity packets here via notifications
    public static final String ACTIVITY_CONTROL_CHAR = "00000004-0000-3512-2118-0009af100700";
    public static final String ACTIVITY_DATA_CHAR    = "00000005-0000-3512-2118-0009af100700";

    /**
     * Helper method to convert a Hex String to a Byte Array.
     * 
     * @param s Hex String to be converted
     * @return Byte Array representation of the Hex String
     */
    public static byte[] hexStringToByteArray(String s) {
        if (s == null || s.isEmpty()) {
            return new byte[0];
        }

        // Strip spaces, colons, and dashes (e.g. "e8:a9" or "e8-a9" or "e8 a9" all become "e8a9")
        s = s.replaceAll("[\\s:\\-]", "").toUpperCase();

        // A valid hex string must have an even number of characters
        if (s.length() % 2 != 0) {
            throw new IllegalArgumentException(
                    "hexStringToByteArray: odd-length hex string (" + s.length() + " chars). "
                            + "Each byte needs exactly 2 hex digits.");
        }

        // Validate every character is a valid hex digit
        if (!s.matches("[0-9A-F]+")) {
            throw new IllegalArgumentException(
                    "hexStringToByteArray: string contains non-hex characters: \"" + s + "\"");
        }

        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                    + Character.digit(s.charAt(i + 1), 16));
        }
        return data;
    }
}
