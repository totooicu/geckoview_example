package android.provider;

import android.net.Uri;

public class MediaStore {
  public static class Downloads {
    public static final String DISPLAY_NAME = "_display_name";
    public static final String MIME_TYPE = "mime_type";
    public static final String RELATIVE_PATH = "relative_path";
    public static final Uri EXTERNAL_CONTENT_URI =
        Uri.parse("content://media/external/downloads");
  }
}
