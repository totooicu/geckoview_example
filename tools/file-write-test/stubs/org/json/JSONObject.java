package org.json;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 桩，但**真的存值**：桌面要拿它构造 params、并检查 FileBridge 返回给页面的字段。
 * 只实现 FileBridge / JsBridge 用到的那几个方法，别在这里加功能。
 */
public class JSONObject {
  private final Map<String, Object> mValues = new LinkedHashMap<>();

  public JSONObject put(String name, String value) { mValues.put(name, value); return this; }

  public JSONObject put(String name, int value) { mValues.put(name, value); return this; }

  public JSONObject put(String name, long value) { mValues.put(name, value); return this; }

  public JSONObject put(String name, boolean value) { mValues.put(name, value); return this; }

  public JSONObject put(String name, Object value) { mValues.put(name, value); return this; }

  public Object get(String name) { return mValues.get(name); }

  public String optString(String name, String fallback) {
    Object v = mValues.get(name);
    return v == null ? fallback : String.valueOf(v);
  }

  public boolean optBoolean(String name, boolean fallback) {
    Object v = mValues.get(name);
    if (v == null) {
      return fallback;
    }
    if (v instanceof Boolean) {
      return (Boolean) v;
    }
    return Boolean.parseBoolean(String.valueOf(v));
  }

  @Override
  public String toString() { return mValues.toString(); }
}
