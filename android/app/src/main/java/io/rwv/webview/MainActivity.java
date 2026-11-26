package io.rwv.webview;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;
import android.os.Message;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.View;
import android.view.KeyEvent;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.autofill.AutofillManager;
import android.webkit.GeolocationPermissions;
import android.webkit.PermissionRequest;
import android.webkit.URLUtil;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public final class MainActivity extends Activity {
    private static final int PICK_FILE = 41;
    private static final int WEB_PERMISSION = 42;
    private Runnable pendingPermission;
    private PermissionRequest pendingMediaRequest;
    private final Set<AlertDialog> popups = new HashSet<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private WebView webView;
    private ValueCallback<Uri[]> fileCallback;
    private FrameLayout root;
    private boolean fullscreen;
    private boolean destroying;
    private WebView splash;
    private long splashStarted;
    private boolean splashReady;
    private boolean pageReady;
    private static final long SPLASH_MIN_MS = 1400;
    private static final long SPLASH_MAX_MS = 5000;
    private final Runnable splashTimeout = () -> dismissSplash();

    private String readAsset(String name) throws Exception {
        try (InputStream in = getAssets().open(name); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    @Override public void onCreate(Bundle state) {
        setTheme(R.style.AppTheme);
        super.onCreate(state);
        try {
            JSONObject config = new JSONObject(readAsset("config.json"));
            final String url = config.getString("website_url");
            fullscreen = state == null ? config.optBoolean("fullscreen", false) : state.getBoolean("rwv.fullscreen", config.optBoolean("fullscreen", false));
            final String signature = readAsset("console.js");
            if (!Navigation.isWebUrl(url)) throw new IllegalArgumentException("Invalid website URL");
            root = new FrameLayout(this);
            root.setBackgroundColor(Color.WHITE);
            webView = new WebView(this);
            root.addView(webView, new FrameLayout.LayoutParams(-1, -1));
            configureWebView(webView, signature, null);
            // No app toolbar or reserved top margin. The website owns the entire content area.
            setContentView(root);
            applyFullscreen();
            if (state != null && webView.restoreState(state) != null) return;
            showSplash();
            webView.loadUrl(url);
        } catch (Exception ex) {
            new AlertDialog.Builder(this).setTitle("RWV could not start")
                .setMessage(ex.getMessage()).setPositiveButton("Close", (d, w) -> finish())
                .setOnCancelListener(d -> finish()).show();
        }
    }

    private void applyFullscreen() {
        View decor = getWindow().getDecorView();
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                if (fullscreen) controller.hide(WindowInsets.Type.systemBars());
                else controller.show(WindowInsets.Type.systemBars());
            }
        } else {
            int mask = View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
            int flags = decor.getSystemUiVisibility();
            decor.setSystemUiVisibility(fullscreen ? flags | mask : flags & ~mask);
        }
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applyFullscreen();
    }

    private boolean autofillEnabled() {
        if (Build.VERSION.SDK_INT < 26) return false;
        AutofillManager manager = getSystemService(AutofillManager.class);
        return manager != null && manager.isEnabled();
    }

    private void openAutofillSettings() {
        new AlertDialog.Builder(this).setTitle("Password Autofill")
            .setMessage("Choose a password manager in Android Settings > Passwords or Autofill. The system will offer to save and fill passwords on supported website login forms. RWV never reads or stores your passwords itself.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Settings", (d, w) -> {
                try { startActivity(new Intent(Settings.ACTION_SETTINGS)); }
                catch (RuntimeException ex) { showMessage("Choose an Autofill provider in Android system settings."); }
            }).show();
    }

    private void offerAutofillSetup() {
        if (destroying || isFinishing() || isDestroyed() || Build.VERSION.SDK_INT < 26 || autofillEnabled()) return;
        if (getPreferences(MODE_PRIVATE).getBoolean("autofillSetupOffered", false)) return;
        getPreferences(MODE_PRIVATE).edit().putBoolean("autofillSetupOffered", true).apply();
        openAutofillSettings();
    }

    // Android Back still navigates website history. At its root (or via a hardware
    // Menu key) these native options are available without any permanent chrome.
    private void showBrowserMenu() {
        if (webView == null || isFinishing()) return;
        ArrayList<String> labels = new ArrayList<>();
        ArrayList<Integer> ids = new ArrayList<>();
        if (webView.canGoBack()) { labels.add("Back"); ids.add(1); }
        if (webView.canGoForward()) { labels.add("Forward"); ids.add(2); }
        labels.add("Reload"); ids.add(3);
        labels.add("Open in system browser"); ids.add(4);
        boolean cookies = getPreferences(MODE_PRIVATE).getBoolean("thirdPartyCookies", false);
        labels.add((cookies ? "✓ " : "") + "Allow third-party cookies"); ids.add(5);
        if (Build.VERSION.SDK_INT >= 26) { labels.add("Password Autofill settings"); ids.add(6); }
        labels.add("Clear website data"); ids.add(7);
        labels.add(fullscreen ? "Exit fullscreen" : "Enter fullscreen"); ids.add(8);
        new AlertDialog.Builder(this).setTitle("Browser options")
            .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                switch (ids.get(which)) {
                    case 1: if (webView.canGoBack()) webView.goBack(); break;
                    case 2: if (webView.canGoForward()) webView.goForward(); break;
                    case 3: webView.reload(); break;
                    case 4:
                        if (Navigation.isWebUrl(webView.getUrl())) {
                            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(webView.getUrl()))); }
                            catch (ActivityNotFoundException ex) { showMessage("No browser is installed."); }
                        }
                        break;
                    case 5:
                        boolean enabled = !getPreferences(MODE_PRIVATE).getBoolean("thirdPartyCookies", false);
                        getPreferences(MODE_PRIVATE).edit().putBoolean("thirdPartyCookies", enabled).apply();
                        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, enabled);
                        showMessage("Cookie preference saved. Reload the website. Newly opened website windows will also use this setting.");
                        break;
                    case 6: openAutofillSettings(); break;
                    case 7: confirmClearWebsiteData(); break;
                    case 8: fullscreen = !fullscreen; applyFullscreen(); break;
                }
            })
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Exit app", (d, w) -> finish()).show();
    }

    private void confirmClearWebsiteData() {
        new AlertDialog.Builder(this).setTitle("Clear cookies and website data?")
            .setMessage("This clears cookies, supported website storage, cache and history. For a full reset use Android App info > Storage > Clear storage. System Autofill passwords are not removed.")
            .setNegativeButton("Cancel", null).setPositiveButton("Clear", (d, w) -> {
                for (AlertDialog popup : new HashSet<>(popups)) popup.dismiss();
                webView.stopLoading();
                android.webkit.WebStorage.getInstance().deleteAllData();
                GeolocationPermissions.getInstance().clearAll();
                android.webkit.WebViewDatabase.getInstance(this).clearHttpAuthUsernamePassword();
                webView.clearCache(true); webView.clearHistory();
                CookieManager.getInstance().removeAllCookies(removed -> {
                    CookieManager.getInstance().flush();
                    if (!isFinishing() && !isDestroyed() && webView != null) webView.reload();
                });
            }).show();
    }

    @Override public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) { showBrowserMenu(); return true; }
        return super.onKeyUp(keyCode, event);
    }
    private void showMessage(String message) {
        new AlertDialog.Builder(this).setMessage(message).setPositiveButton("OK", null).show();
    }

    private void configureWebView(WebView view, String signature, AlertDialog popup) {
        WebSettings settings = view.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        // Honor responsive viewport declarations and normal HTTPS CDN resources.
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setBlockNetworkLoads(false);
        settings.setDatabaseEnabled(true); // Legacy WebSQL only where the engine still supports it.
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSupportMultipleWindows(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setGeolocationEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        // Keep the modern target-SDK third-party cookie default; do not weaken privacy globally.
        cookies.setAcceptThirdPartyCookies(view, getPreferences(MODE_PRIVATE).getBoolean("thirdPartyCookies", false));
        if (Build.VERSION.SDK_INT >= 26) {
            // Let the OS/provider discover virtual HTML fields and manage its own save/fill UI.
            view.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_YES);
        }
        WebView.setWebContentsDebuggingEnabled(false);
        view.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) {
                return !Navigation.isWebUrl(request.getUrl().toString());
            }
            @Override public boolean shouldOverrideUrlLoading(WebView v, String target) {
                return !Navigation.isWebUrl(target);
            }
            @Override public void onPageCommitVisible(WebView v, String target) {
                if (v == webView) { pageReady = true; maybeDismissSplash(); }
            }
            @Override public void onPageFinished(WebView v, String target) {
                if (Navigation.isWebUrl(target)) {
                    v.evaluateJavascript(signature, null);
                    if (popup != null) popup.setTitle(Uri.parse(target).getHost());
                }
                CookieManager.getInstance().flush();
            }
        });
        view.setDownloadListener((url, agent, disposition, mime, size) -> confirmDownload(url, agent, disposition, mime));
        view.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                try { startActivityForResult(params.createIntent(), PICK_FILE); }
                catch (ActivityNotFoundException | SecurityException ex) {
                    fileCallback.onReceiveValue(null); fileCallback = null;
                }
                return true;
            }
            @Override public boolean onCreateWindow(WebView parent, boolean dialog, boolean gesture, Message result) {
                if (!gesture || popups.size() >= 3 || isFinishing()) return false;
                WebView child = new WebView(MainActivity.this);
                AlertDialog window = new AlertDialog.Builder(MainActivity.this).setTitle("Website window")
                    .setView(child).setNegativeButton("Close", (d, w) -> {}).create();
                configureWebView(child, signature, window);
                window.setOnDismissListener(d -> {
                    popups.remove(window); child.stopLoading(); child.destroy();
                    CookieManager.getInstance().flush();
                });
                popups.add(window); window.show();
                if (window.getWindow() != null) window.getWindow().setLayout(-1, -1);
                ((WebView.WebViewTransport) result.obj).setWebView(child);
                result.sendToTarget();
                return true;
            }
            @Override public void onCloseWindow(WebView child) {
                if (popup != null) popup.dismiss();
            }
            @Override public void onPermissionRequest(PermissionRequest request) {
                runOnUiThread(() -> requestMedia(view, request));
            }
            @Override public void onPermissionRequestCanceled(PermissionRequest request) {
                if (pendingMediaRequest == request) pendingMediaRequest = null;
            }
            @Override public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                requestLocation(view, origin, callback);
            }
        });
    }

    private boolean active(WebView view, String origin) {
        return !isFinishing() && !isDestroyed() && view.isAttachedToWindow()
            && BrowserPolicy.sameSecureOrigin(view.getUrl(), origin);
    }

    private void askRuntime(String[] permissions, Runnable callback) {
        ArrayList<String> missing = new ArrayList<>();
        for (String permission : permissions) if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) missing.add(permission);
        if (missing.isEmpty()) { callback.run(); return; }
        pendingPermission = callback;
        requestPermissions(missing.toArray(new String[0]), WEB_PERMISSION);
    }
    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == WEB_PERMISSION) {
            Runnable callback = pendingPermission; pendingPermission = null;
            if (callback != null) callback.run();
        }
    }
    private void requestMedia(WebView view, PermissionRequest request) {
        String origin = request.getOrigin().toString();
        if (!active(view, origin) || pendingPermission != null || pendingMediaRequest != null) { request.deny(); return; }
        ArrayList<String> resources = new ArrayList<>(), permissions = new ArrayList<>();
        for (String resource : request.getResources()) {
            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)) {
                resources.add(resource); permissions.add(Manifest.permission.CAMERA);
            } else if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) {
                resources.add(resource); permissions.add(Manifest.permission.RECORD_AUDIO);
            }
        }
        if (resources.isEmpty()) { request.deny(); return; }
        pendingMediaRequest = request;
        new AlertDialog.Builder(this).setTitle("Website permission")
            .setMessage(BrowserPolicy.secureOrigin(origin) + " requests " + (resources.size() == 2 ? "camera and microphone" : (permissions.contains(Manifest.permission.CAMERA) ? "camera" : "microphone")) + ". Allow this request?")
            .setNegativeButton("Deny", (d, w) -> denyMedia(request))
            .setOnCancelListener(d -> denyMedia(request))
            .setPositiveButton("Allow", (d, w) -> {
                if (pendingMediaRequest != request) return;
                if (!active(view, origin) || pendingPermission != null) { denyMedia(request); return; }
                askRuntime(permissions.toArray(new String[0]), () -> {
                    if (pendingMediaRequest != request) return;
                    pendingMediaRequest = null;
                    ArrayList<String> granted = new ArrayList<>();
                    for (int i = 0; i < resources.size(); i++) if (checkSelfPermission(permissions.get(i)) == PackageManager.PERMISSION_GRANTED) granted.add(resources.get(i));
                    if (!active(view, origin) || granted.isEmpty()) request.deny(); else request.grant(granted.toArray(new String[0]));
                });
            }).show();
    }
    private void denyMedia(PermissionRequest request) {
        if (pendingMediaRequest == request) { pendingMediaRequest = null; request.deny(); }
    }
    private void requestLocation(WebView view, String origin, GeolocationPermissions.Callback callback) {
        if (!active(view, origin) || pendingPermission != null || pendingMediaRequest != null) { callback.invoke(origin, false, false); return; }
        new AlertDialog.Builder(this).setTitle("Location permission")
            .setMessage(BrowserPolicy.secureOrigin(origin) + " requests your location. Allow this request?")
            .setNegativeButton("Deny", (d, w) -> callback.invoke(origin, false, false))
            .setOnCancelListener(d -> callback.invoke(origin, false, false))
            .setPositiveButton("Allow", (d, w) -> {
                if (!active(view, origin) || pendingPermission != null) { callback.invoke(origin, false, false); return; }
                askRuntime(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION}, () ->
                    callback.invoke(origin, active(view, origin) && (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                        || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED), false));
            }).show();
    }
    private void confirmDownload(String url, String agent, String disposition, String mime) {
        if (!Navigation.isWebUrl(url)) {
            new AlertDialog.Builder(this).setMessage("This download type is not supported by Android Download Manager.").setPositiveButton("OK", null).show(); return;
        }
        String filename = URLUtil.guessFileName(url, disposition, mime).replaceAll("[^a-zA-Z0-9._ -]", "_");
        if (filename.isEmpty() || filename.equals(".") || filename.equals("..")) filename = "download";
        if (filename.length() > 100) filename = filename.substring(0, 100);
        final String name = filename;
        new AlertDialog.Builder(this).setTitle("Download file?").setMessage(name + "\nFrom " + Uri.parse(url).getHost())
            .setNegativeButton("Cancel", null).setPositiveButton("Download", (d, w) -> {
                try {
                    DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
                    if (agent != null) request.addRequestHeader("User-Agent", agent);
                    // DownloadManager follows redirects; never forward session cookies or authorization headers.
                    request.setTitle(name).setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                    if (mime != null && !mime.isEmpty()) request.setMimeType(mime);
                    request.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, System.currentTimeMillis() + "-" + name);
                    ((DownloadManager) getSystemService(DOWNLOAD_SERVICE)).enqueue(request);
                } catch (RuntimeException ex) {
                    new AlertDialog.Builder(this).setMessage("Could not start download.").setPositiveButton("OK", null).show();
                }
            }).show();
    }

    private void showSplash() throws Exception {
        splash = new WebView(this);
        splash.setBackgroundColor(Color.WHITE);
        splash.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        if (Build.VERSION.SDK_INT >= 26) splash.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        splash.setVerticalScrollBarEnabled(false);
        splash.setHorizontalScrollBarEnabled(false);
        WebSettings settings = splash.getSettings();
        settings.setJavaScriptEnabled(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setBlockNetworkLoads(false);
        settings.setUseWideViewPort(true);
        settings.setTextZoom(100);
        splash.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) { return true; }
            @Override public boolean shouldOverrideUrlLoading(WebView v, String target) { return true; }
            @Override public void onPageFinished(WebView v, String target) {
                if (v == splash && !splashReady) {
                    splashReady = true;
                    splashStarted = SystemClock.uptimeMillis();
                    maybeDismissSplash();
                }
            }
        });
        // The splash is a separate, script-free WebView. The synthetic HTTPS base
        // allows normal CDN font CORS and avoids adding splash pages to site history.
        root.addView(splash, new FrameLayout.LayoutParams(-1, -1));
        splash.loadDataWithBaseURL("https://rwv.invalid/", readAsset("splash.html"), "text/html", "UTF-8", null);
        handler.postDelayed(splashTimeout, SPLASH_MAX_MS);
    }

    private void maybeDismissSplash() {
        if (splash == null || !splashReady || !pageReady) return;
        long remaining = SPLASH_MIN_MS - (SystemClock.uptimeMillis() - splashStarted);
        if (remaining > 0) handler.postDelayed(this::maybeDismissSplash, remaining);
        else dismissSplash();
    }

    private void dismissSplash() {
        if (splash == null) return;
        handler.removeCallbacks(splashTimeout);
        WebView oldSplash = splash;
        splash = null;
        root.removeView(oldSplash);
        oldSplash.stopLoading();
        oldSplash.destroy();
        if (!destroying) handler.post(this::offerAutofillSetup);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == PICK_FILE && fileCallback != null) {
            fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result, data));
            fileCallback = null;
        }
    }
    @Override public void onBackPressed() {
        if (splash != null) { dismissSplash(); return; }
        if (webView != null && webView.canGoBack()) webView.goBack();
        else showBrowserMenu();
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("rwv.fullscreen", fullscreen);
        if (webView != null && splash == null) webView.saveState(state);
        super.onSaveInstanceState(state);
    }
    @Override protected void onPause() {
        if (splash != null) splash.onPause();
        if (webView != null) { webView.onPause(); CookieManager.getInstance().flush(); }
        super.onPause();
    }
    @Override protected void onResume() {
        super.onResume();
        if (splash != null) splash.onResume();
        if (webView != null) webView.onResume();
    }
    @Override protected void onDestroy() {
        destroying = true;
        handler.removeCallbacksAndMessages(null);
        dismissSplash();
        pendingPermission = null;
        if (pendingMediaRequest != null) denyMedia(pendingMediaRequest);
        for (AlertDialog popup : new HashSet<>(popups)) popup.dismiss();
        CookieManager.getInstance().flush();
        if (fileCallback != null) { fileCallback.onReceiveValue(null); fileCallback = null; }
        if (webView != null) {
            webView.stopLoading();
            if (webView.getParent() instanceof ViewGroup) ((ViewGroup) webView.getParent()).removeView(webView);
            webView.destroy(); webView = null;
        }
        super.onDestroy();
    }
}
