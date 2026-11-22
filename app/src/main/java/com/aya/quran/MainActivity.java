package com.aya.quran;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.KeyEvent;
import android.view.Window;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.webkit.WebViewAssetLoader;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

/**
 * Thin WebView shell around the offline Quran app in assets/index.html.
 * Assets are served from https://appassets.androidplatform.net so that
 * localStorage / IndexedDB (reciter downloads) run on a secure origin.
 */
public class MainActivity extends Activity {

    private static final int REQ_FILE = 1001;
    private static final String HOME = "https://appassets.androidplatform.net/assets/index.html";

    private WebView web;
    private ValueCallback<Uri[]> pendingFiles;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        Window win = getWindow();
        win.setStatusBarColor(Color.rgb(11, 61, 46));
        win.setNavigationBarColor(Color.rgb(11, 61, 46));

        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setAllowFileAccess(false);

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                return loader.shouldInterceptRequest(r.getUrl());
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                v.evaluateJavascript(SAVE_HOOK, null);
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (pendingFiles != null) pendingFiles.onReceiveValue(null);
                pendingFiles = cb;
                try {
                    startActivityForResult(params.createIntent(), REQ_FILE);
                    return true;
                } catch (Exception e) {
                    pendingFiles = null;
                    return false;
                }
            }
        });

        web.addJavascriptInterface(new Saver(), "AndroidSaver");
        web.loadUrl(HOME);
    }

    /** Backup export: a blob link turns into a real file in Downloads. */
    private static final String SAVE_HOOK =
            "(function(){try{var oc=HTMLAnchorElement.prototype.click;" +
            "if(oc.__aya)return;" +
            "HTMLAnchorElement.prototype.click=function(){" +
            "if(this.download&&this.href&&this.href.indexOf('blob:')===0){" +
            "var nm=this.download;" +
            "fetch(this.href).then(function(r){return r.blob()}).then(function(b){" +
            "var fr=new FileReader();" +
            "fr.onload=function(){try{window.AndroidSaver.save(nm,String(fr.result).split(',')[1]);}catch(e){}};" +
            "fr.readAsDataURL(b);});" +
            "return;}" +
            "return oc.apply(this,arguments);};" +
            "HTMLAnchorElement.prototype.click.__aya=1;}catch(e){}})();";

    public class Saver {
        @JavascriptInterface
        public void save(final String name, final String b64) {
            final String safe = (name == null ? "backup.json" : name).replaceAll("[^A-Za-z0-9._-]", "_");
            try {
                byte[] data = Base64.decode(b64, Base64.DEFAULT);
                String where;
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.MediaColumns.DISPLAY_NAME, safe);
                    cv.put(MediaStore.MediaColumns.MIME_TYPE, "application/json");
                    cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                    Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                    if (uri == null) throw new IllegalStateException("no uri");
                    OutputStream os = getContentResolver().openOutputStream(uri);
                    os.write(data);
                    os.close();
                    where = "Downloads/" + safe;
                } else {
                    File f = new File(getExternalFilesDir(null), safe);
                    FileOutputStream fo = new FileOutputStream(f);
                    fo.write(data);
                    fo.close();
                    where = f.getAbsolutePath();
                }
                toast("اتحفظ: " + where);
            } catch (Exception e) {
                toast("معلش، الملف ما اتحفظش");
            }
        }
    }

    private void toast(final String msg) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show();
            }
        });
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == REQ_FILE && pendingFiles != null) {
            pendingFiles.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, data));
            pendingFiles = null;
            return;
        }
        super.onActivityResult(req, res, data);
    }

    @Override
    public boolean onKeyDown(int key, KeyEvent ev) {
        if (key == KeyEvent.KEYCODE_BACK && web != null && web.canGoBack()) {
            web.goBack();
            return true;
        }
        return super.onKeyDown(key, ev);
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.removeJavascriptInterface("AndroidSaver");
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
