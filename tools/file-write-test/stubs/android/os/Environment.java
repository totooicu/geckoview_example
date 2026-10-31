package android.os;

import java.io.File;

public class Environment {
  public static final String DIRECTORY_DOWNLOADS = "Download";

  /** 公共 Downloads 目录同样指到临时目录；不设属性就退回真机上的路径形状 */
  public static File getExternalStoragePublicDirectory(String type) {
    String root = System.getProperty("dr.test.externalDir", "/storage/emulated/0");
    return new File(root, type);
  }
}
