package com.github.catvod.spider;
import org.json.*;
import java.lang.reflect.*;
import java.util.*;
public class TestHot {
  public static void main(String[] x) throws Exception {
    Qinghuaci q = new Qinghuaci();
    q.init("{}");
    Method m = Qinghuaci.class.getDeclaredMethod("api", String.class, LinkedHashMap.class);
    m.setAccessible(true);
    for (String path : new String[]{"/anonymous/home/hotSearch","/anonymous/home/hot/search","/anonymous/home/hotWord"}) {
      try {
        JSONObject r = (JSONObject) m.invoke(q, path, new LinkedHashMap<String,String>());
        System.out.println(path + " => " + r.toString().substring(0, Math.min(600, r.toString().length())));
      } catch (Throwable e) { System.out.println(path + " ERR " + e.getCause()); }
    }
  }
}
