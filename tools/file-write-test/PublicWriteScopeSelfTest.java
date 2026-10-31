package org.mozilla.geckoview_example.jsbridge;

/**
 * {@link PublicWriteScope} 的自测：把「位置只能由壳给」这条规矩的每一种走法都判一遍。
 *
 * <p>为什么能在桌面上跑：{@code PublicWriteScope.java} 只依赖 {@code java.util}，不碰任何
 * Android API，这里编译的就是壳里那份。桥外层（MediaStore 真的建文件、"wa" 真的续写、
 * 权限框）要真机才验得到——那部分用 {@code app/src/main/assets/test.html} 里新加的两个按钮：
 * 「分片续写（应读到 AAABBB）」与「越界位置续写（应被拒）」。</p>
 *
 * <p>用法：{@code ./run.sh}（它会先用桩把真实的 {@code FileBridge.java} 一起 javac
 * 类型检查一遍，再跑这里）。</p>
 */
public final class PublicWriteScopeSelfTest {

  private static int passed = 0;
  private static int failed = 0;

  private PublicWriteScopeSelfTest() {}

  /** 网页首片拿到的落点：29+ 是 content uri，26-28 是绝对路径，两种都要过 */
  private static final String MEDIA_URI =
      "content://media/external/downloads/1234";

  private static final String FILE_PATH =
      "/storage/emulated/0/Download/dating-record-1.drp.zip";

  public static void main(String[] args) {
    firstWriteAndContinue();
    rejectsSelfReportedTargets();
    readAndDeleteShareTheSameGate();
    forgetAfterDelete();
    boundedRegistry();
    saveTsStyleChunking();
    safeNameRules();
    issueIgnoresBlank();

    System.out.println();
    System.out.println(failed == 0
        ? "PASS: PublicWriteScope 全部断言通过（" + passed + " 项）"
        : "FAIL: " + failed + " 项未通过 / " + (passed + failed) + " 项");
    if (failed > 0) {
      System.exit(1);
    }
  }

  /** S1-S2 首片与续写 */
  private static void firstWriteAndContinue() {
    System.out.println("[S1-S2] 首片建新文件、壳发出去的落点可以续写");
    PublicWriteScope s = new PublicWriteScope();
    PublicWriteScope.Decision first = s.resolveWrite("");
    check("S1 清单为空 + 页面没带 uri → 建新文件（首片/单次写不受影响）",
        first.createNew && first.error == null, String.valueOf(first.error));
    s.issue(MEDIA_URI);
    PublicWriteScope.Decision second = s.resolveWrite(MEDIA_URI);
    check("S2 壳发出去的 uri 带回来 → 放行，且 target 原样",
        !second.createNew && MEDIA_URI.equals(second.target) && second.error == null, second.target);
  }

  /** S3-S5 页面自己拼的地址一律不收 */
  private static void rejectsSelfReportedTargets() {
    System.out.println("[S3-S5] 页面自报位置被拒（v2.1 要修的就是这个洞）");
    PublicWriteScope s = new PublicWriteScope();
    s.issue(FILE_PATH);
    String[] evil = {
        "file:///data/data/org.mozilla.geckoview_example/files/location_log.jsonl",
        "file:///storage/emulated/0/DCIM/Camera/secret.jpg",
        "/storage/emulated/0/Download/other-app.txt",
        "content://media/external/downloads/9999",
    };
    for (String e : evil) {
      PublicWriteScope.Decision d = s.resolveWrite(e);
      check("S3 拒：" + e, d.error != null && !d.createNew && d.target == null, d.target);
    }
    for (String e : evil) {
      check("S4 同一道门也拦住读/删：" + e, s.resolveTarget(e).error != null, "");
    }
    PublicWriteScope.Decision d = s.resolveWrite(FILE_PATH);
    check("S5 续写只看 uri，name 说不动位置（换 name 也还写回壳给的那条）",
        FILE_PATH.equals(d.target), d.target);
  }

  /** S6 空 uri 的两种说法 */
  private static void readAndDeleteShareTheSameGate() {
    System.out.println("[S6] readPublic / deletePublic 没有「新建」这条路");
    PublicWriteScope s = new PublicWriteScope();
    s.issue(MEDIA_URI);
    PublicWriteScope.Decision empty = s.resolveTarget("");
    check("S6a 空 uri → 明确报「缺少 uri」，不是含糊的拒绝",
        empty.error != null && empty.error.contains("缺少 uri"), empty.error);
    check("S6b 清单里的 uri → 放行", s.resolveTarget(MEDIA_URI).error == null, "");
    check("S6c 全新清单上任何 uri 都不认识（进程重启后旧地址自然失效）",
        new PublicWriteScope().resolveTarget(MEDIA_URI).error != null, "");
  }

