package com.github.catvod.spider;
import org.json.*;
public class TestHomeCount {
  public static void main(String[] x) throws Exception {
    Qinghuaci q = new Qinghuaci(); q.init("{\"aggregate\":false}");
    long t=System.currentTimeMillis();
    JSONObject r = new JSONObject(q.homeContent(true));
    System.out.println("home ms="+(System.currentTimeMillis()-t));
    System.out.println("banners="+r.optJSONArray("banners").length()+" sections="+r.optJSONArray("sections").length()+" filters="+r.optJSONObject("filters").length());
    JSONArray b=r.optJSONArray("banners");
    for(int i=0;i<b.length();i++){JSONObject o=b.getJSONObject(i);System.out.println("B"+i+" id="+o.optString("vod_id")+" name="+o.optString("vod_name")+" pic="+o.optString("vod_pic"));}
    JSONArray s=r.optJSONArray("sections");
    for(int i=0;i<s.length();i++){JSONObject o=s.getJSONObject(i);System.out.println("S"+i+" "+o.optString("title")+" n="+o.optJSONArray("list").length());}
    System.out.println("filters keys="+r.optJSONObject("filters").keySet());
    System.out.println("filter[2]="+r.optJSONObject("filters").optJSONArray("2"));
  }
}
