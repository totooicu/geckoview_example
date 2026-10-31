package org.mozilla.geckoview_example.jsbridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.core.content.ContextCompat;

/**
 * 开机自启动：只认「用户在前一版里打开过自启动」这一条存档。
 *
 * <p>开机后只把服务器拉起来（前台服务 + 通知）；数据仍在壳的网页里，
 * 所以客户端此刻连进来会看到「服务端不在线」。点通知打开壳后，页面会
 * 按同一条存档自动以服务端身份连回本地服务器。</p>
 */
public class DataShareBootReceiver extends BroadcastReceiver {
  @Override
  public void onReceive(Context context, Intent intent) {
    if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
    DataShareStore.Prefs prefs = DataShareStore.read(context);
    if (!prefs.autostart) return;
    ContextCompat.startForegroundService(
        context, new Intent(context, DataShareService.class).setAction(DataShareService.ACTION_START));
  }
}
