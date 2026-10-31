package org.json;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 桩，但**真的存值**：桌面要拿它构造 params、并检查 FileBridge / NetBridge 返回给页面的字段。
 * 只实现 FileBridge / NetBridge / JsBridge 用到的那几个方法，别在这里加功能。
 */
public class JSONObject {
  private final Map<String, Object> mValues = new LinkedHashMap<>();

  public JSONObject() {}

  /**
   * 从 Map 构造（NetBridge 把响应头交给页面时用它）。
   * null 的键直接跳过，和 android 的实现一致——否则页面上会出现 "null" 字符串。
   */
  public JSONObject(Map<?, ?> copyFrom) {
    if (copyFrom != null) {
      for (Map.Entry<?, ?> e : copyFrom.entrySet()) {
        if (e.getKey() == null || e.getValue() == null) {
          continue;
        }
        mValues.put(String.valueOf(e.getKey()), e.getValue());
      }
    }
  }

  /**
   * 桩**不解析 JSON 文本**：桌面测试构造 params 一律用 put，回包只检查字段值。
   * 谁哪天改成靠字符串解析取数据，这里必须炸出来，而不是悄悄返回一个空对象
   * （空对象会让 optString 一路回落到默认值，测试看着过、真机上少字段）。
   */
  public JSONObject(String json) {
    throw new UnsupportedOperationException("桩不解析 JSON 文本，请改用 put 构造（见 net-http-test/README）");
  }

  public JSONObject put(String name, String value) { mValues.put(name, value); return this; }

  public JSONObject put(String name, int value) { mValues.put(name, value); return this; }

  public JSONObject put(String name, long value) { mValues.put(name, value); return this; }

  public JSONObject put(String name, boolean value) { mValues.put(name, value); return this; }

  public JSONObject put(String name, Object value) { mValues.put(name, value); return this; }

  public Object get(String name) { return mValues.get(name); }

  public Object opt(String name) { return mValues.get(name); }

  public int optInt(String name, int fallback) {
    Object v = mValues.get(name);
    if (v == null) {
      return fallback;
    }
    if (v instanceof Number) {
      return ((Number) v).intValue();
    }
    try {
      return Integer.parseInt(String.valueOf(v).trim());
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  public JSONObject optJSONObject(String name) {
    Object v = mValues.get(name);
    return v instanceof JSONObject ? (JSONObject) v : null;
  }

  /** android 的 keys() 返回键名的迭代器（NetBridge 遍历请求头用它）。 */
  public Iterator<String> keys() { return mValues.keySet().iterator(); }

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
