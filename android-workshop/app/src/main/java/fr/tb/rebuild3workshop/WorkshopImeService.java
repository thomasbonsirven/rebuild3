package fr.tb.rebuild3workshop;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.inputmethodservice.InputMethodService;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputConnection;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONArray;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class WorkshopImeService extends InputMethodService {
    private static final int CHUNK_SIZE = 96;
    private static final long CHUNK_DELAY_MS = 55L;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private TextView status;
    private Button pauseButton;
    private String payload = "";
    private int cursor = 0;
    private boolean running = false;
    private boolean paused = false;
    private int partIndex = 0;
    private int partTotal = 1;

    private final BroadcastReceiver controlReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (!AppState.ACTION_CONTROL.equals(i.getAction())) return;
            String cmd = i.getStringExtra(AppState.EXTRA_COMMAND);
            if (AppState.CMD_TOGGLE_PAUSE.equals(cmd)) togglePause();
            else if (AppState.CMD_STOP.equals(cmd)) stopTyping("Arrêté");
        }
    };

    private final Runnable writer = new Runnable() {
        @Override public void run() {
            if (!running || paused) return;
            InputConnection ic = getCurrentInputConnection();
            if (ic == null) {
                paused = true;
                updateUi("Champ indisponible - pause");
                sendProgress("Champ indisponible - pause");
                return;
            }
            if (cursor >= payload.length()) {
                completePart();
                return;
            }

            int end = Math.min(payload.length(), cursor + CHUNK_SIZE);
            if (end < payload.length() && end > cursor &&
                Character.isHighSurrogate(payload.charAt(end - 1)) &&
                Character.isLowSurrogate(payload.charAt(end))) end--;

            if (!ic.commitText(payload.subSequence(cursor, end), 1)) {
                stopTyping("Rebuild a refusé l'écriture");
                return;
            }
            cursor = end;
            updateUi(progressText());
            sendProgress("Écriture...");
            handler.postDelayed(this, CHUNK_DELAY_MS);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        IntentFilter f = new IntentFilter(AppState.ACTION_CONTROL);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(controlReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(controlReceiver, f);
    }

    @Override public View onCreateInputView() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(10), dp(8), dp(10), dp(8));
        root.setBackgroundColor(Color.rgb(25,25,25));

        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(14);
        status.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(status);

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER);

        Button start = new Button(this);
        start.setText("Écrire le mod"); start.setAllCaps(false);
        start.setOnClickListener(v -> startTyping());
        row.addView(start, weighted());

        pauseButton = new Button(this);
        pauseButton.setText("Pause"); pauseButton.setAllCaps(false);
        pauseButton.setOnClickListener(v -> togglePause());
        row.addView(pauseButton, weighted());

        Button stop = new Button(this);
        stop.setText("Stop"); stop.setAllCaps(false);
        stop.setOnClickListener(v -> stopTyping("Arrêté"));
        row.addView(stop, weighted());

        root.addView(row);
        refreshIdle();
        return root;
    }

    private LinearLayout.LayoutParams weighted() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1f);
        p.setMargins(dp(2),0,dp(2),0);
        return p;
    }

    private void startTyping() {
        if (running && paused) {
            paused = false;
            pauseButton.setText("Pause");
            handler.post(writer);
            sendProgress("Reprise...");
            return;
        }
        if (running) return;

        try {
            JSONArray parts = AppState.getParts(this);
            if (parts.length() == 0) {
                updateUi("Aucun fichier téléchargé");
                return;
            }
            partIndex = AppState.getCurrentPartIndex(this);
            partTotal = parts.length();
            File file = AppState.getCurrentPartFile(this);
            if (file == null || !file.exists()) {
                updateUi("Fichier introuvable");
                return;
            }
            payload = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            cursor = 0;
            paused = false;
            running = true;
            pauseButton.setText("Pause");
            updateUi(progressText());
            sendProgress("Démarrage...");
            handler.post(writer);
        } catch (Exception e) {
            updateUi("Erreur : " + e.getMessage());
        }
    }

    private void completePart() {
        running = false;
        handler.removeCallbacks(writer);
        int finished = partIndex + 1;
        AppState.sendProgress(this, payload.length(), Math.max(1,payload.length()), finished, partTotal,
            "Partie terminée - valide avec Okay");
        if (finished < partTotal) {
            AppState.setCurrentPartIndex(this, finished);
            updateUi("Partie " + finished + "/" + partTotal + " terminée. Valide Okay puis réouvre Install Mod.");
        } else {
            updateUi("Pack terminé. Valide Okay puis redémarre Rebuild.");
        }
        payload = "";
        cursor = 0;
    }

    private void togglePause() {
        if (!running) {
            refreshIdle();
            return;
        }
        paused = !paused;
        pauseButton.setText(paused ? "Reprendre" : "Pause");
        if (paused) {
            handler.removeCallbacks(writer);
            updateUi("Pause - " + progressText());
            sendProgress("En pause");
        } else {
            handler.post(writer);
            sendProgress("Reprise...");
        }
    }

    private void stopTyping(String reason) {
        handler.removeCallbacks(writer);
        running = false; paused = false; payload = ""; cursor = 0;
        if (pauseButton != null) pauseButton.setText("Pause");
        updateUi(reason);
        sendProgress(reason);
    }

    private String progressText() {
        int total = Math.max(1, payload.length());
        return "Partie " + (partIndex + 1) + "/" + partTotal + "  •  " + Math.min(100, cursor * 100 / total) + "%";
    }

    private void sendProgress(String s) {
        AppState.sendProgress(this, cursor, Math.max(1,payload.length()), partIndex + 1, partTotal, s);
    }

    private void refreshIdle() {
        JSONArray parts = AppState.getParts(this);
        if (parts.length() == 0) updateUi("Télécharge d'abord le pack dans l'application");
        else updateUi("Prêt pour la partie " + (AppState.getCurrentPartIndex(this)+1) + "/" + parts.length());
    }

    private void updateUi(String s) { if (status != null) status.setText(s); }

    @Override public void onStartInput(android.view.inputmethod.EditorInfo a, boolean restarting) {
        super.onStartInput(a, restarting);
        if (!running) refreshIdle();
    }

    @Override public void onFinishInput() {
        if (running) {
            paused = true;
            handler.removeCallbacks(writer);
            updateUi("Champ quitté - écriture en pause");
            sendProgress("Champ quitté - pause");
        }
        super.onFinishInput();
    }

    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        try { unregisterReceiver(controlReceiver); } catch (Exception ignored) {}
        super.onDestroy();
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
