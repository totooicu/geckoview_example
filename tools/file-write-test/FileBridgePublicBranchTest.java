package org.mozilla.geckoview_example.jsbridge;

import android.os.Build;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.json.JSONObject;

/**
 * 拿真实的 {@link FileBridge} 在桌面上跑 API 26-28 那条分支（普通文件读写，桩里都是真文件），
 * 验的是「FileBridge 到底有没有去问清单」——{@link PublicWriteScopeSelfTest} 只验判定本身，
 * 判定再接不上，洞就还在。
 *
 * <p>桩只覆盖 {@code java.io} 能表达的部分：公共目录与 filesDir 指到临时目录，
 * 权限一律当已给。MediaStore 那半边（29+ 的 content uri、"wa" 模式）桌面没有等价物，
 * 所以这里刻意把 {@code Build.VERSION.SDK_INT} 摆成 28；29+ 的语义仍要真机，
 * 见 app/src/main/assets/test.html 的两个新按钮。</p>
 */
public final class FileBridgePublicBranchTest {

  private static int passed = 0;
  private static int failed = 0;

  private static File sDownloads;
  private static File sFilesDir;

  private FileBridgePublicBranchTest() {}

  public static void main(String[] args) throws Exception {
    // 26-28：走 File 分支。桩的 SDK_INT 刻意不是 final，就是为了让两条分支都能类型检查、
    // 这一条还能真跑。
    Build.VERSION.SDK_INT = 28;

    File root = new File(System.getProperty("java.io.tmpdir"), "file-write-test-" + System.nanoTime());
    sFilesDir = new File(root, "files");
    sDownloads = new File(root, "sdcard/Download");
    sFilesDir.mkdirs();
    sDownloads.mkdirs();
    System.setProperty("dr.test.filesDir", sFilesDir.getAbsolutePath());
    System.setProperty("dr.test.externalDir", new File(root, "sdcard").getAbsolutePath());

    writeAndAppend();
    rejectsSelfReportedFileUri();
    rejectsForeignPath();
    unknownUriCreatesNoFile();
    readsBackPlainText();
    deletesAndBlocksReuse();
    base64LandsAsRealBytes();
    singleWriteKeepsOldShape();

    System.out.println();
    System.out.println(failed == 0
        ? "PASS: FileBridge（26-28 分支）全部断言通过（" + passed + " 项）"
        : "FAIL: " + failed + " 项未通过 / " + (passed + failed) + " 项");
    if (failed > 0) {
      System.exit(1);
    }
  }

  /* ------------------------------------------------------------------ 用例 */

  /** H1-H2 首片 + 续写：v2 的语义不能被收口弄坏 */
  private static void writeAndAppend() throws Exception {
    System.out.println("[H1-H2] 首片写入、第二片真的接在后面");
    FileBridge fb = newBridge();
    String name = "chunks.txt";
    JsBridge.clearReplies();
    fb.handle("r1", "file.writePublic", params(name, "AAA", null, false), new JsBridge());
    JSONObject res = okResult("r1");
    String path = res == null ? "" : res.optString("path", "");
    check("H1 首片成功并回 path（指向临时 Downloads）",
        path.startsWith(sDownloads.getAbsolutePath()) && "AAA".equals(read(new File(path))), path);

    JsBridge.clearReplies();
    fb.handle("r2", "file.writePublic", params(name, "BBB", path, true), new JsBridge());
    JSONObject res2 = okResult("r2");
    check("H2 续写成功，落点还是同一个文件",
        res2 != null && path.equals(res2.optString("path", "")), res2 == null ? "" : res2.toString());
    check("H2b 内容接成了 AAABBB", "AAABBB".equals(read(new File(path))), read(new File(path)));

    JsBridge.clearReplies();
    fb.handle("r3", "file.readPublic", uriParams(path), new JsBridge());
    check("H3 读回来就是 AAABBB", "AAABBB".equals(textOf("r3")), textOf("r3"));
  }

  /** H4 这次的洞：页面自报一条裸绝对路径，直达本 App 的私有目录 */
  private static void rejectsSelfReportedFileUri() throws Exception {
    System.out.println("[H4] 自报位置指向本 App 私有目录 → 拒，且一个字节都不许变");
    File log = new File(sFilesDir, "location_log.jsonl");
    write(log, "ORIGINAL-LOCATION-LOG\n");
    FileBridge fb = newBridge();
    // 26-28 那条分支当年是 new File(uriParam)：给一条裸路径就真的写过去了，这里必须被拒
    JsBridge.clearReplies();
    fb.handle("e1", "file.writePublic",
        params("evil.txt", "PWNED", log.getAbsolutePath(), true), new JsBridge());
    check("H4a 裸路径被拒（不是静默成功）", errorOf("e1") != null, errorOf("e1"));
    check("H4b 理由说清是位置不认识",
        String.valueOf(errorOf("e1")).contains("不认识"), errorOf("e1"));
    check("H4c 定位兜底日志一个字节都没变", "ORIGINAL-LOCATION-LOG\n".equals(read(log)), read(log));

    // 29+ 那条分支的形态是 file:// Content uri——真机上 ContentResolver 认 file:，
    // 桌面这条只能验到「判定同样拒」，真覆盖与否留给真机按钮
    JsBridge.clearReplies();
    fb.handle("e1b", "file.writePublic",
        params("evil.txt", "PWNED", "file://" + log.getAbsolutePath(), true), new JsBridge());
    check("H4d file:// 形式同样被拒", errorOf("e1b") != null, errorOf("e1b"));
    check("H4e 原文件依旧没动", "ORIGINAL-LOCATION-LOG\n".equals(read(log)), read(log));
  }

