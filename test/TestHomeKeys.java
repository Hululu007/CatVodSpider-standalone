package com.github.catvod.spider;
import org.json.*; import java.lang.reflect.*; import java.util.*;
public class TestHomeKeys {
 public static void main(String[] a) throws Exception {
  Qinghuaci q=new Qinghuaci(); q.init("{}");
  Method m=Qinghuaci.class.getDeclaredMethod("homeData"); m.setAccessible(true);
  JSONObject d=(JSONObject)m.invoke(q);
  for (String k : d.keySet()) {
    Object v=d.opt(k);
    String info;
    if (v instanceof JSONObject) { JSONObject o=(JSONObject)v; info="obj keys="+o.keySet()+" title="+o.optString("title")+" n="+(o.optJSONArray("list")==null?-1:o.optJSONArray("list").length()); }
    else info=String.valueOf(v);
    System.out.println(k+" => "+info);
  }
 }
}
