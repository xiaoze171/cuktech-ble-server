package com.cuktech.mobile;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.JavascriptInterface;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Thin native host. All charger UI is served by the bundled application. */
public final class MainActivity extends Activity {
    private static final int REQUEST_BLUETOOTH = 10;
    private static final int REQUEST_NOTIFICATIONS = 11;
    private static final int REQUEST_ENABLE_BLUETOOTH = 12;
    private static final int REQUEST_LOCATION_SETTINGS = 13;
    private static final int REQUEST_EXPORT = 14;
    private static final long MAX_EXPORT_BYTES = 128L * 1024 * 1024;

    private WebView webView;
    private LinearLayout messagePanel;
    private TextView message;
    private ProgressBar progress;
    private Button retry;
    private Button settings;
    private LocalUrlPolicy urlPolicy;
    private String loadedBase = "";
    private String pendingExport;
    private boolean resumed;
    private boolean receiverRegistered;
    private boolean preparationPending;
    private boolean requestInFlight;
    private boolean askedEnable;
    private boolean askedLocation;
    private boolean initialStart;
    private boolean pageFailed;
    private final ExecutorService exports = Executors.newSingleThreadExecutor();

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { showRuntimeState(); }
    };

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        initialStart = savedInstanceState == null;
        if (savedInstanceState != null) {
            pendingExport = savedInstanceState.getString("pendingExport");
            preparationPending = savedInstanceState.getBoolean("preparationPending");
            askedEnable = savedInstanceState.getBoolean("askedEnable");
            askedLocation = savedInstanceState.getBoolean("askedLocation");
        }
        createViews();
        showMessage(getString(R.string.preparing), true, false);
    }

    private void createViews() {
        WebView.setWebContentsDebuggingEnabled(
                (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0);
        // 页面默认浅色（白色背景），本地外壳保持一致的白色，避免启动白/黑闪烁。
        getWindow().setStatusBarColor(Color.WHITE);
        getWindow().setNavigationBarColor(Color.WHITE);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.WHITE);
        // Android 15 enforces edge-to-edge: preserve the original page's usable viewport.
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        webView = new WebView(this);
        clearStaleAssetCache(webView);
        webView.setVerticalScrollBarEnabled(false);
        webView.setHorizontalScrollBarEnabled(false);
        webView.addJavascriptInterface(new AndroidSettingsBridge(), "AndroidSettings");
        webView.setBackgroundColor(Color.WHITE);
        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setAllowFileAccess(false);
        webSettings.setAllowContentAccess(false);
        webSettings.setAllowFileAccessFromFileURLs(false);
        webSettings.setAllowUniversalAccessFromFileURLs(false);
        webSettings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        webSettings.setJavaScriptCanOpenWindowsAutomatically(false);
        webSettings.setSupportMultipleWindows(false);
        if (Build.VERSION.SDK_INT >= 26) webSettings.setSafeBrowsingEnabled(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false);
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return routeNavigation(request.getUrl().toString(), request.isForMainFrame());
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return routeNavigation(url, true);
            }
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                if (urlPolicy == null || !urlPolicy.isLocal(url)) {
                    view.stopLoading();
                    if (!"about:blank".equals(url)) openExternal(url);
                }
            }
            @Override public void onPageFinished(WebView view, String url) {
                if (!pageFailed && "running".equals(ChargerService.snapshot().state)
                        && urlPolicy != null && urlPolicy.isLocal(url)) {
                    messagePanel.setVisibility(View.GONE);
                    webView.setVisibility(View.VISIBLE);
                }
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    pageFailed = true;
                    showMessage(getString(R.string.page_load_failed) + "\n" + error.getDescription(), false, true);
                }
            }
        });
        webView.setDownloadListener((url, userAgent, disposition, mimeType, length) -> requestExport(url));
        root.addView(webView, new FrameLayout.LayoutParams(-1, -1));

        messagePanel = new LinearLayout(this);
        messagePanel.setOrientation(LinearLayout.VERTICAL);
        messagePanel.setGravity(Gravity.CENTER);
        messagePanel.setPadding(dp(28), dp(28), dp(28), dp(28));
        messagePanel.setBackgroundColor(Color.WHITE);
        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextColor(Color.rgb(26, 26, 26));
        title.setTextSize(24);
        title.setGravity(Gravity.CENTER);
        messagePanel.addView(title, new LinearLayout.LayoutParams(-1, -2));
        progress = new ProgressBar(this);
        LinearLayout.LayoutParams progressLayout = new LinearLayout.LayoutParams(dp(40), dp(40));
        progressLayout.topMargin = dp(24);
        progressLayout.bottomMargin = dp(20);
        messagePanel.addView(progress, progressLayout);
        message = new TextView(this);
        message.setTextColor(Color.rgb(102, 102, 102));
        message.setTextSize(16);
        message.setGravity(Gravity.CENTER);
        message.setPadding(0, dp(16), 0, dp(20));
        messagePanel.addView(message, new LinearLayout.LayoutParams(-1, -2));
        retry = new Button(this);
        retry.setText(R.string.retry);
        retry.setOnClickListener(view -> {
            if ("running".equals(ChargerService.snapshot().state)) {
                pageFailed = false;
                showMessage(getString(R.string.preparing_page), true, false);
                webView.reload();
            } else {
                askedEnable = false;
                preparationPending = true;
                continueStartup();
            }
        });
        messagePanel.addView(retry, new LinearLayout.LayoutParams(-1, -2));
        settings = new Button(this);
        settings.setText(R.string.open_app_settings);
        settings.setOnClickListener(view -> {
            preparationPending = true;
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
        });
        messagePanel.addView(settings, new LinearLayout.LayoutParams(-1, -2));
        root.addView(messagePanel, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        root.requestApplyInsets();
    }

    @Override protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(ChargerService.ACTION_STATE);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(stateReceiver, filter);
        receiverRegistered = true;
        showRuntimeState();
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        webView.onResume();
        if (initialStart) {
            initialStart = false;
            String state = ChargerService.snapshot().state;
            preparationPending = !"running".equals(state) && !"starting".equals(state) && !"stopping".equals(state);
        } else if (!preparationPending && runtimeDown()) {
            // Opening the app must reconnect the already configured charger, whether the
            // runtime was stopped by background policy, by the notification action or by a failure.
            askedEnable = false;
            preparationPending = true;
        }
        if (preparationPending) continueStartup();
    }

    private boolean runtimeDown() {
        String state = ChargerService.snapshot().state;
        return "stopped".equals(state) || "error".equals(state);
    }

    @Override protected void onPause() {
        resumed = false;
        webView.onPause();
        super.onPause();
    }

    @Override protected void onStop() {
        if (receiverRegistered) {
            unregisterReceiver(stateReceiver);
            receiverRegistered = false;
        }
        if (!KeepAlivePreferences.isEnabled(this)
                && !isChangingConfigurations()
                && !isFinishing()) {
            startService(new Intent(this, ChargerService.class).setAction(ChargerService.ACTION_STOP));
        }
        super.onStop();
    }

    /** A reinstall ships new assets under unchanged URLs and the engine marks them
     *  immutable, so the WebView would keep serving the previous page for a week.
     *  Drop the resource cache once per install; storage and settings are untouched. */
    private void clearStaleAssetCache(WebView view) {
        android.content.SharedPreferences preferences = getPreferences(MODE_PRIVATE);
        long installed = 0L;
        try {
            installed = getPackageManager().getPackageInfo(getPackageName(), 0).lastUpdateTime;
        } catch (PackageManager.NameNotFoundException error) {
            installed = System.currentTimeMillis();
        }
        if (preferences.getLong("assetCacheInstall", -1L) == installed) return;
        preferences.edit().putLong("assetCacheInstall", installed).apply();
        view.clearCache(true);
    }

    private void continueStartup() {
        if (!resumed || requestInFlight || !preparationPending) return;
        showMessage(getString(R.string.preparing), true, false);
        if (!ChargerService.hasBluetoothPermissions(this)) {
            requestInFlight = true;
            String[] permissions = Build.VERSION.SDK_INT >= 31
                    ? new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT}
                    : new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
            requestPermissions(permissions, REQUEST_BLUETOOTH);
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                && !getPreferences(MODE_PRIVATE).getBoolean("askedNotifications", false)) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("askedNotifications", true).apply();
            requestInFlight = true;
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
            return;
        }
        BluetoothManager bluetooth = getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = bluetooth == null ? null : bluetooth.getAdapter();
        if (adapter == null) {
            Toast.makeText(this, R.string.bluetooth_unavailable, Toast.LENGTH_LONG).show();
        } else if (!askedEnable) {
            askedEnable = true;
            try {
                if (!adapter.isEnabled()) {
                    requestInFlight = true;
                    startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQUEST_ENABLE_BLUETOOTH);
                    return;
                }
            } catch (SecurityException | ActivityNotFoundException error) {
                requestInFlight = false;
                Toast.makeText(this, R.string.bluetooth_disabled_hint, Toast.LENGTH_LONG).show();
            }
        }
        if (Build.VERSION.SDK_INT <= 30 && !askedLocation && !locationEnabled()) {
            askedLocation = true;
            requestInFlight = true;
            new AlertDialog.Builder(this).setTitle(R.string.location_title).setMessage(R.string.location_message)
                    .setPositiveButton(R.string.open_settings, (dialog, which) -> {
                        try { startActivityForResult(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS), REQUEST_LOCATION_SETTINGS); }
                        catch (ActivityNotFoundException error) { requestInFlight = false; continueStartup(); }
                    })
                    .setNegativeButton(R.string.continue_without_bluetooth, (dialog, which) -> {
                        requestInFlight = false; continueStartup();
                    })
                    .setOnCancelListener(dialog -> { requestInFlight = false; continueStartup(); }).show();
            return;
        }
        preparationPending = false;
        try {
            Intent start = new Intent(this, ChargerService.class).setAction(ChargerService.ACTION_START);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(start);
            else startService(start);
        } catch (RuntimeException error) {
            showMessage(getString(R.string.service_start_failed) + "\n" + error.getMessage(), false, true);
        }
    }

    private final class AndroidSettingsBridge {
        @JavascriptInterface public boolean isKeepAliveEnabled() {
            return KeepAlivePreferences.isEnabled(MainActivity.this);
        }

        /** 应用版本（如 1.1.1-android.10），供配置页「关于」显示。 */
        @JavascriptInterface public String getVersionName() {
            try {
                return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            } catch (Exception error) {
                return "";
            }
        }

        @JavascriptInterface public void setKeepAlive(boolean enabled) {
            runOnUiThread(() -> {
                KeepAlivePreferences.setEnabled(MainActivity.this, enabled);
                if (enabled) {
                    Intent start = new Intent(MainActivity.this, ChargerService.class).setAction(ChargerService.ACTION_START);
                    if (Build.VERSION.SDK_INT >= 26) startForegroundService(start); else startService(start);
                }
            });
        }

        // ── 应用内更新：WebView 下载 release 资产 → PackageInstaller 拉起系统安装 ──
        private final Object updateLock = new Object();
        private String updateState = "idle";   // idle | running | done | error
        private long updateReceived = 0, updateTotal = 0;
        private String updateError = "";
        private java.io.File updateApk = null;

        /** 后台下载更新包。仅允许本仓库 release 资产与其 CDN 跳转地址。 */
        @JavascriptInterface public String downloadUpdate(final String url) {
            boolean allowed = url != null && (url.startsWith(
                    "https://github.com/xiaoze171/cuktech-ble-server/releases/download/")
                || url.startsWith("https://objects.githubusercontent.com/"));
            if (!allowed) return "blocked";
            synchronized (updateLock) {
                if ("running".equals(updateState)) return "running";
                updateState = "running"; updateReceived = 0; updateTotal = 0; updateError = "";
                new java.io.File(getFilesDir(), "updates").mkdirs();
                updateApk = new java.io.File(getFilesDir(), "updates/update.apk");
            }
            Thread worker = new Thread(() -> {
                java.io.File tmp = new java.io.File(getFilesDir(), "updates/update.apk.tmp");
                Exception lastError = null;
                // 慢网络下单次读取可能超时：最多尝试 3 次，支持断点续传
                for (int attempt = 0; attempt < 3; attempt++) {
                    try {
                        javax.net.ssl.HttpsURLConnection conn = (javax.net.ssl.HttpsURLConnection) new java.net.URL(url).openConnection();
                        conn.setInstanceFollowRedirects(true);
                        conn.setConnectTimeout(20000);
                        conn.setReadTimeout(60000);
                        conn.setRequestProperty("User-Agent", "cuktech-ble-server");
                        boolean resume = attempt > 0 && tmp.exists() && tmp.length() > 0;
                        if (resume) conn.setRequestProperty("Range", "bytes=" + tmp.length() + "-");
                        int code = conn.getResponseCode();
                        boolean appending = resume && code == 206;
                        try (java.io.InputStream in = conn.getInputStream();
                             java.io.OutputStream out = new java.io.FileOutputStream(tmp, appending)) {
                            updateTotal = conn.getContentLength() + (appending ? tmp.length() : 0);
                            if (!appending) updateReceived = 0;
                            byte[] buffer = new byte[65536];
                            int read;
                            while ((read = in.read(buffer)) > 0) {
                                out.write(buffer, 0, read);
                                updateReceived += read;
                            }
                        }
                        if (!tmp.renameTo(updateApk)) throw new java.io.IOException("rename failed");
                        synchronized (updateLock) { updateState = "done"; }
                        lastError = null;
                        break;
                    } catch (Exception error) {
                        lastError = error;
                        synchronized (updateLock) { updateState = "error"; updateError = String.valueOf(error.getMessage()); }
                    }
                }
                if (lastError != null) tmp.delete();
            }, "update-download");
            worker.start();
            return "running";
        }

        /** 下载进度 JSON（WebView 每秒轮询）。 */
        @JavascriptInterface public String downloadStatus() {
            synchronized (updateLock) {
                return "{\"state\":\"" + updateState + "\",\"received\":" + updateReceived
                    + ",\"total\":" + updateTotal + ",\"error\":\""
                    + (updateError == null ? "" : updateError.replace("\"", "'")) + "\"}";
            }
        }

        /** 下载完成后触发系统安装确认；返回 ok/permission/no_file/error:... */
        @JavascriptInterface public String installUpdate() {
            try {
                if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
                    Intent perm = new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        android.net.Uri.parse("package:" + getPackageName()));
                    perm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(perm);
                    return "permission";
                }
                java.io.File apk = updateApk != null ? updateApk : new java.io.File(getFilesDir(), "updates/update.apk");
                if (!apk.exists() || apk.length() == 0) return "no_file";
                android.content.pm.PackageInstaller installer = getPackageManager().getPackageInstaller();
                android.content.pm.PackageInstaller.SessionParams params =
                    new android.content.pm.PackageInstaller.SessionParams(
                        android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                int sessionId = installer.createSession(params);
                android.content.pm.PackageInstaller.Session session = installer.openSession(sessionId);
                try (java.io.InputStream in = new java.io.FileInputStream(apk);
                     java.io.OutputStream out = session.openWrite("update.apk", 0, apk.length())) {
                    byte[] buffer = new byte[65536];
                    int read;
                    while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
                    session.fsync(out);
                }
                int piFlags = android.app.PendingIntent.FLAG_UPDATE_CURRENT;
                if (Build.VERSION.SDK_INT >= 31) piFlags |= android.app.PendingIntent.FLAG_MUTABLE;
                Intent done = new Intent(InstallStatusReceiver.ACTION).setPackage(getPackageName());
                android.app.PendingIntent pending = android.app.PendingIntent.getBroadcast(MainActivity.this, 0, done, piFlags);
                session.commit(pending.getIntentSender());
                session.close();
                return "ok";
            } catch (Exception error) {
                return "error:" + error.getMessage();
            }
        }
    }

    private boolean locationEnabled() {
        LocationManager manager = getSystemService(LocationManager.class);
        if (manager == null) return false;
        if (Build.VERSION.SDK_INT >= 28) return manager.isLocationEnabled();
        return manager.isProviderEnabled(LocationManager.GPS_PROVIDER) || manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        requestInFlight = false;
        if (requestCode == REQUEST_BLUETOOTH && !ChargerService.hasBluetoothPermissions(this)) {
            preparationPending = false;
            showMessage(getString(R.string.permission_required), false, true);
            settings.setVisibility(View.VISIBLE);
        } else {
            continueStartup();
        }
    }

    private void showRuntimeState() {
        ChargerService.Snapshot state = ChargerService.snapshot();
        switch (state.state) {
            case "running":
                if (!state.url.equals(loadedBase)) {
                    try { urlPolicy = new LocalUrlPolicy(state.url); }
                    catch (IllegalArgumentException error) { showMessage(error.getMessage(), false, true); return; }
                    loadedBase = urlPolicy.baseUrl;
                    pageFailed = false;
                    showMessage(getString(R.string.preparing_page), true, false);
                    webView.loadUrl(loadedBase + "/phone.html");
                } else if (!pageFailed) {
                    messagePanel.setVisibility(View.GONE);
                    webView.setVisibility(View.VISIBLE);
                }
                break;
            case "starting": showMessage(getString(R.string.preparing), true, false); break;
            case "stopping":
                loadedBase = "";
                urlPolicy = null;
                webView.stopLoading();
                showMessage(getString(R.string.stopping), true, false);
                break;
            case "error":
                loadedBase = "";
                urlPolicy = null;
                webView.stopLoading();
                showMessage(getString(R.string.service_start_failed) + "\n" + state.error, false, true);
                break;
            default:
                loadedBase = "";
                urlPolicy = null;
                webView.stopLoading();
                showMessage(getString(R.string.runtime_stopped), false, true);
                break;
        }
    }

    private void showMessage(String text, boolean busy, boolean canRetry) {
        webView.setVisibility(View.INVISIBLE);
        message.setText(text);
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        retry.setVisibility(canRetry ? View.VISIBLE : View.GONE);
        settings.setVisibility(View.GONE);
        messagePanel.setVisibility(View.VISIBLE);
    }

    private boolean routeNavigation(String url, boolean mainFrame) {
        if (urlPolicy != null && urlPolicy.isLocal(url)) {
            if (mainFrame && urlPolicy.isCsvExport(url)) { requestExport(url); return true; }
            return false;
        }
        if (mainFrame) openExternal(url);
        return true;
    }

    private void openExternal(String url) {
        if (!LocalUrlPolicy.isExternalWeb(url)) return;
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)); }
        catch (ActivityNotFoundException error) { Toast.makeText(this, R.string.no_browser, Toast.LENGTH_LONG).show(); }
    }

    private void requestExport(String url) {
        if (urlPolicy == null || !urlPolicy.isCsvExport(url)) {
            Toast.makeText(this, R.string.unsupported_download, Toast.LENGTH_LONG).show();
            return;
        }
        if (pendingExport != null) return;
        pendingExport = url;
        String port = Uri.parse(url).getLastPathSegment();
        Intent create = new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("text/csv")
                .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, "port_" + port + "_history.csv");
        try { startActivityForResult(create, REQUEST_EXPORT); }
        catch (ActivityNotFoundException error) {
            pendingExport = null;
            Toast.makeText(this, R.string.no_document_picker, Toast.LENGTH_LONG).show();
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_EXPORT) {
            String export = pendingExport;
            pendingExport = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null && export != null) {
                // Activity recreation may deliver this result before onStart restores the current origin.
                try {
                    LocalUrlPolicy current = new LocalUrlPolicy(ChargerService.snapshot().url);
                    if (!current.isCsvExport(export)) throw new IllegalArgumentException("Export origin changed");
                    saveExport(export, data.getData());
                } catch (IllegalArgumentException error) {
                    Toast.makeText(this, R.string.export_failed, Toast.LENGTH_LONG).show();
                }
            }
        } else if (requestCode == REQUEST_ENABLE_BLUETOOTH || requestCode == REQUEST_LOCATION_SETTINGS) {
            requestInFlight = false;
            if (requestCode == REQUEST_ENABLE_BLUETOOTH && resultCode != RESULT_OK) {
                Toast.makeText(this, R.string.bluetooth_disabled_hint, Toast.LENGTH_LONG).show();
            }
            continueStartup();
        }
    }

    private void saveExport(String url, Uri destination) {
        Context application = getApplicationContext();
        exports.execute(() -> {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(url).openConnection();
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(30000);
                connection.setRequestProperty("Accept", "text/csv");
                if (connection.getResponseCode() != 200 || connection.getContentType() == null
                        || !connection.getContentType().toLowerCase(java.util.Locale.ROOT).startsWith("text/csv")) {
                    throw new java.io.IOException("Unexpected export response");
                }
                try (InputStream input = connection.getInputStream();
                     OutputStream output = application.getContentResolver().openOutputStream(destination, "wt")) {
                    if (output == null) throw new java.io.IOException("Cannot open destination");
                    byte[] buffer = new byte[32768];
                    long total = 0;
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        total += count;
                        if (total > MAX_EXPORT_BYTES) throw new java.io.IOException("Export exceeds 128 MB");
                        output.write(buffer, 0, count);
                    }
                }
                runOnUiThread(() -> Toast.makeText(application, R.string.export_saved, Toast.LENGTH_LONG).show());
            } catch (Exception error) {
                android.util.Log.w("ChargerExport", "CSV export failed", error);
                runOnUiThread(() -> Toast.makeText(application, R.string.export_failed, Toast.LENGTH_LONG).show());
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        outState.putString("pendingExport", pendingExport);
        outState.putBoolean("preparationPending", preparationPending);
        outState.putBoolean("askedEnable", askedEnable);
        outState.putBoolean("askedLocation", askedLocation);
        super.onSaveInstanceState(outState);
    }

    @Override public void onBackPressed() {
        if (webView.getVisibility() == View.VISIBLE && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        webView.stopLoading();
        webView.destroy();
        exports.shutdown();
        super.onDestroy();
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
