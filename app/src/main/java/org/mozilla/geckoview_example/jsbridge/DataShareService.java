package org.mozilla.geckoview_example.jsbridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;

import org.mozilla.geckoview_example.R;
import org.mozilla.geckoview_example.WebViewActivity;

import java.io.IOException;

/**
 * 「跨设备操作」前台服务：让局域网服务器在切到后台后不被系统随手回收。
 *
 * <p>服务只负责「端口在听」。真正的数据仍在壳里的网页（gh-pages 源）中——
 * 所以通知会提示保持壳打开；壳被划掉后服务还能监听，但客户端会看到
 * 「服务端不在线」，不会拿到旧数据。</p>
 */
public class DataShareService extends Service {
  private static final String LOGTAG = "DataShareService";

  public static final String ACTION_START = "org.mozilla.geckoview_example.DATA_SHARE_START";
  public static final String ACTION_STOP = "org.mozilla.geckoview_example.DATA_SHARE_STOP";

  public static final int NOTIFICATION_ID = 4517;
  private static final String CHANNEL_ID = "data-share";

  @Override
  public void onCreate() {
    super.onCreate();
    createChannel();
  }

  @Override
  public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
    String action = intent == null ? null : intent.getAction();
    if (ACTION_STOP.equals(action)) {
      stopSharing();
      ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
      stopSelf();
      return START_NOT_STICKY;
    }

    startForegroundCompat();
    startSharing();
    updateNotification();
    return START_STICKY;
  }

  private void startSharing() {
    DataShareStore.Prefs p = DataShareStore.read(this);
    String token = p.token.isEmpty() ? DataShareStore.tokenOrCreate(this) : p.token;
    try {
      DataShareServer.get().start(p.port, token);
    } catch (IOException e) {
      Log.w(LOGTAG, "start failed", e);
      updateNotification("启动失败：" + e.getMessage());
    }
  }

  private void stopSharing() {
    DataShareServer.get().stop();
  }

  private void createChannel() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
    NotificationManager nm = getSystemService(NotificationManager.class);
    if (nm == null) return;
    NotificationChannel ch =
        new NotificationChannel(CHANNEL_ID, "跨设备操作", NotificationManager.IMPORTANCE_LOW);
    ch.setDescription("局域网内把壳里的数据共享给其他设备");
    ch.setShowBadge(false);
    nm.createNotificationChannel(ch);
  }

  private void startForegroundCompat() {
    int type = 0;
    if (Build.VERSION.SDK_INT >= 34) {
      type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
    }
    ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(null), type);
  }

  private void updateNotification() {
    updateNotification(null);
  }

  private void updateNotification(@Nullable String problem) {
    NotificationManager nm = getSystemService(NotificationManager.class);
    if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification(problem));
  }

  private Notification buildNotification(@Nullable String problem) {
    DataShareServer.Status st = DataShareServer.get().status();
    String address = st.addresses.isEmpty() ? "手机IP" : st.addresses.get(0);
    String text;
    if (problem != null) {
      text = problem;
    } else if (!st.running) {
      text = "未在监听，请打开壳重新开启";
    } else {
      text =
          "http://"
              + address
              + ":"
              + st.port
              + (st.hostOnline ? " · 服务端在线" : " · 等壳里的页面开启");
    }

    Intent open = new Intent(this, WebViewActivity.class);
    open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) piFlags |= PendingIntent.FLAG_IMMUTABLE;
    PendingIntent pi = PendingIntent.getActivity(this, 0, open, piFlags);

    return new NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.logo)
        .setContentTitle("跨设备操作")
        .setContentText(text)
        .setStyle(new NotificationCompat.BigTextStyle().bigText(text + "\n保持壳打开，数据才会在壳里。"))
        .setOngoing(true)
        .setShowWhen(false)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setContentIntent(pi)
        .build();
  }

  @Nullable
  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }
}
