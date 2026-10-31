package android.net;

public class Uri {
  private final String mText;

  private Uri(String text) { mText = text; }

  public static Uri parse(String uriString) { return new Uri(uriString); }

  @Override
  public String toString() { return mText; }
}