  /** H5 页面给裸绝对路径（26-28 分支当年直接 new File(uriParam)）*/
  private static void rejectsForeignPath() throws Exception {
    System.out.println("[H5] 裸绝对路径（Downloads 之外）→ 拒");
    File outside = new File(sDownloads.getParentFile(), "outside.txt");
    FileBridge fb = newBridge();
    JsBridge.clearReplies();
    fb.handle("e2", "file.writePublic",
        params("x.txt", "PWNED", outside.getAbsolutePath(), true), new JsBridge());
    check("H5a 被拒", errorOf("e2") != null, errorOf("e2"));
    check("H5b 那个路径上没长出文件", !outside.exists(), outside.getAbsolutePath());
  }

  /** H6 拒的同时不该顺手建新文件（校验发生在 insert / 建文件之前）*/
  private static void unknownUriCreatesNoFile() throws Exception {
    System.out.println("[H6] 清单外的续写不落任何新文件");
    int before = sDownloads.listFiles().length;
    FileBridge fb = newBridge();
    JsBridge.clearReplies();
    fb.handle("e3", "file.writePublic",
        params("ghost.txt", "PWNED", sDownloads.getAbsolutePath() + "/ghost.txt", true), new JsBridge());
    check("H6a 被拒", errorOf("e3") != null, errorOf("e3"));
    check("H6b Downloads 目录里的文件数没变", before == sDownloads.listFiles().length,
        before + " -> " + sDownloads.listFiles().length);
  }

  /** H7 读：清单外的 uri 不许读（v2 之前照着 open 任意 content uri）*/
  private static void readsBackPlainText() throws Exception {
    System.out.println("[H7] readPublic 只认清单");
    File secret = new File(sFilesDir, "secret.txt");
    write(secret, "TOP-SECRET");
    FileBridge fb = newBridge();
    JsBridge.clearReplies();
    fb.handle("e4", "file.readPublic", uriParams("file://" + secret.getAbsolutePath()), new JsBridge());
    check("H7a 陌生 uri 读不了", errorOf("e4") != null, errorOf("e4"));
    JsBridge.clearReplies();
    fb.handle("e5", "file.readPublic", uriParams(""), new JsBridge());
    check("H7b 空 uri 报「缺少」", String.valueOf(errorOf("e5")).contains("缺少"), errorOf("e5"));
  }

  /** H8 删：清单内可删、删完同一路径不能再写 */
  private static void deletesAndBlocksReuse() throws Exception {
    System.out.println("[H8] deletePublic：能删自己发的，删完不许复活");
    FileBridge fb = newBridge();
    JsBridge.clearReplies();
    fb.handle("d1", "file.writePublic", params("gone.txt", "BYE", null, false), new JsBridge());
    String path = okResult("d1").optString("path", "");
    File f = new File(path);
    File other = new File(sFilesDir, "keepme.txt");
    write(other, "KEEP");

    JsBridge.clearReplies();
    fb.handle("d2", "file.deletePublic", uriParams("file://" + other.getAbsolutePath()), new JsBridge());
    check("H8a 删陌生目标被拒", errorOf("d2") != null, errorOf("d2"));
    check("H8b 那个文件还在", other.exists(), other.getAbsolutePath());

    JsBridge.clearReplies();
    fb.handle("d3", "file.deletePublic", uriParams(path), new JsBridge());
    check("H8c 自己发出去的删掉了", Boolean.TRUE.equals(deletedOf("d3")) && !f.exists(),
        String.valueOf(deletedOf("d3")));
    JsBridge.clearReplies();
    fb.handle("d4", "file.writePublic", params("gone.txt", "AGAIN", path, true), new JsBridge());
    check("H8d 删完后拿旧路径来续写 → 拒", errorOf("d4") != null, errorOf("d4"));
  }

