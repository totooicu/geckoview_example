package android.os;

/**
 * 桩。SDK_INT 在真机上是 final 但由静态块赋值，不是编译期常量；这里刻意留成可变，
 * 好让 javac 两条分支都去类型检查——写成常量的话 26-28 那段会被当成死代码跳过后
 * 不再报错，桩就白设了。
 */
public class Build {
  public static class VERSION {
    public static int SDK_INT = 34;
  }

  public static class VERSION_CODES {
    public static final int Q = 29;
  }
}
