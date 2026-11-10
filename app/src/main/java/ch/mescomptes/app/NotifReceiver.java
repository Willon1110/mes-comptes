package ch.mescomptes.app;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONObject;

import java.text.DateFormatSymbols;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;

/** Rappels de Mes Comptes : « Prépare ton mois » et rappel quotidien, programmés localement. */
public class NotifReceiver extends BroadcastReceiver {
    static final String PREFS = "notif";
    static final String CHANNEL = "rappels";
    static final String EXTRA_KIND = "kind";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String kind = intent.getStringExtra(EXTRA_KIND);
        if (kind != null) {
            SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            if ("daily".equals(kind)) {
                String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Calendar.getInstance().getTime());
                if (!today.equals(sp.getString("entered", ""))) {
                    show(ctx, 2, "Mes Comptes", "As-tu des dépenses à noter aujourd'hui ?", "add");
                }
            } else if ("month".equals(kind)) {
                Calendar n = Calendar.getInstance();
                n.add(Calendar.MONTH, 1);
                String mois = new DateFormatSymbols(Locale.FRENCH).getMonths()[n.get(Calendar.MONTH)];
                mois = mois.substring(0, 1).toUpperCase(Locale.FRENCH) + mois.substring(1);
                show(ctx, 1, "Prépare ton mois", mois + " arrive dans 3 jours : vérifie tes revenus et dépenses prévus.", "prev");
            }
        }
        schedule(ctx);
    }

    static void channel(Context ctx) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = ctx.getSystemService(NotificationManager.class);
            if (nm.getNotificationChannel(CHANNEL) == null) {
                NotificationChannel ch = new NotificationChannel(CHANNEL, "Rappels", NotificationManager.IMPORTANCE_DEFAULT);
                ch.setDescription("Préparer le mois et noter ses dépenses");
                nm.createNotificationChannel(ch);
            }
        }
    }

    static void show(Context ctx, int id, String title, String text, String open) {
        channel(ctx);
        Intent i = new Intent(ctx, MainActivity.class);
        i.putExtra("open", open);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(ctx, id, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(ctx, CHANNEL) : new Notification.Builder(ctx);
        b.setSmallIcon(R.drawable.ic_notif).setContentTitle(title).setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setColor(0xFFA8661A).setAutoCancel(true).setContentIntent(pi);
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        try { nm.notify(id, b.build()); } catch (SecurityException ignored) { }
    }

    /** Reprogramme les deux rappels d'après les réglages enregistrés. */
    static void schedule(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        try {
            JSONObject cfg = new JSONObject(sp.getString("cfg", "{}"));
            JSONObject month = cfg.optJSONObject("month"), daily = cfg.optJSONObject("daily");
            set(ctx, am, "month", 11, month != null && month.optBoolean("on"), nextMonthPrep(month));
            set(ctx, am, "daily", 12, daily != null && daily.optBoolean("on"), nextDaily(daily));
        } catch (Exception ignored) { }
    }

    private static void set(Context ctx, AlarmManager am, String kind, int code, boolean on, long when) {
        Intent i = new Intent(ctx, NotifReceiver.class);
        i.putExtra(EXTRA_KIND, kind);
        PendingIntent pi = PendingIntent.getBroadcast(ctx, code, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        am.cancel(pi);
        if (!on || when <= 0) return;
        if (Build.VERSION.SDK_INT >= 23) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pi);
        else am.set(AlarmManager.RTC_WAKEUP, when, pi);
    }

    private static long nextDaily(JSONObject o) {
        if (o == null) return 0;
        Calendar now = Calendar.getInstance(), c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, o.optInt("h", 19)); c.set(Calendar.MINUTE, o.optInt("m", 30)); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
        if (!c.after(now)) c.add(Calendar.DAY_OF_MONTH, 1);
        return c.getTimeInMillis();
    }

    /** 3 jours avant le 1er du mois suivant, à l'heure choisie. */
    private static long nextMonthPrep(JSONObject o) {
        if (o == null) return 0;
        Calendar now = Calendar.getInstance();
        for (int k = 1; k <= 2; k++) {
            Calendar c = Calendar.getInstance();
            c.set(Calendar.DAY_OF_MONTH, 1); c.add(Calendar.MONTH, k); c.add(Calendar.DAY_OF_MONTH, -3);
            c.set(Calendar.HOUR_OF_DAY, o.optInt("h", 18)); c.set(Calendar.MINUTE, o.optInt("m", 0)); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
            if (c.after(now)) return c.getTimeInMillis();
        }
        return 0;
    }
}
