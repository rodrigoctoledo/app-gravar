package com.loopcam;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.StatFs;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private TextView tvStatus, tvCurrentFile, tvStorageInfo, tvRecordingCount, tvTimer;
    private MaterialButton btnToggle;
    private boolean isRecording = false;
    private BroadcastReceiver statusReceiver;

    private final ActivityResultLauncher<String[]> permissionLauncher =
        registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
            boolean allGranted = true;
            for (Boolean granted : result.values()) {
                if (!granted) { allGranted = false; break; }
            }
            if (allGranted) {
                startRecordingService();
            } else {
                Toast.makeText(this, "Permissões necessárias não concedidas", Toast.LENGTH_LONG).show();
            }
        });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvStatus        = findViewById(R.id.tvStatus);
        tvCurrentFile   = findViewById(R.id.tvCurrentFile);
        tvStorageInfo   = findViewById(R.id.tvStorageInfo);
        tvRecordingCount= findViewById(R.id.tvRecordingCount);
        tvTimer         = findViewById(R.id.tvTimer);
        btnToggle       = findViewById(R.id.btnToggle);

        btnToggle.setOnClickListener(v -> toggleRecording());

        setupBroadcastReceiver();
        updateStorageInfo();
    }

    // ─── Broadcast receiver ────────────────────────────────────────────────

    private void setupBroadcastReceiver() {
        statusReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!RecordingService.ACTION_STATUS_UPDATE.equals(intent.getAction())) return;

                boolean recording    = intent.getBooleanExtra(RecordingService.EXTRA_IS_RECORDING, false);
                String  currentFile  = intent.getStringExtra(RecordingService.EXTRA_CURRENT_FILE);
                long    elapsed      = intent.getLongExtra(RecordingService.EXTRA_ELAPSED_SECONDS, 0);
                int     count        = intent.getIntExtra(RecordingService.EXTRA_FILE_COUNT, 0);

                updateUI(recording, currentFile, elapsed, count);
                updateStorageInfo();
            }
        };

        IntentFilter filter = new IntentFilter(RecordingService.ACTION_STATUS_UPDATE);
        // BUG FIX 1: Android 11 (API 30) doesn't have RECEIVER_NOT_EXPORTED — guard correctly
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // API 33+
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(statusReceiver, filter);
        }
    }

    // ─── Recording control ─────────────────────────────────────────────────

    private void toggleRecording() {
        if (isRecording) {
            stopRecordingService();
        } else {
            checkPermissionsAndStart();
        }
    }

    private void checkPermissionsAndStart() {
        List<String> needed = new ArrayList<>();

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.CAMERA);
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.RECORD_AUDIO);
        }
        // Request storage write permission for all Android versions
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }

        if (needed.isEmpty()) {
            startRecordingService();
        } else {
            permissionLauncher.launch(needed.toArray(new String[0]));
        }
    }

    private void startRecordingService() {
        Intent intent = new Intent(this, RecordingService.class);
        intent.setAction(RecordingService.ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        // Optimistic UI update — broadcast will confirm shortly
        isRecording = true;
        tvStatus.setText("● GRAVANDO");
        // BUG FIX 2: getColor(int) requires API 23+; use ContextCompat for safety
        tvStatus.setTextColor(ContextCompat.getColor(this, R.color.recording_red));
        btnToggle.setText("PARAR GRAVAÇÃO");
    }

    private void stopRecordingService() {
        Intent intent = new Intent(this, RecordingService.class);
        intent.setAction(RecordingService.ACTION_STOP);
        startService(intent);
        isRecording = false;
        tvStatus.setText("○ PARADO");
        tvStatus.setTextColor(ContextCompat.getColor(this, R.color.stopped_gray));
        btnToggle.setText("INICIAR GRAVAÇÃO");
        tvCurrentFile.setText("Nenhuma gravação ativa");
        tvTimer.setText("00:00:00");
    }

    // ─── UI updates ────────────────────────────────────────────────────────

    private void updateUI(boolean recording, String currentFile, long elapsedSeconds, int fileCount) {
        // BUG FIX 3: callback may arrive on non-UI thread from the broadcast;
        // runOnUiThread is correct here, but also guard isRecording on UI thread only
        runOnUiThread(() -> {
            isRecording = recording;
            if (recording) {
                tvStatus.setText("● GRAVANDO");
                tvStatus.setTextColor(ContextCompat.getColor(this, R.color.recording_red));
                btnToggle.setText("PARAR GRAVAÇÃO");
                if (currentFile != null && !currentFile.isEmpty()) {
                    tvCurrentFile.setText(currentFile);
                }
                long h = elapsedSeconds / 3600;
                long m = (elapsedSeconds % 3600) / 60;
                long s = elapsedSeconds % 60;
                tvTimer.setText(String.format(Locale.getDefault(), "%02d:%02d:%02d", h, m, s));
            } else {
                tvStatus.setText("○ PARADO");
                tvStatus.setTextColor(ContextCompat.getColor(this, R.color.stopped_gray));
                btnToggle.setText("INICIAR GRAVAÇÃO");
                tvTimer.setText("00:00:00");
            }
            tvRecordingCount.setText("Arquivos gravados: " + fileCount);
        });
    }

    private void updateStorageInfo() {
        File dir = getExternalFilesDir(null);
        if (dir == null) dir = getFilesDir();

        StatFs stat      = new StatFs(dir.getPath());
        long available   = stat.getAvailableBytes();
        long total       = stat.getTotalBytes();
        long used        = total - available;
        // BUG FIX 4: avoid division by zero if total is somehow 0
        int percent = (total > 0) ? (int)((used * 100L) / total) : 0;

        tvStorageInfo.setText(String.format(Locale.getDefault(),
            "Armazenamento: %s usado / %s total (%d%%) | %s livre",
            formatBytes(used), formatBytes(total), percent, formatBytes(available)));

        // Count existing LoopCam files (only when service isn't broadcasting counts)
        if (!isRecording) {
            File loopDir = new File(dir, "LoopCam");
            int count = 0;
            if (loopDir.exists()) {
                File[] files = loopDir.listFiles();
                if (files != null) count = files.length;
            }
            tvRecordingCount.setText("Arquivos salvos: " + count);
        }
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024L)           return bytes + " B";
        if (bytes < 1024L * 1024)    return String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024));
        return String.format(Locale.getDefault(), "%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    // ─── Lifecycle ─────────────────────────────────────────────────────────

    @Override
    protected void onResume() {
        super.onResume();
        // Sync UI with service state when returning to the app
        isRecording = RecordingService.isRunning;
        if (isRecording) {
            tvStatus.setText("● GRAVANDO");
            tvStatus.setTextColor(ContextCompat.getColor(this, R.color.recording_red));
            btnToggle.setText("PARAR GRAVAÇÃO");
        } else {
            tvStatus.setText("○ PARADO");
            tvStatus.setTextColor(ContextCompat.getColor(this, R.color.stopped_gray));
            btnToggle.setText("INICIAR GRAVAÇÃO");
        }
        updateStorageInfo();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (statusReceiver != null) {
            try {
                unregisterReceiver(statusReceiver);
            } catch (IllegalArgumentException ignored) {
                // Already unregistered
            }
        }
    }
}
