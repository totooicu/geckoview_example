package org.mozilla.geckoview_example;

import android.Manifest;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Process;
import android.webkit.WebView;

import androidx.core.content.ContextCompat;

/**
 * 麦克风自检：把 {@code NotReadableError · Could not start audio source} 拆成三个可区分的原因。
 *
 * <p>WebView 里 getUserMedia 报这个错，说明「权限已放行、但 AudioRecord 没起来」。可能是
 * 系统层面没把麦克风交给本 App（AppOps 被关 / 隐私开关 / 被占用），也可能是 WebView
 * 内核自己那条音频路径坏了。这里在放行前先用原生 {@link AudioRecord} 录一小段：</p>
 *
 * <ul>
 *   <li>原生也失败 → 问题在本 App 的系统权限/占用，与 WebView 无关；</li>
 *   <li>原生成功、WebView 仍失败 → 问题在内核（WebView 版本/厂商替换），换内核或改走原生录音。</li>
 * </ul>
 *
 * <p>两个音源各试一次：{@code MIC}（关掉回声消除时用）和 {@code VOICE_COMMUNICATION}
 * （开着回声消除/降噪时用），因为部分 ROM 只坏其中一条。探针只在报告里出现，不参与业务。</p>
 */
final class MicDiagnostics {

  private MicDiagnostics() {}

  /** AppOps 里 RECORD_AUDIO 的实际模式；读不到返回 -1。 */
  static int audioOpMode(Context ctx) {
    try {
      AppOpsManager ops = (AppOpsManager) ctx.getSystemService(Context.APP_OPS_SERVICE);
      if (ops == null) {
        return -1;
      }
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        return ops.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_RECORD_AUDIO, Process.myUid(), ctx.getPackageName());
      }
      return ops.checkOpNoThrow(
          AppOpsManager.OPSTR_RECORD_AUDIO, Process.myUid(), ctx.getPackageName());
    } catch (Throwable e) {
      return -1;
    }
  }

  static String opModeLabel(int mode) {
    switch (mode) {
      case AppOpsManager.MODE_ALLOWED:
        return "允许";
      case AppOpsManager.MODE_IGNORED:
        return "被关掉";
      case AppOpsManager.MODE_ERRORED:
        return "报错";
      case AppOpsManager.MODE_DEFAULT:
        return "跟随权限";
      case 4: // MODE_FOREGROUND
        return "仅前台允许";
      default:
        return "未知(" + mode + ")";
    }
  }

  private static String permLabel(Context ctx, String perm) {
    return ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED
        ? "有"
        : "没有";
  }

  private static String webViewLabel() {
    try {
      PackageInfo pi = WebView.getCurrentWebViewPackage();
      return pi == null ? "未知" : pi.packageName + "/" + pi.versionName;
    } catch (Throwable e) {
      return "读取失败";
    }
  }

  private static String audioManagerLabel(Context ctx) {
    try {
      AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
      if (am == null) {
        return "不可用";
      }
      StringBuilder sb = new StringBuilder();
      sb.append("静音开关=").append(am.isMicrophoneMute() ? "开(系统把麦克风关了)" : "关");
      sb.append(" · 模式=").append(am.getMode());
      int rate = -1;
      try {
        rate = Integer.parseInt(am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE));
      } catch (Throwable ignored) {
      }
      sb.append(" · 输出采样率=").append(rate > 0 ? rate + "Hz" : "未知");
      int inputs = 0;
      try {
        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
          if (d.isSource()) {
            inputs++;
          }
        }
      } catch (Throwable ignored) {
      }
      sb.append(" · 输入设备=").append(inputs).append(" 个");
      return sb.toString();
    } catch (Throwable e) {
      return "读取失败";
    }
  }

  /** 用原生 AudioRecord 录一小段（非阻塞读，最多 ~200ms），返回结果描述。 */
  private static String probe(int source) {
    final int sampleRate = 16000;
    int minBuf =
        AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
    if (minBuf <= 0) {
      return "参数不支持(minBuffer=" + minBuf + ")";
    }
    AudioRecord rec = null;
    long t0 = System.nanoTime();
    try {
      // 用 {@link MediaRecorder.AudioSource} 的 source，照 WebView 开麦时的两种典型取值
      rec = new AudioRecord(source, sampleRate, AudioFormat.CHANNEL_IN_MONO,
          AudioFormat.ENCODING_PCM_16BIT, minBuf * 2);
      if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
        return "初始化失败(state=" + rec.getState() + ")";
      }
      try {
        rec.startRecording();
      } catch (SecurityException se) {
        return "被系统拒绝(" + se.getMessage() + ")";
      }
      if (rec.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
        return "没进入录音态(state=" + rec.getRecordingState() + ")";
      }
      short[] buf = new short[1024];
      int frames = 0;
      int peak = 0;
      long deadline = System.nanoTime() + 200_000_000L;
      while (System.nanoTime() < deadline) {
        int n = rec.read(buf, 0, buf.length, AudioRecord.READ_NON_BLOCKING);
        if (n > 0) {
          frames += n;
          for (int i = 0; i < n; i++) {
            int v = Math.abs(buf[i]);
            if (v > peak) {
              peak = v;
            }
          }
          if (frames >= 2048) {
            break;
          }
        } else if (n < 0) {
          return "读取失败(err=" + n + ")";
        } else {
          try {
            Thread.sleep(20);
          } catch (InterruptedException ignored) {
            break;
          }
        }
      }
      int ms = (int) ((System.nanoTime() - t0) / 1_000_000L);
      if (frames == 0) {
        // 起来了但一帧都没有：多半是隐私开关/被占用，HAL 只给静音帧或不给帧
        return "起来了但无数据(" + ms + "ms)";
      }
      return "正常(" + frames + " 帧, 峰值 " + peak + ", " + ms + "ms)";
    } catch (Throwable e) {
      return e.getClass().getSimpleName() + "(" + e.getMessage() + ")";
    } finally {
      if (rec != null) {
        try {
          if (rec.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
            rec.stop();
          }
        } catch (Throwable ignored) {
        }
        try {
          rec.release();
        } catch (Throwable ignored) {
        }
      }
    }
  }

  /**
   * 完整自检报告（多行）。耗时约 0.4 秒（两路音源各探一次），必须在后台线程调用。
   */
  static String collect(Context ctx) {
    int op = audioOpMode(ctx);
    StringBuilder sb = new StringBuilder();
    sb.append("壳 ").append(BuildConfig.VERSION_NAME)
        .append(" · targetSdk ").append(ctx.getApplicationInfo().targetSdkVersion)
        .append('\n');
    sb.append("系统 ")
        .append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
        .append(" · Android ").append(Build.VERSION.RELEASE)
        .append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n");
    sb.append("WebView 内核 ").append(webViewLabel()).append('\n');
    sb.append("RECORD_AUDIO 权限 ").append(permLabel(ctx, Manifest.permission.RECORD_AUDIO))
        .append(" · AppOps ").append(opModeLabel(op))
        .append(" · MODIFY_AUDIO_SETTINGS ").append(permLabel(ctx, Manifest.permission.MODIFY_AUDIO_SETTINGS))
        .append('\n');
    sb.append(audioManagerLabel(ctx)).append('\n');
    sb.append("原生录音 MIC：").append(probe(MediaRecorder.AudioSource.MIC)).append('\n');
    sb.append("原生录音 VOICE_COMMUNICATION：")
        .append(probe(MediaRecorder.AudioSource.VOICE_COMMUNICATION));
    return sb.toString();
  }
}
