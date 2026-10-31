package android.util;

/**
 * 桩：android.util.Log 的五个级别都有两种写法（带 / 不带 Throwable），返回值是写出的字节数。
 * 打去向照抄真机习惯——诊断级别走 stdout，警告与错误走 stderr，
 * 这样桌面跑测试时日志混在终端里也能一眼分出哪条是问题。
 */
public class Log {
  public static int v(String tag, String msg) { return println(System.out, "V", tag, msg, null); }

  public static int d(String tag, String msg) { return println(System.out, "D", tag, msg, null); }

  public static int i(String tag, String msg) { return println(System.out, "I", tag, msg, null); }

  public static int w(String tag, String msg) { return println(System.err, "W", tag, msg, null); }

  public static int w(String tag, String msg, Throwable tr) { return println(System.err, "W", tag, msg, tr); }

  public static int e(String tag, String msg) { return println(System.err, "E", tag, msg, null); }

  public static int e(String tag, String msg, Throwable tr) { return println(System.err, "E", tag, msg, tr); }

  private static int println(java.io.PrintStream out, String level, String tag, String msg, Throwable tr) {
    out.println(level + "/" + tag + ": " + msg);
    if (tr != null) {
      out.println("    " + tr);
    }
    return msg == null ? 0 : msg.length();
  }
}
