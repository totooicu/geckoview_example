package org.mozilla.geckoview_example.jsbridge;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 公共目录（Downloads）落点的唯一清单：只有壳自己发出去过的位置，才允许再被写、被读、被删。
 *
 * <p>为什么要有它。file.writePublic 自 v2 起支持分片续写，网页把首片返回的 uri / path
 * 带回来定位文件，于是「页面说位置在哪，壳就写到哪」：
 *
 * <ul>
 *   <li>{@code uri = "file:///data/data/&lt;本 App&gt;/files/location_log.jsonl"} ——
 *       ContentResolver 认 file:，一趟就把网页依赖的定位兜底日志覆盖掉；
 *   <li>API 26-28 那条分支 {@code new File(uriParam)} —— 整个绕过 safeName，
 *       外部存储上任何路径都写得进去；
 *   <li>{@code file.readPublic} / {@code file.deletePublic} 照单全收 ——
 *       一个页面能让壳把它权限范围内的文件读走、删掉。
 * </ul>
 *
 * <p>规矩收成一句话：<b>位置由壳给，页面只能原样带回来。</b>清单里没有的一律拒。
 * 页面刷新、进程重启后清单是空的，这时带旧 uri 来会被拒——正确做法是重新写一片新的，
 * 而不是继续往一个壳已经不认识的落点上追加。
 *
 * <p>这个类刻意不 import 任何 Android API。壳工程 CLAUDE.md「环境备注」写明沙箱里没有
 * Android SDK，判定逻辑只有纯 Java 才可能在桌面上 javac + 断言跑绿（见
 * {@code tools/file-write-test/}），否则这条收口只能靠人肉读 diff。
 */
public final class PublicWriteScope {

  /**
   * 清单长度上限。一次导出正常只往里加一条（首片建文件），分片再多也还是那一条；
   * 能涨到上百条的只有批量试探，那就丢最旧的——最旧那条被丢掉后页面会收到一条明确的拒绝，
   * 而不是悄无声息地写到别的地方去。
   */
  private static final int MAX_ISSUED = 128;

  /** 拒绝理由：给页面看的，要说清「为什么这个 uri 不行」，别只说 invalid */
  private static final String UNKNOWN_TARGET =
      "公共文件位置不认识：uri 必须是本壳 file.writePublic 返回过的位置，页面自己拼的路径（含 file:）一律不收";

  /** 判定结果。三个字段互斥：createNew 建新；target 用既有落点；error 拒。 */
  public static final class Decision {
    public final boolean createNew;
    public final String target;
    public final String error;

    private Decision(boolean createNew, String target, String error) {
      this.createNew = createNew;
      this.target = target;
      this.error = error;
    }

    static Decision newFile() {
      return new Decision(true, null, null);
    }

    static Decision existing(String target) {
      return new Decision(false, target, null);
    }

    static Decision reject(String error) {
      return new Decision(false, null, error);
    }
  }

  private final Map<String, Boolean> mIssued =
      new LinkedHashMap<String, Boolean>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
          return size() > MAX_ISSUED;
        }
      };

  /** 壳把一份文件交到用户手里之后，立刻把这个落点记进清单 */
  public synchronized void issue(String target) {
    if (target == null || target.isEmpty()) {
      return;
    }
    mIssued.put(target, Boolean.TRUE);
  }

  public synchronized boolean knows(String target) {
    return target != null && !target.isEmpty() && mIssued.containsKey(target);
  }

  /** deletePublic 删成之后调用：文件已经没了，还留在清单里就等于允许往一个不存在的落点续写 */
  public synchronized void forget(String target) {
    if (target != null) {
      mIssued.remove(target);
    }
  }

  /** 清单当前条数：给自测和日志用，规矩本身不看它 */
  public synchronized int issuedCount() {
    return mIssued.size();
  }

  /**
   * file.writePublic 用：没给 uri 就是建新文件（首片 / 单次写），
   * 给了 uri 只认清单里的那一个。
   */
  public synchronized Decision resolveWrite(String uriParam) {
    if (uriParam == null || uriParam.isEmpty()) {
      return Decision.newFile();
    }
    return knows(uriParam) ? Decision.existing(uriParam) : Decision.reject(UNKNOWN_TARGET);
  }

  /** file.readPublic / file.deletePublic 用：这两个动作没有「新建」这条路，只认清单。 */
  public synchronized Decision resolveTarget(String target) {
    if (target == null || target.isEmpty()) {
      return Decision.reject("缺少 uri");
    }
    return knows(target) ? Decision.existing(target) : Decision.reject(UNKNOWN_TARGET);
  }

  /** 只留文件名，路径分隔符和上跳都吃掉——私有目录与 26-28 的公共目录都靠它 */
  public static String safeName(String name) {
    String n = name.replace("..", "_").replace("/", "_").replace("\\", "_");
    return n.isEmpty() ? "unnamed" : n;
  }
}
