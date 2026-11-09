package com.github.catvod.spider;
import org.json.*;
public class TestAction {
  public static void main(String[] x) throws Exception {
    Qinghuaci q = new Qinghuaci(); q.init("{\"aggregate\":false}");
    JSONObject r = new JSONObject(q.action("hot:"));
    JSONArray l = r.optJSONArray("list");
    System.out.println("hot n=" + (l==null?-1:l.length()));
    if(l!=null&&l.length()>0) System.out.println(l.getJSONObject(0).toString().substring(0,200));
  }
}
