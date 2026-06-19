package com.example.magneticfield;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

/**
 * 冷启动页（Launcher Activity）
 * 展示品牌 Logo + 进度条，2秒后自动进入 MainActivity
 */
public class SplashActivity extends AppCompatActivity {

    private static final long SPLASH_DELAY_MS = 2000L;
    private static final long PROGRESS_STEP_MS = 20L;

    private Handler handler;
    private Runnable navigateRunnable;
    private Runnable progressRunnable;
    private ProgressBar progressBar;
    private TextView progressText;
    private int progress = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            setContentView(R.layout.activity_splash);
        } catch (Throwable t) {
            // 布局 inflate 失败，直接跳过启动页
            t.printStackTrace();
            navigateToMain();
            return;
        }

        handler = new Handler(Looper.getMainLooper());

        progressBar = findViewById(R.id.splashProgress);
        progressText = findViewById(R.id.splashProgressText);
        startProgressAnimation();

        navigateRunnable = this::navigateToMain;
        handler.postDelayed(navigateRunnable, SPLASH_DELAY_MS);
    }

    private void startProgressAnimation() {
        if (progressBar == null) return;
        progress = 0;
        progressBar.setProgress(0);
        updateProgressText();

        int totalSteps = (int) (SPLASH_DELAY_MS / PROGRESS_STEP_MS);
        progressRunnable = new Runnable() {
            @Override
            public void run() {
                if (isFinishing() || isDestroyed()) return;
                progress++;
                int percent = Math.min(100, (progress * 100) / totalSteps);
                if (progressBar != null) progressBar.setProgress(percent);
                updateProgressText();
                if (progress < totalSteps && percent < 100) {
                    handler.postDelayed(this, PROGRESS_STEP_MS);
                }
            }
        };
        handler.postDelayed(progressRunnable, PROGRESS_STEP_MS);
    }

    private void updateProgressText() {
        if (progressText != null && progressBar != null) {
            progressText.setText(progressBar.getProgress() + "%");
        }
    }

    private void navigateToMain() {
        if (isFinishing() || isDestroyed()) return;
        try {
            Intent intent = new Intent(this, MainActivity.class);
            startActivity(intent);
            finish();
        } catch (Throwable t) {
            t.printStackTrace();
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        if (handler != null) {
            if (navigateRunnable != null) handler.removeCallbacks(navigateRunnable);
            if (progressRunnable != null) handler.removeCallbacks(progressRunnable);
        }
        super.onDestroy();
    }
}
