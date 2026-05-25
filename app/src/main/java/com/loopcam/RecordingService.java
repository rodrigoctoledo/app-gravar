package com.loopcam;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.StatFs;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.camera.core.CameraSelector;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.FileOutputOptions;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RecordingService extends Service implements LifecycleOwner {

    private static final String TAG = "LoopCam";
    private static final String CHANNEL_ID = "loopcam_channel";
    private static final int NOTIFICATION_ID = 1001;

    // Segment duration: 1 minute
    private static final long RECORDING_SEGMENT_MS = 60 * 1000L;
    // Minimum free space to keep before deleting oldest file
    private static final long MIN_FREE_SPACE_BYTES = 500L * 1024 * 1024;
    // Storage check interval
    private static final long STORAGE_CHECK_INTERVAL_MS = 15 * 1000L;

    public static final String ACTION_START        = "com.loopcam.ACTION_START";
    public static final String ACTION_STOP         = "com.loopcam.ACTION_STOP";
    public static final String ACTION_STATUS_UPDATE= "com.loopcam.STATUS_UPDATE";
    public static final String EXTRA_IS_RECORDING  = "is_recording";
    public static final String EXTRA_CURRENT_FILE  = "current_file";
    public static final String EXTRA_ELAPSED_SECONDS = "elapsed_seconds";
    public static final String EXTRA_FILE_COUNT    = "file_count";

    /** Read by MainActivity.onResume() to sync UI without a broadcast race */
    public static volatile boolean isRunning = false;

    // BUG FIX 5: LifecycleRegistry must only be touched on the main thread
    private LifecycleRegistry lifecycleRegistry;
    private ProcessCameraProvider cameraProvider;
    private VideoCapture<Recorder> videoCapture;
    private Recording activeRecording;
    private ExecutorService cameraExecutor;
    private Handler mainHandler;

    private Timer segmentTimer;
    private Timer storageTimer;
    private Timer statusTimer;

    private long recordingStartTime;
    private String currentFileName = "";
    private int fileCount;
    private File outputDir;

    private volatile boolean keepLooping = false;

    // ─── Lifecycle ─────────────────────────────────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();
        // Must be created on the main thread
        lifecycleRegistry = new LifecycleRegistry(this);
        lifecycleRegistry.setCurrentState(Lifecycle.State.CREATED);

        cameraExecutor = Executors.newSingleThreadExecutor();
        mainHandler    = new Handler(Looper.getMainLooper());

        outputDir = new File(getExternalFilesDir(null), "LoopCam");
        if (!outputDir.exists()) outputDir.mkdirs();

        File[] existing = outputDir.listFiles();
        fileCount = (existing != null) ? existing.length : 0;

        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // intent can be null when the system restarts a START_STICKY service
        String action = (intent != null) ? intent.getAction() : null;

        if (ACTION_STOP.equals(action)) {
            stopEverything();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        // ACTION_START or null (system restart) — begin recording
        if (!isRunning) {
            isRunning    = true;
            keepLooping  = true;
            startForegroundNotification();
            // Move lifecycle state to RESUMED on main thread
            mainHandler.post(() -> {
                lifecycleRegistry.setCurrentState(Lifecycle.State.STARTED);
                lifecycleRegistry.setCurrentState(Lifecycle.State.RESUMED);
                initCamera();
            });
            startStorageMonitor();
            startStatusBroadcast();
        }

        return START_STICKY;
    }

    // ─── Camera setup ──────────────────────────────────────────────────────

    private void initCamera() {
        ProcessCameraProvider.getInstance(this).addListener(() -> {
            try {
                cameraProvider = ProcessCameraProvider.getInstance(this).get();

                cameraProvider.unbindAll();

                QualitySelector qualitySelector = QualitySelector.fromOrderedList(
                    Arrays.asList(Quality.UHD, Quality.FHD, Quality.HD, Quality.SD)
                );

                Recorder recorder = new Recorder.Builder()
                    .setExecutor(cameraExecutor)
                    .setQualitySelector(qualitySelector)
                    .build();

                videoCapture = VideoCapture.withOutput(recorder);

                cameraProvider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, videoCapture);

                startNewSegment();

            } catch (Exception e) {
                Log.e(TAG, "Camera init failed", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    // ─── Segment logic ─────────────────────────────────────────────────────

    /**
     * Starts a new 1-minute segment. When the Finalize event arrives the next
     * segment is started immediately — zero intentional gap.
     */
    private void startNewSegment() {
        if (!keepLooping || videoCapture == null) return;

        String timestamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault())
            .format(new Date());
        currentFileName  = "VID_" + timestamp + ".mp4";
        File outputFile  = new File(outputDir, currentFileName);

        FileOutputOptions options = new FileOutputOptions.Builder(outputFile).build();

        try {
            activeRecording = videoCapture.getOutput()
                .prepareRecording(this, options)
                .withAudioEnabled()
                .start(ContextCompat.getMainExecutor(this), event -> {

                    if (event instanceof VideoRecordEvent.Start) {
                        recordingStartTime = System.currentTimeMillis();
                        Log.d(TAG, "Segment started: " + currentFileName);
                        updateNotification("Gravando: " + currentFileName);
                        scheduleSegmentStop();

                    } else if (event instanceof VideoRecordEvent.Finalize) {
                        VideoRecordEvent.Finalize fin = (VideoRecordEvent.Finalize) event;
                        if (!fin.hasError()) {
                            fileCount++;
                            Log.d(TAG, "Segment saved: " + currentFileName
                                + " | total=" + fileCount);
                        } else {
                            Log.e(TAG, "Finalize error=" + fin.getError());
                        }
                        activeRecording = null;

                        // Immediately begin next segment if still looping
                        if (keepLooping) {
                            mainHandler.post(this::startNewSegment);
                        }
                    }
                });

        } catch (Exception e) {
            Log.e(TAG, "prepareRecording failed", e);
            if (keepLooping) {
                // Short retry delay — don't spin-loop on a hard error
                mainHandler.postDelayed(this::startNewSegment, 500);
            }
        }
    }

    /** Schedules `.stop()` on the active recording after 1 minute. */
    private void scheduleSegmentStop() {
        cancelSegmentTimer();
        segmentTimer = new Timer("SegmentStop", true);
        segmentTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                mainHandler.post(() -> {
                    if (activeRecording != null && keepLooping) {
                        Log.d(TAG, "1-min mark — stopping segment");
                        activeRecording.stop();
                        // Next segment starts inside the Finalize callback
                    }
                });
            }
        }, RECORDING_SEGMENT_MS);
    }

    private void cancelSegmentTimer() {
        if (segmentTimer != null) {
            segmentTimer.cancel();
            segmentTimer = null;
        }
    }

    // ─── Storage monitor ───────────────────────────────────────────────────

    private void startStorageMonitor() {
        storageTimer = new Timer("StorageMonitor", true);
        storageTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() { checkAndFreeStorage(); }
        }, STORAGE_CHECK_INTERVAL_MS, STORAGE_CHECK_INTERVAL_MS);
    }

    private void checkAndFreeStorage() {
        try {
            StatFs stat = new StatFs(outputDir.getPath());
            long available = stat.getAvailableBytes();
            if (available < MIN_FREE_SPACE_BYTES) {
                Log.w(TAG, "Low storage (" + (available / 1024 / 1024) + " MB) — deleting oldest");
                deleteOldestRecording();
            }
        } catch (Exception e) {
            Log.e(TAG, "Storage check failed", e);
        }
    }

    private void deleteOldestRecording() {
        File[] files = outputDir.listFiles(f -> f.getName().endsWith(".mp4"));
        if (files == null || files.length == 0) return;

        Arrays.sort(files, Comparator.comparingLong(File::lastModified));

        for (File file : files) {
            // Never delete the segment currently being written
            if (!file.getName().equals(currentFileName)) {
                long mb = file.length() / 1024 / 1024;
                if (file.delete()) {
                    fileCount = Math.max(0, fileCount - 1);
                    Log.i(TAG, "Deleted " + file.getName() + " (" + mb + " MB freed)");
                    break; // one at a time; monitor runs again in 15 s
                }
            }
        }
    }

    // ─── Status broadcast ──────────────────────────────────────────────────

    private void startStatusBroadcast() {
        statusTimer = new Timer("StatusBroadcast", true);
        statusTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() { broadcastStatus(); }
        }, 0, 1000);
    }

    private void broadcastStatus() {
        long elapsed = (recordingStartTime > 0)
            ? (System.currentTimeMillis() - recordingStartTime) / 1000L : 0L;

        Intent intent = new Intent(ACTION_STATUS_UPDATE);
        intent.putExtra(EXTRA_IS_RECORDING,   isRunning && activeRecording != null);
        intent.putExtra(EXTRA_CURRENT_FILE,   currentFileName);
        intent.putExtra(EXTRA_ELAPSED_SECONDS, elapsed);
        intent.putExtra(EXTRA_FILE_COUNT,     fileCount);
        sendBroadcast(intent);
    }

    // ─── Notification ──────────────────────────────────────────────────────

    private void startForegroundNotification() {
        startForeground(NOTIFICATION_ID, buildNotification("Gravação em loop ativa"));
    }

    private void updateNotification(String text) {
        NotificationManager nm =
            (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification(text));
    }

    private Notification buildNotification(String text) {
        Intent notifIntent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, notifIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("LoopCam")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "LoopCam Gravação", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Gravação em loop em background");
            ch.setShowBadge(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    // ─── Stop everything ───────────────────────────────────────────────────

    private void stopEverything() {
        isRunning   = false;
        keepLooping = false;

        cancelSegmentTimer();
        if (storageTimer != null) { storageTimer.cancel(); storageTimer = null; }
        if (statusTimer  != null) { statusTimer.cancel();  statusTimer  = null; }

        if (activeRecording != null) {
            activeRecording.stop();
            activeRecording = null;
        }
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }

        // BUG FIX 5 (cont.): lifecycle state must be set on main thread
        mainHandler.post(() ->
            lifecycleRegistry.setCurrentState(Lifecycle.State.DESTROYED));
    }

    // ─── Service boilerplate ───────────────────────────────────────────────

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        stopEverything();
        cameraExecutor.shutdown();
        super.onDestroy();
    }

    @Override
    public Lifecycle getLifecycle() { return lifecycleRegistry; }
}
