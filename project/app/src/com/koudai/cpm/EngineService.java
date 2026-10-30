package com.koudai.cpm;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

/**
 * 引擎保活服务。
 *
 * llama-server 是 App 进程 fork 出来的子进程，进程一被系统回收引擎就没了。
 * 挂一个前台服务可以把 App 进程的重要性提到前台级别，Activity 退出、切后台、
 * 锁屏时都不会被轻易杀掉，重新进入时直接接上正在跑的引擎（约 1 秒），
 * 不用再把 1.7GB 模型从闪存读一遍。
 *
 * 后台闲置超过 {@link #IDLE_MS} 自动释放内存；用户也可以点通知里的「结束引擎」
 * 立即释放。
 */
public class EngineService extends Service {

    private static final String TAG = "KoudaiEngine";
    private static final String CHANNEL = "koudai_engine";
    private static final int NOTIFY_ID = 1001;

    static final String ACTION_STOP = "com.koudai.cpm.action.STOP";
    static final String ACTION_ARM_IDLE = "com.koudai.cpm.action.ARM_IDLE";
    static final String ACTION_DISARM_IDLE = "com.koudai.cpm.action.DISARM_IDLE";

    /** 后台闲置多久自动释放内存。 */
    private static final long IDLE_MS = 60 * 60 * 1000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable idleStop = new Runnable() {
        @Override
        public void run() {
            Log.i(TAG, "后台闲置超时，释放引擎内存");
            EngineHolder.stop();
            running = false;
            stopForeground(true);
            stopSelf();
        }
    };

    /** 服务是否在跑（同进程静态标记，Activity 用来决定要不要发闲置指令）。 */
    static volatile boolean running = false;

    static void armIdle(Context c) {
        if (running) send(c, ACTION_ARM_IDLE);
    }

    static void disarmIdle(Context c) {
        if (running) send(c, ACTION_DISARM_IDLE);
    }

    private static void send(Context c, String action) {
        try {
            c.startService(new Intent(c, EngineService.class).setAction(action));
        } catch (Throwable ignore) {
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                NotificationChannel ch = new NotificationChannel(
                        CHANNEL, "本地引擎", NotificationManager.IMPORTANCE_MIN);
                ch.setDescription("保持离线推理引擎在后台运行");
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();

        // 系统重启服务（进程已被回收，引擎肯定不在了）时别挂个空通知，直接收工
        if (action == null && !EngineHolder.alive()) {
            running = false;
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_STOP.equals(action)) {
            Log.i(TAG, "用户结束引擎");
            handler.removeCallbacks(idleStop);
            EngineHolder.stop();
            running = false;
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        startForeground(NOTIFY_ID, buildNotification());

        if (ACTION_ARM_IDLE.equals(action)) {
            handler.removeCallbacks(idleStop);
            handler.postDelayed(idleStop, IDLE_MS);
        } else if (ACTION_DISARM_IDLE.equals(action)) {
            handler.removeCallbacks(idleStop);
        }
        return START_STICKY;
    }

    private Notification buildNotification() {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this);
        }

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent open = PendingIntent.getActivity(this, 1,
                new Intent(this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                flags);
        PendingIntent stop = PendingIntent.getService(this, 2,
                new Intent(this, EngineService.class).setAction(ACTION_STOP), flags);

        b.setContentTitle("口袋CPM 正在本机运行")
                .setContentText("离线引擎已就绪，随时可以提问")
                .setSmallIcon(R.drawable.ic_notify)
                .setContentIntent(open)
                .setOngoing(true)
                .setShowWhen(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b.addAction(new Notification.Action.Builder(
                    android.R.drawable.ic_menu_close_clear_cancel, "结束引擎", stop).build());
        } else {
            b.addAction(android.R.drawable.ic_menu_close_clear_cancel, "结束引擎", stop);
        }
        return b.build();
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(idleStop);
        running = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
