package fr.tb.rebuild3workshop;

import android.content.Intent;
import android.graphics.Color;
import android.inputmethodservice.InputMethodService;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputConnection;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.json.JSONArray;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;

public class WorkshopImeService extends InputMethodService {
    // Petit bloc volontairement conservateur pour ne pas saturer Rebuild/AIR.
    private static final int CHUNK_SIZE = 128;
    private static final long AUTO_DELAY_MS = 80L;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private TextView titleView;
    private TextView statusView;
    private ProgressBar progressBar;
    private Button autoButton;

    private String payload = "";
    private int cursor = 0;
    private int loadedPartIndex = -1;
    private boolean autoRunning = false;

    private final Runnable autoWriter = new Runnable() {
        @Override
        public void run() {
            if (!autoRunning) return;

            if (!writeOneBlock()) {
                autoRunning = false;
                updateAutoButton();
                return;
            }

            if (cursor < payload.length()) {
                handler.postDelayed(this, AUTO_DELAY_MS);
            } else {
                autoRunning = false;
                updateAutoButton();
                updateStatus("100 % — valide avec Okay");
            }
        }
    };

    @Override
    public View onCreateInputView() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(8), dp(6), dp(8), dp(8));
        root.setBackgroundColor(Color.rgb(24, 24, 24));

        titleView = new TextView(this);
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(14);
        titleView.setGravity(Gravity.CENTER);
        titleView.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(titleView);

        statusView = new TextView(this);
        statusView.setTextColor(Color.LTGRAY);
        statusView.setTextSize(12);
        statusView.setGravity(Gravity.CENTER);
        statusView.setPadding(0, dp(3), 0, dp(4));
        root.addView(statusView);

        progressBar = new ProgressBar(
            this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        root.addView(progressBar, new LinearLayout.LayoutParams(-1, dp(9)));

        LinearLayout mainRow = new LinearLayout(this);
        mainRow.setGravity(Gravity.CENTER);

        Button previous = actionButton("◀");
        previous.setOnClickListener(v -> selectRelative(-1));
        mainRow.addView(previous, weighted(0.65f));

        Button block = actionButton("Bloc");
        block.setOnClickListener(v -> {
            stopAuto();
            ensureLoaded();
            writeOneBlock();
        });
        mainRow.addView(block, weighted(1f));

        autoButton = actionButton("AUTO");
        autoButton.setOnClickListener(v -> toggleAuto());
        mainRow.addView(autoButton, weighted(1f));

        Button next = actionButton("▶");
        next.setOnClickListener(v -> selectRelative(1));
        mainRow.addView(next, weighted(0.65f));

        root.addView(mainRow);

        LinearLayout secondRow = new LinearLayout(this);
        secondRow.setGravity(Gravity.CENTER);

        Button reset = actionButton("Recommencer");
        reset.setOnClickListener(v -> {
            stopAuto();
            ensureLoaded();
            cursor = 0;
            refreshProgress();
            updateStatus("Position remise à 0");
        });
        secondRow.addView(reset, weighted(1f));

        Button app = actionButton("Workshop");
        app.setOnClickListener(v -> {
            Intent intent = new Intent(this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        });
        secondRow.addView(app, weighted(1f));

        root.addView(secondRow);

        loadCurrentPart(true);
        return root;
    }

    private Button actionButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(13);
        return button;
    }

    private LinearLayout.LayoutParams weighted(float weight) {
        LinearLayout.LayoutParams params =
            new LinearLayout.LayoutParams(0, -2, weight);
        params.setMargins(dp(2), dp(3), dp(2), 0);
        return params;
    }

    private void toggleAuto() {
        ensureLoaded();

        if (payload.isEmpty()) {
            updateStatus("Aucun fichier chargé");
            return;
        }

        if (cursor >= payload.length()) {
            updateStatus("Déjà à 100 % — Recommencer si nécessaire");
            return;
        }

        autoRunning = !autoRunning;
        updateAutoButton();

        if (autoRunning) {
            updateStatus("Écriture automatique...");
            handler.post(autoWriter);
        } else {
            handler.removeCallbacks(autoWriter);
            updateStatus("Pause");
        }
    }

    private void stopAuto() {
        autoRunning = false;
        handler.removeCallbacks(autoWriter);
        updateAutoButton();
    }

    private void updateAutoButton() {
        if (autoButton != null) {
            autoButton.setText(autoRunning ? "PAUSE" : "AUTO");
        }
    }

    private boolean writeOneBlock() {
        ensureLoaded();

        if (payload.isEmpty()) {
            updateStatus("Aucun fichier chargé");
            return false;
        }

        if (cursor >= payload.length()) {
            refreshProgress();
            updateStatus("100 % — valide avec Okay");
            return false;
        }

        InputConnection connection = getCurrentInputConnection();
        if (connection == null) {
            updateStatus("Touche d'abord le champ Install Mod");
            return false;
        }

        int end = Math.min(payload.length(), cursor + CHUNK_SIZE);

        if (end < payload.length()
            && end > cursor
            && Character.isHighSurrogate(payload.charAt(end - 1))
            && Character.isLowSurrogate(payload.charAt(end))) {
            end--;
        }

        CharSequence block = payload.subSequence(cursor, end);
        boolean accepted = connection.commitText(block, 1);

        if (!accepted) {
            updateStatus("Rebuild a refusé ce bloc");
            return false;
        }

        cursor = end;
        refreshProgress();

        if (cursor >= payload.length()) {
            updateStatus("100 % — valide avec Okay");
        } else {
            updateStatus(progressPercent() + " % — " + cursor + "/" +
                payload.length() + " caractères");
        }

        return true;
    }

    private void selectRelative(int delta) {
        stopAuto();

        JSONArray parts = AppState.getParts(this);
        if (parts.length() == 0) {
            updateStatus("Télécharge d'abord un pack");
            return;
        }

        int current = AppState.getCurrentPartIndex(this);
        int target = Math.max(0, Math.min(
            parts.length() - 1, current + delta));

        AppState.setCurrentPartIndex(this, target);
        loadCurrentPart(true);
    }

    private void ensureLoaded() {
        int current = AppState.getCurrentPartIndex(this);
        if (loadedPartIndex != current || payload.isEmpty()) {
            loadCurrentPart(false);
        }
    }

    private void loadCurrentPart(boolean resetCursor) {
        stopAuto();

        JSONArray parts = AppState.getParts(this);
        if (parts.length() == 0) {
            payload = "";
            cursor = 0;
            loadedPartIndex = -1;
            if (titleView != null) titleView.setText("Rebuild Workshop");
            if (progressBar != null) progressBar.setProgress(0);
            updateStatus("Télécharge un pack dans l'application");
            return;
        }

        int index = AppState.getCurrentPartIndex(this);
        File file = AppState.getCurrentPartFile(this);

        try {
            payload = file == null ? "" : readUtf8(file);
            loadedPartIndex = index;
            if (resetCursor) cursor = 0;
            cursor = Math.min(cursor, payload.length());

            if (titleView != null) {
                titleView.setText(
                    "Fichier " + (index + 1) + "/" + parts.length() +
                    "  •  " + (file == null ? "?" : file.getName()));
            }

            refreshProgress();
            updateStatus(
                payload.length() + " caractères — prêt");
        } catch (Exception e) {
            payload = "";
            cursor = 0;
            loadedPartIndex = index;
            refreshProgress();
            updateStatus("Erreur lecture : " + e.getMessage());
        }
    }

    private static String readUtf8(File file) throws Exception {
        try (
            FileInputStream in = new FileInputStream(file);
            ByteArrayOutputStream out = new ByteArrayOutputStream()
        ) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                out.write(buffer, 0, read);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private int progressPercent() {
        if (payload.isEmpty()) return 0;
        return Math.min(100, (int) ((cursor * 100L) / payload.length()));
    }

    private void refreshProgress() {
        if (progressBar != null) {
            progressBar.setProgress(progressPercent());
        }
    }

    private void updateStatus(String message) {
        if (statusView != null) statusView.setText(message);
    }

    @Override
    public void onStartInput(
        android.view.inputmethod.EditorInfo attribute,
        boolean restarting
    ) {
        super.onStartInput(attribute, restarting);
        loadCurrentPart(false);
    }

    @Override
    public void onFinishInput() {
        stopAuto();
        super.onFinishInput();
    }

    @Override
    public void onDestroy() {
        stopAuto();
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(
            value * getResources().getDisplayMetrics().density);
    }
}
