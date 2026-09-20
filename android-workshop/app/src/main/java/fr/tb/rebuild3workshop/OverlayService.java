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
    public static final String ACTION_START =
        "fr.tb.rebuild3workshop.overlay.START";

    private static final int NOTIFICATION_ID = 1001;
    private static final String CHANNEL_ID = "rebuild_workshop_overlay";

    private WindowManager windowManager;
    private View overlay;
    private TextView titleView;
    private TextView detailView;
    private ProgressBar progressBar;

    private final BroadcastReceiver progressReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!AppState.ACTION_PROGRESS.equals(intent.getAction())) return;

            int current = intent.getIntExtra(AppState.EXTRA_CURRENT, 0);
            int total = Math.max(
                1, intent.getIntExtra(AppState.EXTRA_TOTAL, 1));
            int part = Math.max(
                1, intent.getIntExtra(AppState.EXTRA_PART, 1));
            int partTotal = Math.max(
                1, intent.getIntExtra(AppState.EXTRA_PART_TOTAL, 1));

            String status = intent.getStringExtra(AppState.EXTRA_STATUS);
            if (status == null) status = "";

            int partPercent = Math.min(
                100, Math.max(0, (int)((current * 100L) / total)));

            int totalPercent = (int)Math.min(
                100,
                Math.max(
                    0,
                    (((part - 1) + (partPercent / 100.0))
                        / partTotal) * 100.0
                )
            );

            if (titleView != null) {
                titleView.setText(
                    "Rebuild Workshop  •  " + part + "/" + partTotal);
            }

            if (detailView != null) {
                detailView.setText(
                    status + "\n" +
                    partPercent + "% partie  •  " +
                    totalPercent + "% total"
                );
            }

            if (progressBar != null) {
                progressBar.setProgress(partPercent);
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());

        IntentFilter filter =
            new IntentFilter(AppState.ACTION_PROGRESS);

        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(
                progressReceiver,
                filter,
                Context.RECEIVER_NOT_EXPORTED
            );
        } else {
            registerReceiver(progressReceiver, filter);
        }
    }

    @Override
    public int onStartCommand(
        Intent intent,
        int flags,
        int startId
    ) {
        if (overlay == null && Settings.canDrawOverlays(this)) {
            createOverlay();
        }
        return START_STICKY;
    }

    private void createOverlay() {
        windowManager =
            (WindowManager)getSystemService(WINDOW_SERVICE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(10), dp(14), dp(10));

        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.argb(230, 18, 18, 18));
        background.setCornerRadius(dp(14));
        background.setStroke(
            dp(1), Color.argb(190, 100, 100, 100));
        root.setBackground(background);

        titleView = new TextView(this);
        titleView.setText("Rebuild Workshop");
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(14);
        titleView.setTypeface(
            null, android.graphics.Typeface.BOLD);
        root.addView(titleView);

        detailView = new TextView(this);
        detailView.setText(
            "Prêt. Ouvre Install Mod et utilise le clavier Workshop.");
        detailView.setTextColor(Color.LTGRAY);
        detailView.setTextSize(12);
        detailView.setPadding(0, dp(4), 0, dp(6));
        root.addView(detailView);

        progressBar = new ProgressBar(
            this,
            null,
            android.R.attr.progressBarStyleHorizontal
        );
        progressBar.setMax(100);
        progressBar.setProgress(0);
        root.addView(
            progressBar,
            new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(10)
            )
        );

        Button close = new Button(this);
        close.setText("Fermer");
        close.setAllCaps(false);
        close.setOnClickListener(v -> stopSelf());
        root.addView(close);

        overlay = root;

        int type = Build.VERSION.SDK_INT >= 26
            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            : WindowManager.LayoutParams.TYPE_PHONE;

        WindowManager.LayoutParams params =
            new WindowManager.LayoutParams(
                dp(330),
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            );

        params.gravity =
            Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        params.y = dp(42);

        windowManager.addView(overlay, params);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return;

        NotificationManager manager =
            (NotificationManager)getSystemService(
                NOTIFICATION_SERVICE);

        NotificationChannel channel =
            new NotificationChannel(
                CHANNEL_ID,
                "Rebuild Workshop Overlay",
                NotificationManager.IMPORTANCE_LOW
            );

        channel.setDescription(
            "Progression de l'installation Rebuild 3.");
        manager.createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        Notification.Builder builder;

        if (Build.VERSION.SDK_INT >= 26) {
            builder = new Notification.Builder(
                this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        return builder
            .setContentTitle("Rebuild 3 Workshop")
            .setContentText("Overlay de progression actif")
            .setSmallIcon(
                android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build();
    }

    private int dp(int value) {
        return Math.round(
            value * getResources()
                .getDisplayMetrics().density);
    }

    @Override
    public void onDestroy() {
        try {
            unregisterReceiver(progressReceiver);
        } catch (Exception ignored) {}

        if (overlay != null && windowManager != null) {
            try {
                windowManager.removeView(overlay);
            } catch (Exception ignored) {}
        }

        overlay = null;

        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }

        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
