package io.github.weiranyi.magneticfield;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.ProcessLifecycleOwner;
import com.google.android.gms.ads.AdError;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.FullScreenContentCallback;
import com.google.android.gms.ads.MobileAds;
import com.google.android.gms.ads.appopen.AppOpenAd;
import java.util.Date;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 自定义 Application 类，负责：
 * 1. 初始化 AdMob SDK
 * 2. 管理开屏广告的加载与展示
 * 3. 监听应用前后台切换
 *
 * 广告展示策略：
 * - 冷启动：SplashActivity 展示开屏广告
 * - 热启动：应用从后台回到前台时展示开屏广告
 * - 测试和生产都用同一个广告单元 ID
 */
public class MyApplication extends Application
        implements Application.ActivityLifecycleCallbacks, DefaultLifecycleObserver {

    // 广告单元 ID（测试/上线都用这个）
    private static final String AD_UNIT_ID = "ca-app-pub-6574931021125922/5877608240";

    private static final long AD_VALIDITY_MS = 4L * 60 * 60 * 1000; // 开屏广告有效期 4 小时

    private AppOpenAdManager adManager;
    private Activity currentActivity;
    private final AtomicBoolean sdkInitialized = new AtomicBoolean(false);
    private boolean adShownThisSession = false;

    @Override
    public void onCreate() {
        super.onCreate();
        registerActivityLifecycleCallbacks(this);
        ProcessLifecycleOwner.get().getLifecycle().addObserver(this);

        // 异步初始化 AdMob SDK（不阻塞启动）
        MobileAds.initialize(this, initializationStatus -> {
            sdkInitialized.set(true);
            adManager = new AppOpenAdManager();
            adManager.loadAd();
            android.util.Log.d("AdMob", "✅ AdMob SDK 初始化成功");
        });
    }

    // ==================== 应用前后台切换监听 ====================

    @Override
    public void onStart(@NonNull LifecycleOwner owner) {
        DefaultLifecycleObserver.super.onStart(owner);
        // 热启动：应用从后台回到前台时展示开屏广告
        if (sdkInitialized.get() && adManager != null && currentActivity != null) {
            boolean isSplash = currentActivity instanceof SplashActivity;
            if (!isSplash && !adShownThisSession) {
                adManager.showAdIfAvailable(currentActivity, () -> {});
            }
        }
    }

    @Override
    public void onStop(@NonNull LifecycleOwner owner) {
        DefaultLifecycleObserver.super.onStop(owner);
        // 应用进入后台时重置会话标记
        adShownThisSession = false;
    }

    // ==================== Activity 生命周期回调 ====================

    @Override
    public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {}

    @Override
    public void onActivityStarted(@NonNull Activity activity) {
        if (adManager == null || !adManager.isShowingAd) {
            currentActivity = activity;
        }
    }

    @Override
    public void onActivityResumed(@NonNull Activity activity) {}

    @Override
    public void onActivityPaused(@NonNull Activity activity) {}

    @Override
    public void onActivityStopped(@NonNull Activity activity) {}

    @Override
    public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {}

    @Override
    public void onActivityDestroyed(@NonNull Activity activity) {}

    // ==================== 供 SplashActivity 调用 ====================

    /** 冷启动展示广告（仅 DEBUG 模式调用） */
    public void showAdOnColdStart(@NonNull Activity activity, @NonNull Runnable onAdClosed) {
        if (adManager != null) {
            adManager.showAdIfAvailable(activity, onAdClosed);
        } else {
            onAdClosed.run();
        }
    }

    // ==================== 开屏广告管理器 ====================

    private class AppOpenAdManager {
        private AppOpenAd appOpenAd = null;
        private boolean isLoadingAd = false;
        boolean isShowingAd = false;
        private long loadTimeMillis = 0L;

        /** 加载开屏广告 */
        void loadAd() {
            if (isLoadingAd || appOpenAd != null) {
                return;
            }
            isLoadingAd = true;

            android.util.Log.d("AdMob", "开始加载开屏广告，AdUnitID: " + AD_UNIT_ID);

            AdRequest adRequest = new AdRequest.Builder().build();
            AppOpenAd.load(
                    getApplicationContext(),
                    AD_UNIT_ID,
                    adRequest,
                    new AppOpenAd.AppOpenAdLoadCallback() {
                        @Override
                        public void onAdLoaded(@NonNull AppOpenAd ad) {
                            appOpenAd = ad;
                            isLoadingAd = false;
                            loadTimeMillis = new Date().getTime();
                            android.util.Log.d("AdMob", "✅ 开屏广告加载成功");
                        }

                        @Override
                        public void onAdFailedToLoad(@NonNull com.google.android.gms.ads.LoadAdError error) {
                            isLoadingAd = false;
                            android.util.Log.e("AdMob", "❌ 开屏广告加载失败");
                            android.util.Log.e("AdMob", "   代码: " + error.getCode());
                            android.util.Log.e("AdMob", "   原因: " + error.getMessage());
                        }
                    });
        }

        /** 展示开屏广告（如果可用），展示完成后执行回调 */
        void showAdIfAvailable(@NonNull Activity activity, @NonNull Runnable onComplete) {
            if (isShowingAd) {
                android.util.Log.d("AdMob", "广告正在展示，跳过");
                onComplete.run();
                return;
            }
            if (!isAdAvailable()) {
                android.util.Log.d("AdMob", "广告不可用，执行回调并重新加载");
                onComplete.run();
                loadAd();
                return;
            }

            isShowingAd = true;
            adShownThisSession = true;

            // 设置回调后展示广告
            appOpenAd.setFullScreenContentCallback(new FullScreenContentCallback() {
                @Override
                public void onAdDismissedFullScreenContent() {
                    appOpenAd = null;
                    isShowingAd = false;
                    loadAd();
                    android.util.Log.d("AdMob", "广告已关闭，重新加载");
                    onComplete.run();
                }

                @Override
                public void onAdFailedToShowFullScreenContent(@NonNull AdError error) {
                    appOpenAd = null;
                    isShowingAd = false;
                    android.util.Log.e("AdMob", "广告展示失败: " + error.getMessage());
                    onComplete.run();
                }

                @Override
                public void onAdShowedFullScreenContent() {
                    android.util.Log.d("AdMob", "✅ 广告已全屏展示");
                }

                @Override
                public void onAdImpression() {
                    android.util.Log.d("AdMob", "广告曝光记录成功");
                }

                @Override
                public void onAdClicked() {
                    android.util.Log.d("AdMob", "广告被点击");
                }
            });

            appOpenAd.show(activity);
            android.util.Log.d("AdMob", "调用 ad.show()");
        }

        /** 判断广告是否在有效期内 */
        private boolean isAdAvailable() {
            if (appOpenAd == null) {
                return false;
            }
            long age = new Date().getTime() - loadTimeMillis;
            boolean valid = age < AD_VALIDITY_MS;
            android.util.Log.d("AdMob", "广告有效性检查: " + valid + " (年龄: " + (age / 1000) + "秒)");
            return valid;
        }
    }
}
