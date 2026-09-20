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

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
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
            "Prototype Overlay v0.1\n\nLe Workshop télécharge les fichiers depuis GitHub. " +
            "Le clavier intégré écrit le mod progressivement dans le champ Install Mod. " +
            "Aucun gros collage presse-papiers.", 16, false);
        intro.setPadding(0, dp(8), 0, dp(16));
        root.addView(intro);

        root.addView(section("1. Autorisations"));
        root.addView(button("Autoriser l'overlay", v -> openOverlayPermission()));
        root.addView(button("Activer le clavier Workshop", v ->
            startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))));
        root.addView(button("Choisir le clavier Workshop", v -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            imm.showInputMethodPicker();
        }));

        root.addView(section("2. Télécharger"));
        root.addView(button("Télécharger le TEST UI", v -> downloadFromManifest(true)));
        root.addView(button("Télécharger le pack Android", v -> downloadFromManifest(false)));

        root.addView(section("3. Lancer"));
        root.addView(button("Afficher l'overlay", v -> startOverlay()));
        root.addView(button("Ouvrir Rebuild 3", v -> launchRebuild()));
        root.addView(button("Recommencer à la partie 1", v -> {
            AppState.setCurrentPartIndex(this, 0);
            refreshStatus();
            Toast.makeText(this, "Retour à la partie 1", Toast.LENGTH_SHORT).show();
        }));

        root.addView(section("État"));
        statusView = text("", 15, false);
        statusView.setTextColor(Color.DKGRAY);
        root.addView(statusView);

        root.addView(text(
            "\nUtilisation Overlay :\n" +
            "1) Autorise l'overlay.\n" +
            "2) Active puis sélectionne Rebuild 3 Workshop Keyboard.\n" +
            "3) Télécharge le TEST UI ou le pack Android.\n" +
            "4) Affiche l'overlay et ouvre Rebuild 3.\n" +
            "5) Config → Modding → Install Mod.\n" +
            "6) Touche le champ blanc puis Écrire le mod sur le clavier.\n" +
            "7) À 100 %, valide avec Okay. Pour un pack multi-fichier, réouvre Install Mod : la partie suivante est sélectionnée automatiquement.",
            14, false));
        return scroll;
    }

    private TextView section(String s) {
        TextView v = text(s, 19, true);
        v.setPadding(0, dp(22), 0, dp(8));
        return v;
    }
    private TextView text(String s, int sp, boolean bold) {
        TextView v = new TextView(this);
        v.setText(s); v.setTextSize(sp); v.setTextColor(Color.rgb(25,25,25));
        if (bold) v.setTypeface(null, android.graphics.Typeface.BOLD);
        return v;
    }
    private Button button(String s, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s); b.setAllCaps(false); b.setOnClickListener(l);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(0, dp(4), 0, dp(4));
        b.setLayoutParams(p);
        return b;
    }
    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void openOverlayPermission() {
        if (Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Overlay déjà autorisé", Toast.LENGTH_SHORT).show();
            return;
        }
        startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:" + getPackageName())));
    }

    private void startOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Autorise d'abord l'overlay", Toast.LENGTH_LONG).show();
            openOverlayPermission();
            return;
        }
        Intent i = new Intent(this, OverlayService.class);
        i.setAction(OverlayService.ACTION_START);
        startForegroundService(i);
    }

    private void launchRebuild() {
        Intent i = getPackageManager().getLaunchIntentForPackage(REBUILD_PACKAGE);
        if (i == null) {
            Toast.makeText(this, "Rebuild 3 introuvable", Toast.LENGTH_LONG).show();
            return;
        }
        startActivity(i);
    }

    private void downloadFromManifest(boolean diagnosticOnly) {
        statusView.setText("Téléchargement du manifeste...");
        new Thread(() -> {
            try {
                JSONObject manifest = new JSONObject(httpGetText(MANIFEST_URL));
                JSONArray files = new JSONArray();

                if (diagnosticOnly) {
                    JSONObject probe = manifest.optJSONObject("ui_probe");
                    if (probe == null) throw new IllegalStateException("ui_probe absent du manifeste");
                    files.put(new JSONObject().put("name", probe.getString("name")));
                } else {
                    JSONObject android = manifest.optJSONObject("android");
                    JSONArray sourceFiles = android != null ? android.getJSONArray("files") : manifest.getJSONArray("files");
                    for (int i = 0; i < sourceFiles.length(); i++) files.put(sourceFiles.getJSONObject(i));
                }

                File dir = AppState.partsDir(this);
                File[] old = dir.listFiles();
                if (old != null) for (File f : old) f.delete();

                JSONArray names = new JSONArray();
                for (int i = 0; i < files.length(); i++) {
                    String name = files.getJSONObject(i).getString("name");
                    byte[] data = httpGetBytes(BASE_URL + name);
                    File out = new File(dir, new File(name).getName());
                    try (FileOutputStream fos = new FileOutputStream(out)) { fos.write(data); }
                    names.put(out.getName());
                    final int done = i + 1;
                    runOnUiThread(() -> statusView.setText("Téléchargement : " + done + "/" + files.length()));
                }

                SharedPreferences.Editor edit = AppState.prefs(this).edit();
                edit.putString(AppState.KEY_PARTS, names.toString());
                edit.putInt(AppState.KEY_CURRENT_PART, 0);
                edit.putString(AppState.KEY_SOURCE, diagnosticOnly ? "Diagnostic UI" : "Pack Android");
                edit.apply();
                runOnUiThread(() -> {
                    refreshStatus();
                    Toast.makeText(this, names.length() + " fichier(s) prêt(s)", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> statusView.setText("Erreur téléchargement : " + e.getMessage()));
            }
        }).start();
    }

    private void refreshStatus() {
        if (statusView == null) return;
        JSONArray parts = AppState.getParts(this);
        int current = parts.length() == 0 ? 0 : AppState.getCurrentPartIndex(this) + 1;
        String source = AppState.prefs(this).getString(AppState.KEY_SOURCE, "Aucun fichier");
        statusView.setText("Source : " + source + "\nFichiers : " + parts.length() +
            "\nProchaine partie : " + (parts.length() == 0 ? "-" : current + "/" + parts.length()) +
            "\nOverlay : " + (Settings.canDrawOverlays(this) ? "autorisé" : "non autorisé"));
    }

    @Override protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private static String httpGetText(String u) throws Exception {
        return new String(httpGetBytes(u), StandardCharsets.UTF_8);
    }
    private static byte[] httpGetBytes(String u) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setConnectTimeout(15000); c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", "Rebuild3Workshop-Android/0.1");
        c.connect();
        if (c.getResponseCode() != 200) throw new IllegalStateException("HTTP " + c.getResponseCode() + " pour " + u);
        try (BufferedInputStream in = new BufferedInputStream(c.getInputStream());
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) >= 0) out.write(buffer, 0, read);
            return out.toByteArray();
        } finally {
            c.disconnect();
        }
    }
}
