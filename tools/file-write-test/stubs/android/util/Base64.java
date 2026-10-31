package android.util;

public class Base64 {
  public static final int DEFAULT = 0;
  public static final int NO_WRAP = 2;

  /** android 的 DEFAULT ≈ 带 padding 的标准 base64，网页 btoa 出来的正是这种 */
  public static byte[] decode(String str, int flags) {
    if (flags != DEFAULT) {
      throw new UnsupportedOperationException("桩只实现 android.util.Base64.DEFAULT 的解码");
    }
    return java.util.Base64.getDecoder().decode(str);
  }

  /**
   * NO_WRAP = 带 padding、一行到底（不插换行）。net.request 的回包用它，
   * 页面侧 base64ToBytes 按标准 padding 解，两边必须对上。
   * 其余 flag（DEFAULT 会折行）没实现——真要用的人应该先在这里补上，而不是静默少一段。
   */
  public static String encodeToString(byte[] input, int flags) {
    if (flags != NO_WRAP) {
      throw new UnsupportedOperationException("桩只实现 NO_WRAP 的编码（net.request 回包用的那条）");
    }
    return java.util.Base64.getEncoder().encodeToString(input);
  }
}
