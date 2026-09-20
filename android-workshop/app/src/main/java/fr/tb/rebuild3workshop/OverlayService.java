package fr.tb.rebuild3workshop;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

public class OverlayService extends Service {
    public static final String ACTION_START = "fr.tb.rebuild3workshop.overlay.START";
    public static final String ACTION_STOP = "fr.tb.rebuild3workshop.overlay.STOP";
    private static final int NOTIFICATION_ID = 1001;
    private static final String CHANNEL_ID = "workshop_overlay";

    private WindowManager wm;
    private View overlay;
    private TextView title;
    private TextView detail;
    private ProgressBar bar;

    private final BroadcastReceiver progressReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!AppState.ACTION_PROGRESS.equals(intent.getAction())) return;
            int current = intent.getIntExtra(AppState.EXTRA_CURRENT, 0);
            int total = Math.max(1, intent.getIntExtra(AppState.EXTRA_TOTAL, 1));
            int part = Math.max(1, intent.getIntExtra(AppState.EXTRA_PART, 1));
            int partTotal = Math.max(1, intent.getIntExtra(AppState.EXTRA_PART_TOTAL, 1));
            String status = intent.getStringExtra(AppState.EXTRA_STATUS);
            if (status == null) status = "";
            int percent = Math.min(100, Math.max(0, current * 100 / total));
            int overall = (int)Math.min(100, Math.max(0,
                (((part - 1) + (percent / 100.0)) / partTotal) * 100));
            if (title != null) title.setText("Rebuild Workshop  •  Partie " + part + "/" + partTotal);
            if (detail != null) detail.setText(status + "\n" + percent + "% partie  •  " + overall + "% total");
            if (bar != null) bar.setProgress(percent);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(NOTIFICATION_ID, notification());
        IntentFilter f = new IntentFilter(AppState.ACTION_PROGRESS);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(progressReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(progressReceiver, f);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (overlay == null && Settings.canDrawOverlays(this)) createOverlay();
        return START_STICKY;
    }

    private void createOverlay() {
        wm = (WindowManager)getSystemService(WINDOW_SERVICE);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(10), dp(14), dp(10));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(225,20,20,20));
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(1), Color.argb(180,110,110,110));
        root.setBackground(bg);

        title = new TextView(this);
        title.setText("Rebuild Workshop");
        title.setTextColor(Color.WHITE);
        title.setTextSize(14);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title);

        detail = new TextView(this);
        detail.setText("Prêt. Ouvre Install Mod puis touche le champ.");
        detail.setTextColor(Color.LTGRAY);
        detail.setTextSize(12);
        detail.setPadding(0, dp(4), 0, dp(6));
        root.addView(detail);

        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        root.addView(bar, new LinearLayout.LayoutParams(-1, dp(10)));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setGravity(Gravity.END);
        Button pause = new Button(this);
        pause.setText("Pause"); pause.setAllCaps(false);
        pause.setOnClickListener(v -> AppState.sendControl(this, AppState.CMD_TOGGLE_PAUSE));
        buttons.addView(pause);
        Button stop = new Button(this);
        stop.setText("Stop"); stop.setAllCaps(false);
        stop.setOnClickListener(v -> {
            AppState.sendControl(this, AppState.CMD_STOP);
            stopSelf();
        });
        buttons.addView(stop);
        root.addView(buttons);
        overlay = root;

        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
            dp(330), -2, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        p.y = dp(44);
        wm.addView(overlay, p);
    }

    private void createChannel() {
        NotificationManager nm = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Rebuild Workshop Overlay", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Progression de l'installation Rebuild 3");
        nm.createNotificationChannel(ch);
    }

    private Notification notification() {
        return new Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Rebuild 3 Workshop")
            .setContentText("Overlay actif")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build();
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    @Override public void onDestroy() {
        try { unregisterReceiver(progressReceiver); } catch (Exception ignored) {}
        if (overlay != null && wm != null) {
            try { wm.removeView(overlay); } catch (Exception ignored) {}
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
