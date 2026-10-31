package org.mozilla.geckoview_example;

/**
 * 桩：真实的那份由 gradle 生成（build/.../BuildConfig.java），沙箱跑不了 gradle。
 * NetBridge 只取 VERSION_NAME（写进 User-Agent 与 net.capabilities 的 version）。
 * 这里给的是假值——桌面测试要断言的从来不是版本号本身。
 */
public final class BuildConfig {
  public static final String VERSION_NAME = "0.0.0-stub";

  private BuildConfig() {}
}