  /** H9 base64 片落的是真字节（0x00-0xFF 全走一遍），不是 base64 字母串 */
  private static void base64LandsAsRealBytes() throws Exception {
    System.out.println("[H9] encoding:base64 → 落盘是真字节");
    byte[] all = new byte[256];
    for (int i = 0; i < 256; i++) {
      all[i] = (byte) i;
    }
    String b64 = Base64.getEncoder().encodeToString(all);
    FileBridge fb = newBridge();
    JSONObject p = params("bin.bin", null, null, false);
    p.put("encoding", "base64");
    p.put("content", b64);
    p.put("mime", "application/octet-stream");
    JsBridge.clearReplies();
    fb.handle("b1", "file.writePublic", p, new JsBridge());
    String path = okResult("b1").optString("path", "");
    byte[] got = readBytes(new File(path));
    check("H9a 256 个字节逐字节相等（不是 base64 文本）",
        got.length == 256 && java.util.Arrays.equals(all, got), "落盘 " + got.length + " 字节");

    JSONObject p2 = params("bin.bin", null, path, true);
    p2.put("encoding", "base64");
    p2.put("content", Base64.getEncoder().encodeToString(new byte[] {1, 2, 3}));
    JsBridge.clearReplies();
    fb.handle("b2", "file.writePublic", p2, new JsBridge());
    byte[] got2 = readBytes(new File(okResult("b2").optString("path", path)));
    check("H9b 第二片接在 256 字节之后（共 259）", got2.length == 259, "现在 " + got2.length + " 字节");
  }

  /** H10 老调用方不传 encoding / append：仍是 UTF-8 单次写、返回形状不变 */
  private static void singleWriteKeepsOldShape() throws Exception {
    System.out.println("[H10] 老参数（无 encoding / 无 append）行为不变");
    FileBridge fb = newBridge();
    JsBridge.clearReplies();
    fb.handle("o1", "file.writePublic", params("legacy.txt", "你好 hello", null, false), new JsBridge());
    JSONObject res = okResult("o1");
    String path = res.optString("path", "");
    check("H10a 落点仍在公共 Downloads 下、名字原样",
        path.startsWith(sDownloads.getAbsolutePath()) && new File(path).getName().equals("legacy.txt"),
        path);
    check("H10b 内容按 UTF-8 单次写", "你好 hello".equals(read(new File(path))), read(new File(path)));
    check("H10c 回包字段 name / location / bytes 都在",
        res.optString("name", "").equals("legacy.txt")
            && res.optString("location", "").equals("Downloads")
            && res.get("bytes") != null,
        res.toString());
  }

  /* ------------------------------------------------------------------ 工具 */

  private static FileBridge newBridge() {
    return new FileBridge(new android.content.Context(), new JsBridge());
  }

  private static JSONObject params(String name, String content, String uri, boolean append) {
    JSONObject p = new JSONObject();
    p.put("name", name);
    if (content != null) {
      p.put("content", content);
    }
    if (uri != null) {
      p.put("uri", uri);
    }
    p.put("append", append);
    p.put("mime", "text/plain");
    return p;
  }

  private static JSONObject uriParams(String uri) {
    JSONObject p = new JSONObject();
    p.put("uri", uri);
    return p;
  }

  private static JSONObject okResult(String id) {
    for (JsBridge.Reply r : JsBridge.replies()) {
      if (r.id.equals(id)) {
        if (r.ok) {
          return r.result;
        }
        failed++;
        System.out.println("  ✗ " + id + " 的回包应当是成功 — " + r.error);
        return new JSONObject();
      }
    }
    failed++;
    System.out.println("  ✗ " + id + " 没有回包");
    return new JSONObject();
  }

  private static String textOf(String id) {
    JSONObject r = resultOf(id);
    return r == null ? "" : r.optString("content", "");
  }

  private static Object deletedOf(String id) {
    JSONObject r = resultOf(id);
    return r == null ? null : r.get("deleted");
  }

  private static String errorOf(String id) {
    for (JsBridge.Reply r : JsBridge.replies()) {
      if (r.id.equals(id)) {
        return r.ok ? null : r.error;
      }
    }
    return "没有回包";
  }

  private static JSONObject resultOf(String id) {
    for (JsBridge.Reply r : JsBridge.replies()) {
      if (r.id.equals(id)) {
        return r.ok ? r.result : null;
      }
    }
    return null;
  }

  private static String read(File f) throws Exception {
    return f.exists() ? new String(readBytes(f), StandardCharsets.UTF_8) : "<没有这个文件>";
  }

  private static byte[] readBytes(File f) throws Exception {
    if (!f.exists()) {
      return new byte[0];
    }
    try (InputStream in = new FileInputStream(f)) {
      ByteArrayOutputStream buf = new ByteArrayOutputStream();
      byte[] tmp = new byte[8192];
      int n;
      while ((n = in.read(tmp)) > 0) {
        buf.write(tmp, 0, n);
      }
      return buf.toByteArray();
    }
  }

  private static void write(File f, String text) throws Exception {
    try (FileOutputStream out = new FileOutputStream(f)) {
      out.write(text.getBytes(StandardCharsets.UTF_8));
    }
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