  /** S7 删过的落点不能复活 */
  private static void forgetAfterDelete() {
    System.out.println("[S7] deletePublic 成功后落点出清单");
    PublicWriteScope s = new PublicWriteScope();
    s.issue(MEDIA_URI);
    s.forget(MEDIA_URI);
    check("S7a 删完再写 → 拒", s.resolveWrite(MEDIA_URI).error != null, "");
    check("S7b 删完再读 → 拒", s.resolveTarget(MEDIA_URI).error != null, "");
    s.issue(MEDIA_URI);
    s.forget("别的一串");
    check("S7c forget 只摘它自己那条，不影响别的", s.knows(MEDIA_URI), "");
  }

  /** S8 清单有上限，且挤掉最旧的是「明确拒绝」而不是「悄悄放行」 */
  private static void boundedRegistry() {
    System.out.println("[S8] 清单上限 128：批量试探不会把它撑爆，也不会退化成放行");
    PublicWriteScope s = new PublicWriteScope();
    for (int i = 0; i < 200; i++) {
      s.issue("content://media/external/downloads/" + i);
    }
    check("S8a 最早的被挤掉 → 再写被拒",
        s.resolveWrite("content://media/external/downloads/0").error != null, "");
    check("S8b 最新的仍在清单", s.knows("content://media/external/downloads/199"), "");
    check("S8c 中间那条被挤掉的也拒",
        s.resolveWrite("content://media/external/downloads/50").error != null, "");
    check("S8d 清单封顶在 128 条，不随试探增长", s.issuedCount() == 128, "" + s.issuedCount());
  }

  /** S9 照 save.ts 的分片节奏走一遍：三片只该有一条落点 */
  private static void saveTsStyleChunking() {
    System.out.println("[S9] 网页 2MB 分片节奏（save.ts）对着清单走一遍");
    PublicWriteScope s = new PublicWriteScope();
    PublicWriteScope.Decision c1 = s.resolveWrite("");
    check("S9a 首片建新", c1.createNew, "");
    s.issue(FILE_PATH);
    PublicWriteScope.Decision c2 = s.resolveWrite(FILE_PATH);
    PublicWriteScope.Decision c3 = s.resolveWrite(FILE_PATH);
    check("S9b 后两片都续在同一个落点",
        FILE_PATH.equals(c2.target) && FILE_PATH.equals(c3.target), c2.target + " / " + c3.target);
    s.issue(FILE_PATH);
    s.issue(FILE_PATH);
    check("S9c 同一个落点反复交回来也不膨胀（清单仍只有这一条）",
        s.issuedCount() == 1, "" + s.issuedCount());
  }

  /** S10 文件名净化 */
  private static void safeNameRules() {
    System.out.println("[S10] safeName：只留文件名");
    String up = PublicWriteScope.safeName("../../etc/passwd");
    check("S10a 上跳与分隔符都吃掉：" + up,
        !up.contains("/") && !up.contains("\\") && !up.contains(".."), up);
    check("S10b 反斜杠也换掉",
        !PublicWriteScope.safeName("a\\b\\c").contains("\\"), PublicWriteScope.safeName("a\\b\\c"));
    check("S10c 空名字给 unnamed", "unnamed".equals(PublicWriteScope.safeName("")), "");
    check("S10d 正常文件名原样保留",
        "dating-record-1.drp.zip".equals(PublicWriteScope.safeName("dating-record-1.drp.zip")), "");
  }

  /** S11 issue 对空白入参不该把清单弄脏 */
  private static void issueIgnoresBlank() {
    System.out.println("[S11] issue 忽略空串 / null");
    PublicWriteScope s = new PublicWriteScope();
    s.issue("");
    s.issue(null);
    check("S11a 空串进不去清单", !s.knows(""), "");
    check("S11b null 不炸也不进清单", !s.knows(null), "");
    check("S11c 空串当 uri 传来 → 走建新那条路（与 S1 一致，不是拒绝）",
        s.resolveWrite("").createNew, "");
  }

  private static void check(String name, boolean ok, String detail) {
    if (ok) {
      passed++;
      System.out.println("  ✓ " + name);
    } else {
      failed++;
      System.out.println("  ✗ " + name + (detail == null || detail.isEmpty() ? "" : " — " + detail));
    }
  }
}
