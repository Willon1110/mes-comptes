package ch.mescomptes.app;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Mes Comptes : l'appli web dans une fenêtre Android, sans barre d'adresse. */
public class MainActivity extends Activity {
    private static final String APP_URL = "https://willon1110.github.io/mes-comptes/";
    private static final String APP_HOST = "willon1110.github.io";
    private static final int FILE_REQ = 42;

    private WebView web;
    private ValueCallback<Uri[]> fileCallback;
    private String pendingOpen;
    private boolean loaded;
    private volatile boolean ready;
    private final StringBuilder errors = new StringBuilder();
    private static int crashes = 0;
    private static long lastCrash = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#1B2350"));
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setUserAgentString(s.getUserAgentString() + " MesComptesApp/1");

        web.addJavascriptInterface(new Bridge(), "AndroidBridge");

        pendingOpen = getIntent() != null ? getIntent().getStringExtra("open") : null;
        try { NotifReceiver.channel(this); NotifReceiver.schedule(this); } catch (Throwable ignored) { }

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
                // Le moteur web a planté : on recrée la page au lieu de fermer l'appli.
                try { ((android.view.ViewGroup) view.getParent()).removeView(view); view.destroy(); } catch (Throwable ignored) { }
                long now = System.currentTimeMillis();
                crashes = now - lastCrash < 60000 ? crashes + 1 : 1; lastCrash = now;
                if (crashes >= 2) { problem("Le moteur web d'Android (Android System WebView) a planté plusieurs fois en ouvrant l'appli."); return true; }
                recreate();
                return true;
            }
            @Override
            public void onPageFinished(WebView view, String url) {
                loaded = true;
                deliverOpen();
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (APP_HOST.equals(u.getHost())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(android.webkit.ConsoleMessage m) {
                if (m.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR && errors.length() < 1500)
                    errors.append(m.message()).append(" (ligne ").append(m.lineNumber()).append(")\n");
                return false;
            }
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = cb;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                try { startActivityForResult(Intent.createChooser(i, "Choisir une sauvegarde"), FILE_REQ); }
                catch (Exception e) { fileCallback = null; return false; }
                return true;
            }
        });

        web.loadUrl(APP_URL);
        web.postDelayed(this::watchdog, 15000);
    }

    private static boolean healed = false;
    private String stage = "?";

    private void watchdog() {
        if (ready || isFinishing()) return;
        try { web.evaluateJavascript("String(window.__stage||'aucune')", v -> { if (v != null) stage = v; afterStage(); }); }
        catch (Throwable t) { afterStage(); }
    }

    private void afterStage() {
        if (ready || isFinishing()) return;
        if (!healed) {
            // 1er essai automatique : on vide le cache web (sans toucher aux données) et on recharge.
            healed = true;
            try { web.clearCache(true); } catch (Throwable ignored) { }
            web.loadUrl(APP_URL);
            web.postDelayed(this::watchdog, 15000);
            return;
        }
        problem("L'appli ne s'est pas affichée.");
    }

    private void resetApp() {
        try { android.webkit.WebStorage.getInstance().deleteAllData(); } catch (Throwable ignored) { }
        try { android.webkit.CookieManager.getInstance().removeAllCookies(null); android.webkit.CookieManager.getInstance().flush(); } catch (Throwable ignored) { }
        try { web.clearCache(true); web.clearHistory(); } catch (Throwable ignored) { }
        try { deleteDatabase("webview.db"); deleteDatabase("webviewCache.db"); } catch (Throwable ignored) { }
        crashes = 0; healed = false; recreate();
    }

    private void problem(String why) {
        if (isFinishing()) return;
        String det = "\n\nDétail technique : étape " + stage + (errors.length() > 0 ? "\n" + errors : "");
        new android.app.AlertDialog.Builder(this)
            .setTitle("Mes Comptes ne s'ouvre pas")
            .setMessage(why + "\n\n• Réessayer d'abord.\n• Sinon « Réinitialiser » (pas besoin de désinstaller).\n• Si ça revient souvent, mets à jour « Android System WebView »." + det)
            .setPositiveButton("Réessayer", (d, w) -> { crashes = 0; recreate(); })
            .setNeutralButton("Mettre à jour WebView", (d, w) -> { try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.webview"))); } catch (Exception e) { try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.webview"))); } catch (Exception ignored) { } } })
            .setNegativeButton("Réinitialiser", (d, w) -> new android.app.AlertDialog.Builder(this)
                .setTitle("Réinitialiser l'appli ?")
                .setMessage("Ça remplace la désinstallation : l'appli oublie la connexion sur ce téléphone. Tes données restent en sécurité dans ta base ; il faudra juste te reconnecter et retaper ta phrase secrète.")
                .setPositiveButton("Réinitialiser", (d2, w2) -> resetApp())
                .setNegativeButton("Annuler", (d2, w2) -> problem(why))
                .setCancelable(false).show())
            .setCancelable(false).show();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String o = intent.getStringExtra("open");
        if (o != null) { pendingOpen = o; if (loaded) deliverOpen(); }
    }

    private void deliverOpen() {
        if (pendingOpen == null || web == null) return;
        final String o = pendingOpen; pendingOpen = null;
        web.postDelayed(() -> web.evaluateJavascript("setTimeout(function(){ if(window.__appOpen) window.__appOpen('" + o + "'); }, 1500)", null), 300);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == FILE_REQ && fileCallback != null) {
            Uri[] res = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null) res = new Uri[]{data.getData()};
            fileCallback.onReceiveValue(res);
            fileCallback = null;
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("(window.__appBack?window.__appBack():false)", value -> {
            if (!"true".equals(value)) moveTaskToBack(true);
        });
    }

    /** Pont appelé par l'appli web : enregistre les fichiers de sauvegarde dans Téléchargements. */
    private class Bridge {
        @JavascriptInterface
        public void saveFile(String name, String mime, String content) {
            String msg;
            try {
                byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.Downloads.DISPLAY_NAME, name);
                    v.put(MediaStore.Downloads.MIME_TYPE, mime == null || mime.isEmpty() ? "application/octet-stream" : mime);
                    v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                    Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                    try (OutputStream os = getContentResolver().openOutputStream(uri)) { os.write(bytes); }
                    msg = "Enregistré dans Téléchargements : " + name;
                } else {
                    File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                    File f = new File(dir, name);
                    try (FileOutputStream os = new FileOutputStream(f)) { os.write(bytes); }
                    msg = "Enregistré : " + f.getAbsolutePath();
                }
            } catch (Exception e) {
                msg = "Impossible d'enregistrer le fichier";
            }
            final String m = msg;
            runOnUiThread(() -> Toast.makeText(MainActivity.this, m, Toast.LENGTH_LONG).show());
        }

        @JavascriptInterface
        public boolean isApp() { return true; }

        @JavascriptInterface
        public void ready() { ready = true; crashes = 0; }
        @JavascriptInterface
        public void reset() { runOnUiThread(MainActivity.this::resetApp); }

        @JavascriptInterface
        public void setNotifs(String json) {
            try {
                getSharedPreferences(NotifReceiver.PREFS, MODE_PRIVATE).edit().putString("cfg", json).apply();
                NotifReceiver.schedule(MainActivity.this);
            } catch (Throwable ignored) { }
        }

        @JavascriptInterface
        public void markEntered(String date) {
            getSharedPreferences(NotifReceiver.PREFS, MODE_PRIVATE).edit().putString("entered", date).apply();
        }

        @JavascriptInterface
        public void askPermission() {
            try { if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                runOnUiThread(() -> { try { requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 7); } catch (Throwable ignored) { } });
            } } catch (Throwable ignored) { }
        }

        @JavascriptInterface
        public void testNotif() {
            try { NotifReceiver.show(MainActivity.this, 3, "Mes Comptes", "Les notifications fonctionnent. Touche-moi pour ouvrir le + .", "add"); } catch (Throwable ignored) { }
        }
    }
}
