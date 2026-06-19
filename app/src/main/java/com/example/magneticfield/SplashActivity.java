package com.example.magneticfield;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

/**
 * 冷启动页（Launcher Activity）
 *
 * 行为：
 * - 冷启动时展示开屏广告
 * - 广告关闭后进入主页面
 * - 测试和生产都用同一个广告单元 ID
 */
public class SplashActivity extends AppCompatActivity {

    private static final long INIT_DELAY_MS = 1500L; // 等待 SDK 初始化的延迟
    private static final long PROGRESS_STEP_MS = 15L; // 进度更新间隔
    private Handler handler;
    private Runnable navigateRunnable;
    private Runnable progressRunnable;
    private ProgressBar progressBar;
    private TextView progressText;
    private int progress = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

        // 去掉 Logo 的阴影效果
        ImageView logo = findViewById(R.id.splashLogo);
        if (logo != null) {
            // 移除所有可能的阴影来源
            logo.setElevation(0f);
            logo.setTranslationZ(0f);
            logo.setOutlineProvider(null);
            // 确保 ImageView 没有背景和 padding
            logo.setBackground(null);
            logo.setPadding(0, 0, 0, 0);
            // 使用软件渲染避免硬件加速导致的伪影
            logo.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        }

        handler = new Handler(Looper.getMainLooper());

        // 启动进度条动画
        progressBar = findViewById(R.id.splashProgress);
        progressText = findViewById(R.id.splashProgressText);
        startProgressAnimation();

        // 延迟一点让 AdMob SDK 有足够时间
        navigateRunnable = this::showAdThenNavigate;
        handler.postDelayed(navigateRunnable, INIT_DELAY_MS);
    }

    /** 启动页进度条：0% -> 100% 在 INIT_DELAY_MS 内匀速增长 */
    private void startProgressAnimation() {
        if (progressBar == null) return;
        progress = 0;
        progressBar.setProgress(0);
        updateProgressText();

        int totalSteps = (int) (INIT_DELAY_MS / PROGRESS_STEP_MS);
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

    /** 展示开屏广告，广告关闭后跳转主页面 */
    private void showAdThenNavigate() {
        if (isFinishing() || isDestroyed()) {
            return;
        }

        MyApplication app = (MyApplication) getApplication();
        app.showAdOnColdStart(this, this::navigateToMain);
    }

    /** 跳转到主页面 */
    private void navigateToMain() {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        Intent intent = new Intent(this, MainActivity.class);
        startActivity(intent);
        overridePendingTransition(R.anim.activity_fade_in, R.anim.activity_fade_out);
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 清理 Handler 防止内存泄漏
        if (handler != null) {
            if (navigateRunnable != null) handler.removeCallbacks(navigateRunnable);
            if (progressRunnable != null) handler.removeCallbacks(progressRunnable);
        }
    }
}
