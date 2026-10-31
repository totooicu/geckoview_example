package android.content;

import java.io.File;

/**
 * 桩：filesDir 指到测试给的临时目录，这样私有目录（file.writePrivate）与
 * 越界目标（files/location_log.jsonl）都是真文件，断言能读到字节。
 */
public class Context {
  public Context getApplicationContext() { return this; }

  public File getFilesDir() {
    return new File(System.getProperty("dr.test.filesDir", "/tmp/stub-files"));
  }

  public ContentResolver getContentResolver() { return new ContentResolver(); }
}
