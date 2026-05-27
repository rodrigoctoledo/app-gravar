package com.loopcam;

import android.content.Context;
import android.hardware.camera2.CameraManager;
import android.util.Log;

import java.util.Calendar;

public class LEDController {

    private static final String TAG = "LoopCam";
    private final CameraManager cameraManager;
    private String cameraId;
    private volatile boolean ledOn = false;

    public LEDController(Context context) {
        this.cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        try {
            if (cameraManager != null) {
                String[] cameraIds = cameraManager.getCameraIdList();
                if (cameraIds.length > 0) {
                    cameraId = cameraIds[0];
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting camera ID", e);
        }
    }

    /**
     * Check if current time is between 00:00 and 05:00
     */
    private boolean isNightHours() {
        Calendar calendar = Calendar.getInstance();
        int hour = calendar.get(Calendar.HOUR_OF_DAY);
        return hour >= 0 && hour < 5;
    }

    /**
     * Turn LED on (night hours mode)
     */
    public void setLEDOn() {
        if (cameraId == null || ledOn) return;
        try {
            cameraManager.setTorchMode(cameraId, true);
            ledOn = true;
            Log.d(TAG, "LED turned ON (night mode)");
        } catch (Exception e) {
            Log.e(TAG, "Error turning LED on", e);
        }
    }

    /**
     * Turn LED off
     */
    public void setLEDOff() {
        if (cameraId == null || !ledOn) return;
        try {
            cameraManager.setTorchMode(cameraId, false);
            ledOn = false;
            Log.d(TAG, "LED turned OFF");
        } catch (Exception e) {
            Log.e(TAG, "Error turning LED off", e);
        }
    }

    /**
     * Flash LED 4 times (motion detected)
     * If night hours, LED stays on after flashing
     */
    public void flashLED() {
        new Thread(() -> {
            try {
                boolean isNight = isNightHours();
                boolean originalState = ledOn;

                // Flash 4 times
                for (int i = 0; i < 4; i++) {
                    cameraManager.setTorchMode(cameraId, false);
                    Thread.sleep(100);
                    cameraManager.setTorchMode(cameraId, true);
                    Thread.sleep(100);
                }

                // Return to appropriate state
                if (isNight) {
                    cameraManager.setTorchMode(cameraId, true);
                    ledOn = true;
                } else {
                    cameraManager.setTorchMode(cameraId, false);
                    ledOn = false;
                }

                Log.d(TAG, "LED flashed (motion detected)");
            } catch (Exception e) {
                Log.e(TAG, "Error flashing LED", e);
            }
        }).start();
    }

    /**
     * Update LED state based on time of day
     * Should be called periodically or when time changes
     */
    public void updateLEDState() {
        if (isNightHours()) {
            setLEDOn();
        } else {
            setLEDOff();
        }
    }

    /**
     * Clean up resources
     */
    public void release() {
        setLEDOff();
    }
}
