package fr.tb.rebuild3workshop;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
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
            "V0.2 — Clavier spécialisé\n\n" +
            "Le clavier écrit directement dans le champ Install Mod par petits blocs. " +
            "Pas d'overlay et pas de gros collage presse-papiers.", 16, false);
        intro.setPadding(0, dp(8), 0, dp(16));
        root.addView(intro);

        root.addView(section("1. Activer le clavier"));
        root.addView(button("Activer Rebuild Workshop Keyboard", v ->
            startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))));
        root.addView(button("Choisir Rebuild Workshop Keyboard", v -> {
            InputMethodManager imm =
                (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            imm.showInputMethodPicker();
        }));

        root.addView(section("2. Télécharger"));
        root.addView(button("Pack complet clavier — 1 fichier", v ->
            downloadFromManifest(DownloadMode.DESKTOP_FULL)));
        root.addView(button("Pack Android — plusieurs fichiers", v ->
            downloadFromManifest(DownloadMode.ANDROID_MULTI)));
        root.addView(button("TEST UI très court", v ->
            downloadFromManifest(DownloadMode.UI_TEST)));

        root.addView(section("3. Ouvrir le jeu"));
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
            "1) Télécharge un pack.\n" +
            "2) Ouvre Rebuild 3 → Config → Modding → Install Mod.\n" +
            "3) Touche le champ blanc puis sélectionne le clavier Workshop.\n" +
            "4) Utilise « Bloc » pour injecter doucement ou « AUTO » pour continuer seul.\n" +
            "5) Le clavier affiche la progression.\n" +
            "6) Avec le pack multi-fichiers, valide Okay puis utilise > pour passer au suivant.",
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
                            "Version Desktop complète pas encore publiée");
                    }
                    files.put(new JSONObject().put(
                        "name", desktopFile.getString("name")));
                    sourceName = "Pack complet clavier";
                } else {
                    JSONObject android = manifest.optJSONObject("android");
                    JSONArray sourceFiles = android != null
                        ? android.getJSONArray("files")
                        : manifest.getJSONArray("files");
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
            "\nSélection : " + selected);
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
        return Math.round(
            value * getResources().getDisplayMetrics().density);
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
            "User-Agent", "Rebuild3Workshop-Android/0.2");
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
