package io.rwv.webview;

import android.app.Activity;
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
            WebSettings settings = webView.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setDomStorageEnabled(true);
            settings.setAllowFileAccess(false);
            settings.setAllowContentAccess(false);
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
            settings.setSupportMultipleWindows(false);
            settings.setJavaScriptCanOpenWindowsAutomatically(false);
            CookieManager.getInstance().setAcceptCookie(true);
            WebView.setWebContentsDebuggingEnabled(false);
            webView.setWebViewClient(new WebViewClient() {
                @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                    return !Navigation.isWebUrl(request.getUrl().toString());
                }
                @Override public boolean shouldOverrideUrlLoading(WebView view, String target) {
                    return !Navigation.isWebUrl(target);
                }
                @Override public void onPageFinished(WebView view, String target) {
                    if (Navigation.isWebUrl(target)) view.evaluateJavascript(signature, null);
                }
            });
            webView.setWebChromeClient(new WebChromeClient() {
                @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                    if (fileCallback != null) fileCallback.onReceiveValue(null);
                    fileCallback = callback;
                    try { startActivityForResult(params.createIntent(), PICK_FILE); }
                    catch (ActivityNotFoundException | SecurityException ex) {
                        fileCallback.onReceiveValue(null);
                        fileCallback = null;
                    }
                    return true;
                }
            });
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
        if (fileCallback != null) { fileCallback.onReceiveValue(null); fileCallback = null; }
        if (webView != null) {
            webView.stopLoading();
            if (webView.getParent() instanceof ViewGroup) ((ViewGroup) webView.getParent()).removeView(webView);
            webView.destroy(); webView = null;
        }
        super.onDestroy();
    }
}
