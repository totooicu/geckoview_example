package android.content;

import android.net.Uri;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 桩，但要**照真机的脾气**失败：真机上的 ContentResolver 只认 content:（以及本 App 有权限的
 * file: 由具体 provider 决定），给它一条裸路径它会报 Unknown URI。这里刻意对非 content:
 * 一律抛 FileNotFoundException——否则 FileBridge 里「拿裸路径去 openInputStream」这种真机
 * 上必坏的写法，在桌面上会被桩悄悄纵容过去。
 *
 * <p>MediaStore 那半边（insert / "wa" 模式）桌面没有等价物，所以 {@link #insert} 直接抛，
 * 测试一律把 SDK_INT 摆成 28 走 File 分支；29+ 的语义留给真机（assets/test.html）。</p>
 */
public class ContentResolver {
  private static File requireContent(String text) throws FileNotFoundException {
    if (!text.startsWith("content://")) {
      throw new FileNotFoundException("Unknown URI: " + text);
    }
    return new File(text);
  }

  public Uri insert(Uri url, ContentValues values) throws FileNotFoundException {
    throw new FileNotFoundException("桌面桩不模拟 MediaStore：把 Build.VERSION.SDK_INT 设成 28");
  }

  public InputStream openInputStream(Uri uri) throws FileNotFoundException {
    requireContent(uri.toString());
    throw new FileNotFoundException("Unknown URI: " + uri);
  }

  public OutputStream openOutputStream(Uri uri, String mode) throws FileNotFoundException {
    requireContent(uri.toString());
    throw new FileNotFoundException("Unknown URI: " + uri);
  }

  public int delete(Uri uri, String selection, String selectionArgs) throws FileNotFoundException {
    requireContent(uri.toString());
    return 0;
  }
}
