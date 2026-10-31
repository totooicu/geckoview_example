package org.json;

import java.util.ArrayList;
import java.util.List;

public class JSONArray {
  private final List<Object> mItems = new ArrayList<>();

  public JSONArray put(String value) { mItems.add(value); return this; }

  public JSONArray put(Object value) { mItems.add(value); return this; }

  public int length() { return mItems.size(); }

  public Object get(int i) { return mItems.get(i); }

  @Override
  public String toString() { return mItems.toString(); }
}
