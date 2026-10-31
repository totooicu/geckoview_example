package android.util;

public class Base64 {
  public static final int DEFAULT = 0;
  public static final int NO_WRAP = 2;

  /** android 的 DEFAULT ≈ 带 padding 的标准 base64，网页 btoa 出来的正是这种 */
  public static byte[] decode(String str, int flags) {
    return java.util.Base64.getDecoder().decode(str);
  }
}
