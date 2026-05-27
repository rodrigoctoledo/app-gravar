package com.loopcam;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;

import java.util.Timer;
import java.util.TimerTask;

public class MotionDetector {

    private static final String TAG = "LoopCam";
    private MotionListener listener;
    private volatile boolean isRunning = false;
    private Bitmap previousFrame;
    private Timer detectionTimer;

    public interface MotionListener {
        void onPersonDetected();
    }

    public MotionDetector(Context context, MotionListener listener) {
        this.listener = listener;
    }

    /**
     * Simple motion detection by comparing frames
     * Calculates pixel difference between frames
     */
    public void detectMotion(Bitmap currentFrame) {
        if (!isRunning || currentFrame == null) return;

        try {
            if (previousFrame != null) {
                float difference = calculateFrameDifference(previousFrame, currentFrame);

                // If 15% or more of pixels changed, consider it motion
                if (difference > 0.15f) {
                    if (listener != null) {
                        listener.onPersonDetected();
                    }
                    Log.d(TAG, "Motion detected: " + (difference * 100) + "% difference");
                }
            }

            previousFrame = currentFrame.copy(currentFrame.getConfig(), true);
        } catch (Exception e) {
            Log.e(TAG, "Error detecting motion", e);
        }
    }

    /**
     * Calculate pixel difference ratio between two frames
     */
    private float calculateFrameDifference(Bitmap frame1, Bitmap frame2) {
        if (frame1.getWidth() != frame2.getWidth() || frame1.getHeight() != frame2.getHeight()) {
            return 0;
        }

        int width = frame1.getWidth();
        int height = frame1.getHeight();
        int[] pixels1 = new int[width * height];
        int[] pixels2 = new int[width * height];

        frame1.getPixels(pixels1, 0, width, 0, 0, width, height);
        frame2.getPixels(pixels2, 0, width, 0, 0, width, height);

        int diffPixels = 0;
        for (int i = 0; i < pixels1.length; i++) {
            if (pixels1[i] != pixels2[i]) {
                diffPixels++;
            }
        }

        return (float) diffPixels / pixels1.length;
    }

    public void start() {
        isRunning = true;
        Log.d(TAG, "Motion detector started");
    }

    public void stop() {
        isRunning = false;
        Log.d(TAG, "Motion detector stopped");
    }

    public void release() {
        stop();
        if (previousFrame != null) {
            previousFrame.recycle();
            previousFrame = null;
        }
    }
}
