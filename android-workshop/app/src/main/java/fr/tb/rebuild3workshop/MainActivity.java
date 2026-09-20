package fr.tb.rebuild3workshop;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {
    private static final String BASE_URL = "https://thomasbonsirven.github.io/rebuild3/";
    private static final String MANIFEST_URL = BASE_URL + "manifest.json";
    private static final String REBUILD_PACKAGE = "air.com.sarahnorthway.rebuild3";

    private TextView statusView;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(buildUi());
        refreshStatus();
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(32));
        scroll.addView(root);

        root.addView(text("Rebuild 3 Workshop", 26, true));

        TextView intro = text(
            "V0.5 — Overlay + clavier spécialisé\n\n" +
            "L'overlay affiche la progression au-dessus de Rebuild 3. " +
            "Le clavier écrit directement dans Install Mod par petits blocs, " +
            "sans gros collage presse-papiers.",
            16, false);
        intro.setPadding(0, dp(8), 0, dp(16));
        root.addView(intro);

        root.addView(section("1. Overlay"));
        root.addView(button("Autoriser l'overlay", v -> openOverlayPermission()));
        root.addView(button("Afficher l'overlay", v -> startOverlay()));
        root.addView(button("Fermer l'overlay", v -> stopOverlay()));

        root.addView(section("2. Activer le clavier"));
        root.addView(button("Activer Rebuild Workshop Keyboard", v ->
            startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))));
        root.addView(button("Choisir Rebuild Workshop Keyboard", v -> {
            InputMethodManager imm =
                (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            imm.showInputMethodPicker();
        }));

        root.addView(section("3. Télécharger"));
        root.addView(button("Pack Android multi-fichiers — sécurité", v ->
            downloadFromManifest(DownloadMode.ANDROID_MULTI)));
        root.addView(button("TEST UI très court", v ->
            downloadFromManifest(DownloadMode.UI_TEST)));

        root.addView(section("4. Ouvrir le jeu"));
        root.addView(button("Ouvrir Rebuild 3", v -> launchRebuild()));
        root.addView(button("Revenir au premier fichier", v -> {
            AppState.setCurrentPartIndex(this, 0);
            refreshStatus();
            Toast.makeText(this, "Fichier 1 sélectionné", Toast.LENGTH_SHORT).show();
        }));

        root.addView(section("État"));
        statusView = text("", 15, false);
        statusView.setTextColor(Color.DKGRAY);
        root.addView(statusView);

        root.addView(text(
            "\nUtilisation :\n" +
            "1) Autorise puis affiche l'overlay.\n" +
            "2) Active et sélectionne le clavier Workshop.\n" +
            "3) Télécharge le pack Android multi-fichiers.\n" +
            "4) Ouvre Rebuild 3 → Config → Modding → Install Mod.\n" +
            "5) Touche le champ blanc.\n" +
            "6) « Bloc » injecte 128 caractères ; « AUTO » enchaîne les blocs.\n" +
            "7) L'overlay affiche le % de la partie et le % total.\n" +
            "8) À 100 %, valide avec Okay puis utilise ▶ pour le fichier suivant.",
            14, false));

        return scroll;
    }

    private enum DownloadMode {
        DESKTOP_FULL,
        ANDROID_MULTI,
        UI_TEST
    }

    private void downloadFromManifest(DownloadMode mode) {
        statusView.setText("Téléchargement du manifeste...");
        new Thread(() -> {
            try {
                JSONObject manifest = new JSONObject(httpGetText(MANIFEST_URL));
                JSONArray files = new JSONArray();
                String sourceName;

                if (mode == DownloadMode.UI_TEST) {
                    JSONObject probe = manifest.optJSONObject("ui_probe");
                    if (probe == null) {
                        throw new IllegalStateException("ui_probe absent du manifeste");
                    }
                    files.put(new JSONObject().put("name", probe.getString("name")));
                    sourceName = "Diagnostic UI";
                } else if (mode == DownloadMode.DESKTOP_FULL) {
                    JSONObject desktop = manifest.optJSONObject("desktop");
                    JSONObject desktopFile =
                        desktop == null ? null : desktop.optJSONObject("file");
                    if (desktopFile == null) {
                        throw new IllegalStateException(
                            "Version complète pas encore publiée");
                    }
                    files.put(new JSONObject().put(
                        "name", desktopFile.getString("name")));
                    sourceName = "Pack complet clavier";
                } else {
                    JSONObject android = manifest.optJSONObject("android");
                    if (android == null) {
                        throw new IllegalStateException(
                            "Pack Android multi-fichiers absent du manifeste");
                    }
                    JSONArray sourceFiles = android.getJSONArray("files");
                    for (int i = 0; i < sourceFiles.length(); i++) {
                        files.put(sourceFiles.getJSONObject(i));
                    }
                    sourceName = "Pack Android multi-fichiers";
                }

                File dir = AppState.partsDir(this);
                File[] old = dir.listFiles();
                if (old != null) {
                    for (File file : old) file.delete();
                }

                JSONArray names = new JSONArray();

                for (int i = 0; i < files.length(); i++) {
                    String name = files.getJSONObject(i).getString("name");
                    byte[] data = httpGetBytes(BASE_URL + name);
                    File out = new File(dir, new File(name).getName());

                    try (FileOutputStream fos = new FileOutputStream(out)) {
                        fos.write(data);
                    }

                    names.put(out.getName());
                    final int done = i + 1;
                    final int total = files.length();
                    runOnUiThread(() ->
                        statusView.setText("Téléchargement : " + done + "/" + total));
                }

                SharedPreferences.Editor edit = AppState.prefs(this).edit();
                edit.putString(AppState.KEY_PARTS, names.toString());
                edit.putInt(AppState.KEY_CURRENT_PART, 0);
                edit.putString(AppState.KEY_SOURCE, sourceName);
                edit.apply();

                runOnUiThread(() -> {
                    refreshStatus();
                    Toast.makeText(
                        this,
                        names.length() + " fichier(s) prêt(s)",
                        Toast.LENGTH_SHORT
                    ).show();
                });
            } catch (Exception e) {
                runOnUiThread(() ->
                    statusView.setText("Erreur téléchargement : " + e.getMessage()));
            }
        }).start();
    }

    private void openOverlayPermission() {
        if (Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Overlay déjà autorisé", Toast.LENGTH_SHORT).show();
            return;
        }

        Intent intent = new Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:" + getPackageName())
        );
        startActivity(intent);
    }

    private void startOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Autorise d'abord l'overlay", Toast.LENGTH_LONG).show();
            openOverlayPermission();
            return;
        }

        Intent intent = new Intent(this, OverlayService.class);
        intent.setAction(OverlayService.ACTION_START);

        if (android.os.Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private void stopOverlay() {
        Intent intent = new Intent(this, OverlayService.class);
        stopService(intent);
    }

    private void launchRebuild() {
        Intent intent = getPackageManager().getLaunchIntentForPackage(REBUILD_PACKAGE);
        if (intent == null) {
            Toast.makeText(this, "Rebuild 3 introuvable", Toast.LENGTH_LONG).show();
            return;
        }
        startActivity(intent);
    }

    private void refreshStatus() {
        if (statusView == null) return;

        JSONArray parts = AppState.getParts(this);
        String source = AppState.prefs(this).getString(
            AppState.KEY_SOURCE, "Aucun fichier");

        String selected = "-";
        if (parts.length() > 0) {
            selected =
                (AppState.getCurrentPartIndex(this) + 1) + "/" + parts.length();
        }

        statusView.setText(
            "Source : " + source +
            "\nFichiers : " + parts.length() +
            "\nSélection : " + selected +
            "\nOverlay : " +
            (Settings.canDrawOverlays(this) ? "autorisé" : "non autorisé"));
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private TextView section(String value) {
        TextView view = text(value, 19, true);
        view.setPadding(0, dp(22), 0, dp(8));
        return view;
    }

    private TextView text(String value, int sp, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(Color.rgb(25, 25, 25));
        if (bold) {
            view.setTypeface(null, android.graphics.Typeface.BOLD);
        }
        return view;
    }

    private Button button(String value, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(value);
        button.setAllCaps(false);
        button.setOnClickListener(listener);

        LinearLayout.LayoutParams params =
            new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, dp(4), 0, dp(4));
        button.setLayoutParams(params);
        return button;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static String httpGetText(String url) throws Exception {
        return new String(httpGetBytes(url), StandardCharsets.UTF_8);
    }

    private static byte[] httpGetBytes(String url) throws Exception {
        HttpURLConnection connection =
            (HttpURLConnection) new URL(url).openConnection();

        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        connection.setRequestProperty(
            "User-Agent", "Rebuild3Workshop-Android/0.5");
        connection.connect();

        if (connection.getResponseCode() != 200) {
            throw new IllegalStateException(
                "HTTP " + connection.getResponseCode() + " pour " + url);
        }

        try (
            BufferedInputStream in =
                new BufferedInputStream(connection.getInputStream());
            ByteArrayOutputStream out = new ByteArrayOutputStream()
        ) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } finally {
            connection.disconnect();
        }
    }
}
