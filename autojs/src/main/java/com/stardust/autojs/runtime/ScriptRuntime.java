package com.stardust.autojs.runtime;

import android.content.Context;
import android.os.Build;
import android.os.Looper;

import com.stardust.autojs.R;
import com.stardust.autojs.ScriptEngineService;
import com.stardust.autojs.annotation.ScriptVariable;
import com.stardust.autojs.core.accessibility.AccessibilityBridge;
import com.stardust.autojs.engine.ScriptEngine;
import com.stardust.autojs.rhino.AndroidClassLoader;
import com.stardust.autojs.runtime.api.AbstractShell;
import com.stardust.autojs.runtime.api.AppUtils;
import com.stardust.autojs.runtime.api.Console;
import com.stardust.autojs.runtime.api.Device;
import com.stardust.autojs.runtime.api.Engines;
import com.stardust.autojs.runtime.api.Events;
import com.stardust.autojs.runtime.api.Floaty;
import com.stardust.autojs.core.looper.Loopers;
import com.stardust.autojs.runtime.api.Threads;
import com.stardust.autojs.runtime.api.Timers;
import com.stardust.autojs.core.accessibility.UiSelector;
import com.stardust.autojs.runtime.api.Images;
import com.stardust.autojs.core.image.ScreenCaptureRequester;
import com.stardust.autojs.runtime.api.Dialogs;
import com.stardust.autojs.runtime.exception.ScriptEnvironmentException;
import com.stardust.autojs.runtime.exception.ScriptException;
import com.stardust.autojs.runtime.exception.ScriptInterruptedException;
import com.stardust.autojs.core.accessibility.SimpleActionAutomator;
import com.stardust.autojs.runtime.api.UI;
import com.stardust.concurrent.VolatileDispose;
import com.stardust.pio.UncheckedIOException;
import com.stardust.util.ClipboardUtil;
import com.stardust.autojs.core.util.ProcessShell;
import com.stardust.util.ScreenMetrics;
import com.stardust.util.SdkVersionUtil;
import com.stardust.util.Supplier;
import com.stardust.util.UiHandler;
import com.stardust.view.accessibility.AccessibilityInfoProvider;

import org.mozilla.javascript.ContextFactory;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


/**
 * Created by Stardust on 2017/1/27.
 */

public class ScriptRuntime {

    private static final String TAG = "ScriptRuntime";


    public static class Builder {
        private UiHandler mUiHandler;
        private Console mConsole;
        private AccessibilityBridge mAccessibilityBridge;
        private Supplier<AbstractShell> mShellSupplier;
        private ScreenCaptureRequester mScreenCaptureRequester;
        private AppUtils mAppUtils;
        private ScriptEngineService mEngineService;

        public Builder() {

        }

        public Builder setUiHandler(UiHandler uiHandler) {
            mUiHandler = uiHandler;
            return this;
        }

        public Builder setConsole(Console console) {
            mConsole = console;
            return this;
        }

        public Builder setAccessibilityBridge(AccessibilityBridge accessibilityBridge) {
            mAccessibilityBridge = accessibilityBridge;
            return this;
        }

        public Builder setShellSupplier(Supplier<AbstractShell> shellSupplier) {
            mShellSupplier = shellSupplier;
            return this;
        }

        public Builder setScreenCaptureRequester(ScreenCaptureRequester requester) {
            mScreenCaptureRequester = requester;
            return this;
        }

        public Builder setAppUtils(AppUtils appUtils) {
            mAppUtils = appUtils;
            return this;
        }

        public Builder setEngineService(ScriptEngineService service) {
            mEngineService = service;
            return this;
        }


        public ScriptRuntime build() {
            return new ScriptRuntime(this);
        }

    }


    @ScriptVariable
    public final AppUtils app;

    @ScriptVariable
    public final Console console;

    @ScriptVariable
    public final SimpleActionAutomator automator;

    @ScriptVariable
    public final AccessibilityInfoProvider info;

    @ScriptVariable
    public final UI ui;

    @ScriptVariable
    public final Dialogs dialogs;

    @ScriptVariable
    public Events events;

    @ScriptVariable
    public final ScriptBridges bridges = new ScriptBridges();

    @ScriptVariable
    public Loopers loopers;

    @ScriptVariable
    public Timers timers;

    @ScriptVariable
    public Device device;

    @ScriptVariable
    public final AccessibilityBridge accessibilityBridge;

    @ScriptVariable
    public final Engines engines;

    @ScriptVariable
    public Threads threads;

    @ScriptVariable
    public final Floaty floaty;

    @ScriptVariable
    public UiHandler uiHandler;

    private Images images;

    private static WeakReference<Context> applicationContext;
    private Map<String, Object> mProperties = new ConcurrentHashMap<>();
    private AbstractShell mRootShell;
    private Supplier<AbstractShell> mShellSupplier;
    private ScreenMetrics mScreenMetrics = new ScreenMetrics();
    private Thread mThread;


