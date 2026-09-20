package fr.tb.rebuild3workshop;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import org.json.JSONArray;
import java.io.File;

public final class AppState {
    public static final String PREFS = "rebuild3_workshop";
    public static final String KEY_PARTS = "parts_json";
    public static final String KEY_CURRENT_PART = "current_part";
    public static final String KEY_SOURCE = "source";
    public static final String ACTION_PROGRESS = "fr.tb.rebuild3workshop.action.PROGRESS";
    public static final String ACTION_CONTROL = "fr.tb.rebuild3workshop.action.CONTROL";
    public static final String EXTRA_CURRENT = "current";
    public static final String EXTRA_TOTAL = "total";
    public static final String EXTRA_PART = "part";
    public static final String EXTRA_PART_TOTAL = "part_total";
    public static final String EXTRA_STATUS = "status";
    public static final String EXTRA_COMMAND = "command";
    public static final String CMD_TOGGLE_PAUSE = "toggle_pause";
    public static final String CMD_STOP = "stop";
    private AppState() {}
    public static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
    public static File partsDir(Context c) {
        File dir = new File(c.getFilesDir(), "parts");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }
    public static JSONArray getParts(Context c) {
        try { return new JSONArray(prefs(c).getString(KEY_PARTS, "[]")); }
        catch (Exception e) { return new JSONArray(); }
    }
    public static int getCurrentPartIndex(Context c) {
        int count = getParts(c).length();
        int index = prefs(c).getInt(KEY_CURRENT_PART, 0);
        if (count == 0) return 0;
        return Math.max(0, Math.min(index, count - 1));
    }
    public static void setCurrentPartIndex(Context c, int index) {
        prefs(c).edit().putInt(KEY_CURRENT_PART, Math.max(0, index)).apply();
    }
    public static File getCurrentPartFile(Context c) {
        JSONArray parts = getParts(c);
        if (parts.length() == 0) return null;
        String name = parts.optString(getCurrentPartIndex(c), "");
        return name.isEmpty() ? null : new File(partsDir(c), name);
    }
    public static void sendProgress(Context c, int current, int total, int part, int partTotal, String status) {
        Intent i = new Intent(ACTION_PROGRESS);
        i.setPackage(c.getPackageName());
        i.putExtra(EXTRA_CURRENT, current);
        i.putExtra(EXTRA_TOTAL, total);
        i.putExtra(EXTRA_PART, part);
        i.putExtra(EXTRA_PART_TOTAL, partTotal);
        i.putExtra(EXTRA_STATUS, status);
        c.sendBroadcast(i);
    }
    public static void sendControl(Context c, String command) {
        Intent i = new Intent(ACTION_CONTROL);
        i.setPackage(c.getPackageName());
        i.putExtra(EXTRA_COMMAND, command);
        c.sendBroadcast(i);
    }
}
