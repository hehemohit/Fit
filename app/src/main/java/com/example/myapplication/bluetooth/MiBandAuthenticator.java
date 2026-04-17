package com.example.myapplication.bluetooth;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

public class MiBandAuthenticator {

    // The auth key you provided
    public static final String AUTH_KEY_HEX = "b00f4c0184f86b5ca96f03a71e60682a";

    /**
     * Handles the Mi Band 3 authentication challenge.
     * 
     * @param authKey   The 16-byte authentication key
     * @param challenge The 16-byte challenge received from the band
     * @return A byte array starting with 0x03, 0x08 followed by the 16 bytes of the encrypted challenge
     */
    public static byte[] handleChallenge(byte[] authKey, byte[] challenge) {
        try {
            // Setup AES/ECB/NoPadding cipher
            Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
            SecretKeySpec keySpec = new SecretKeySpec(authKey, "AES");
            cipher.init(Cipher.ENCRYPT_MODE, keySpec);
            
            // Encrypt the challenge
            byte[] encrypted = cipher.doFinal(challenge);
            
            // Prepare the final result: 0x03, 0x08 prefix + 16 encrypted bytes
            byte[] result = new byte[encrypted.length + 2];
            result[0] = 0x03;
            result[1] = 0x08;
            
            // Copy encrypted bytes into the result array starting at index 2
            System.arraycopy(encrypted, 0, result, 2, encrypted.length);
            
            return result;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}