    protected ScriptRuntime(Builder builder) {
        app = builder.mAppUtils;
        uiHandler = builder.mUiHandler;
        console = builder.mConsole;
        accessibilityBridge = builder.mAccessibilityBridge;
        mShellSupplier = builder.mShellSupplier;
        ui = new UI(uiHandler.getContext());
        this.automator = new SimpleActionAutomator(accessibilityBridge, this);
        automator.setScreenMetrics(mScreenMetrics);
        this.info = accessibilityBridge.getInfoProvider();
        Context context = uiHandler.getContext();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            images = new Images(context, this, builder.mScreenCaptureRequester);
        }
        engines = new Engines(builder.mEngineService);
        dialogs = new Dialogs(app, uiHandler, bridges);
        device = new Device(uiHandler.getContext());
        floaty = new Floaty(uiHandler, ui, this);
    }

    public void init() {
        if (loopers != null)
            throw new IllegalStateException("already initialized");
        threads = new Threads(this);
        timers = new Timers(bridges, threads);
        loopers = new Loopers(this);
        events = new Events(uiHandler.getContext(), accessibilityBridge, this);
        mThread = Thread.currentThread();
    }

    public static void setApplicationContext(Context context) {
        applicationContext = new WeakReference<>(context);
    }

    public static Context getApplicationContext() {
        if (applicationContext == null || applicationContext.get() == null) {
            throw new ScriptEnvironmentException("No application context");
        }
        return applicationContext.get();
    }

    public UiHandler getUiHandler() {
        return uiHandler;
    }

    public AccessibilityBridge getAccessibilityBridge() {
        return accessibilityBridge;
    }

    public void toast(final String text) {
        uiHandler.toast(text);
    }

    public void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            throw new ScriptInterruptedException();
        }
    }

    public void setClip(final String text) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            ClipboardUtil.setClip(uiHandler.getContext(), text);
            return;
        }
        VolatileDispose<Object> dispose = new VolatileDispose<>();
        uiHandler.post(() -> {
            ClipboardUtil.setClip(uiHandler.getContext(), text);
            dispose.setAndNotify(text);
        });
        dispose.blockedGet();
    }

    public String getClip() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return ClipboardUtil.getClipOrEmpty(uiHandler.getContext()).toString();
        }
        final VolatileDispose<String> clip = new VolatileDispose<>();
        uiHandler.post(() -> clip.setAndNotify(ClipboardUtil.getClipOrEmpty(uiHandler.getContext()).toString()));
        return clip.blockedGetOrThrow(ScriptInterruptedException.class);
    }

    public AbstractShell getRootShell() {
        ensureRootShell();
        return mRootShell;
    }

    private void ensureRootShell() {
        if (mRootShell == null) {
            mRootShell = mShellSupplier.get();
            mRootShell.SetScreenMetrics(mScreenMetrics);
            mShellSupplier = null;
        }
    }

    public AbstractShell.Result shell(String cmd, int root) {
        return ProcessShell.execCommand(cmd, root != 0);
    }

    public UiSelector selector(ScriptEngine engine) {
        return new UiSelector(accessibilityBridge);
    }

    public boolean isStopped() {
        return Thread.currentThread().isInterrupted();
    }

    public void requiresApi(int i) {
        if (Build.VERSION.SDK_INT < i) {
            throw new ScriptException(uiHandler.getContext().getString(R.string.text_requires_sdk_version_to_run_the_script) + SdkVersionUtil.sdkIntToString(i));
        }
    }

    public void loadJar(String path) {
        try {
            ((AndroidClassLoader) ContextFactory.getGlobal().getApplicationClassLoader()).loadJar(new File(path));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void exit() {
        mThread.interrupt();
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new ScriptInterruptedException();
        }
    }

    @Deprecated
    public void stop() {
        exit();
    }


    public void setScreenMetrics(int width, int height) {
        mScreenMetrics.setScreenMetrics(width, height);
    }

    public ScreenMetrics getScreenMetrics() {
        return mScreenMetrics;
    }

    public void ensureAccessibilityServiceEnabled() {
        accessibilityBridge.ensureServiceEnabled();
    }

    public void onExit() {
        //清除interrupt状态
        Thread.interrupted();
        //悬浮窗需要第一时间关闭以免出现恶意脚本全屏悬浮窗屏蔽屏幕并且在exit中写死循环的问题
        ignoresException(floaty::closeAll);
        try {
            events.emit("exit");
        } catch (Exception ignored) {
            console.error("exception on exit: " + ignored);
        }
        ignoresException(threads::shutDownAll);
        ignoresException(events::recycle);
        ignoresException(loopers::quitAll);
        ignoresException(() -> {
            if (mRootShell != null) mRootShell.exitAndWaitFor();
            mRootShell = null;
            mShellSupplier = null;
        });
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            ignoresException(images::releaseScreenCapturer);
        }
    }

    private void ignoresException(Runnable r) {
        try {
            r.run();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public Object getImages() {
        return images;
    }

    public Object getProperty(String key) {
        return mProperties.get(key);
    }

    public Object putProperty(String key, Object value) {
        return mProperties.put(key, value);
    }

    public Object removeProperty(String key) {
        return mProperties.remove(key);
    }

}
