package io.rwv.webview;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;
import android.os.Message;
import android.provider.Settings;
import android.widget.Button;
import android.widget.PopupMenu;
import android.view.View;
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
import android.view.Gravity;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
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
    private LinearLayout splash;

    private String readAsset(String name) throws Exception {
        try (InputStream in = getAssets().open(name); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            JSONObject config = new JSONObject(readAsset("config.json"));
            final String url = config.getString("website_url");
            final String signature = readAsset("console.js");
            if (!Navigation.isWebUrl(url)) throw new IllegalArgumentException("Invalid website URL");
            root = new FrameLayout(this);
            root.setBackgroundColor(Color.WHITE);
            webView = new WebView(this);
            root.addView(webView, new FrameLayout.LayoutParams(-1, -1));
            configureWebView(webView, signature, null);
            Button browserMenu = new Button(this);
            browserMenu.setText("⋮"); browserMenu.setContentDescription("Browser options");
            FrameLayout.LayoutParams menuParams = new FrameLayout.LayoutParams(dp(44), dp(48), Gravity.TOP | Gravity.END);
            // Reserve a narrow top strip so the native menu cannot cover website controls.
            FrameLayout.LayoutParams pageParams = new FrameLayout.LayoutParams(-1, -1);
            pageParams.topMargin = dp(48); webView.setLayoutParams(pageParams);
            root.addView(browserMenu, menuParams);
            browserMenu.setOnClickListener(v -> showBrowserMenu(browserMenu));
            setContentView(root);
            if (state != null && webView.restoreState(state) != null) return;
            showSplash(config.getString("name"));
            handler.postDelayed(() -> {
                if (isFinishing() || isDestroyed()) return;
                root.removeView(splash);
                splash = null;
                webView.loadUrl(url);
            }, 1400);
        } catch (Exception ex) {
            new AlertDialog.Builder(this).setTitle("RWV could not start")
                .setMessage(ex.getMessage()).setPositiveButton("Close", (d, w) -> finish())
                .setOnCancelListener(d -> finish()).show();
        }
    }

    private void showBrowserMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, "Back").setEnabled(webView.canGoBack());
        menu.getMenu().add(0, 2, 1, "Forward").setEnabled(webView.canGoForward());
        menu.getMenu().add(0, 3, 2, "Reload");
        menu.getMenu().add(0, 4, 3, "Open in system browser");
        menu.getMenu().add(0, 5, 4, "Allow third-party cookies").setCheckable(true)
            .setChecked(getPreferences(MODE_PRIVATE).getBoolean("thirdPartyCookies", false));
        if (Build.VERSION.SDK_INT >= 26) menu.getMenu().add(0, 6, 5, "Set up password Autofill");
        menu.getMenu().add(0, 7, 6, "Clear website data");
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
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
                    boolean enabled = !item.isChecked();
                    getPreferences(MODE_PRIVATE).edit().putBoolean("thirdPartyCookies", enabled).apply();
                    CookieManager.getInstance().setAcceptThirdPartyCookies(webView, enabled);
                    showMessage("Cookie preference saved. Reload the website. Newly opened website windows will also use this setting.");
                    break;
                case 6:
                    if (Build.VERSION.SDK_INT >= 26) {
                        try { showMessage("In Android settings, search for Autofill or Passwords and choose your password manager. Return to the app and tap a login field."); startActivity(new Intent(Settings.ACTION_SETTINGS)); }
                        catch (RuntimeException ex) { showMessage("Choose an Autofill provider in your Android system settings."); }
                    }
                    break;
                case 7:
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
                                if (!isFinishing() && !isDestroyed()) webView.reload();
                            });
                        }).show();
                    break;
                default: return false;
            }
            return true;
        });
        menu.show();
    }
    private void showMessage(String message) {
        new AlertDialog.Builder(this).setMessage(message).setPositiveButton("OK", null).show();
    }

    private void configureWebView(WebView view, String signature, AlertDialog popup) {
        WebSettings settings = view.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
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

    private void showSplash(String name) {
        splash = new LinearLayout(this);
        splash.setOrientation(LinearLayout.VERTICAL);
        splash.setGravity(Gravity.CENTER);
        splash.setPadding(dp(24), dp(24), dp(24), dp(24));
        splash.setBackgroundColor(Color.WHITE);
        ImageView image = new ImageView(this);
        image.setImageResource(R.drawable.rwv_icon);
        splash.addView(image, new LinearLayout.LayoutParams(dp(96), dp(96)));
        TextView title = new TextView(this);
        title.setText(name); title.setGravity(Gravity.CENTER); title.setTextColor(Color.BLACK); title.setTextSize(24);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-1, -2);
        titleParams.topMargin = dp(24);
        splash.addView(title, titleParams);
        ProgressBar progress = new ProgressBar(this);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(dp(28), dp(28));
        progressParams.topMargin = dp(20);
        splash.addView(progress, progressParams);
        TextView credit = new TextView(this);
        credit.setText("powered by Jelofen1962"); credit.setTextColor(Color.DKGRAY); credit.setTextSize(12);
        LinearLayout.LayoutParams creditParams = new LinearLayout.LayoutParams(-2, -2);
        creditParams.topMargin = dp(24);
        splash.addView(credit, creditParams);
        root.addView(splash, new FrameLayout.LayoutParams(-1, -1));
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == PICK_FILE && fileCallback != null) {
            fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result, data));
            fileCallback = null;
        }
    }
    @Override public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        if (webView != null && splash == null) webView.saveState(state);
        super.onSaveInstanceState(state);
    }
    @Override protected void onPause() {
        if (webView != null) { webView.onPause(); CookieManager.getInstance().flush(); }
        super.onPause();
    }
    @Override protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
    }
    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
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
