package com.github.catvod.spider;
import org.json.*;
import java.util.*;
public class TestOlevod {
 public static void main(String[] a) throws Exception {
  Olevod o=new Olevod(); o.init("");
  long t=System.currentTimeMillis();
  String home=o.homeContent(true);
  JSONObject h=new JSONObject(home);
  System.out.println("home ms="+(System.currentTimeMillis()-t)+" classes="+h.optJSONArray("class").length()+" banners="+h.optJSONArray("banners").length()+" sections="+h.optJSONArray("sections").length());
  JSONArray sec=h.optJSONArray("sections");
  for(int i=0;i<sec.length();i++){JSONObject s=sec.getJSONObject(i);System.out.println(" S "+s.optString("title")+" n="+s.optJSONArray("list").length());}
  System.out.println("filters[1]="+h.optJSONObject("filters").optJSONArray("1").length());
  t=System.currentTimeMillis();
  JSONObject cat=new JSONObject(o.categoryContent("1","1",true,new HashMap<String,String>()));
  System.out.println("cat ms="+(System.currentTimeMillis()-t)+" n="+cat.optJSONArray("list").length()+" pagecount="+cat.optInt("pagecount")+" total="+cat.optInt("total"));
  System.out.println("first="+cat.optJSONArray("list").optJSONObject(0).toString());
  t=System.currentTimeMillis();
  JSONObject se=new JSONObject(o.searchContent("庆余年",false,"1"));
  System.out.println("search ms="+(System.currentTimeMillis()-t)+" n="+se.optJSONArray("list").length()+" total="+se.optInt("total"));
  if(se.optJSONArray("list").length()>0){
    String id=se.optJSONArray("list").optJSONObject(0).optString("vod_id");
    t=System.currentTimeMillis();
    JSONObject d=new JSONObject(o.detailContent(Arrays.asList(id)));
    JSONObject it=d.optJSONArray("list").optJSONObject(0);
    System.out.println("detail ms="+(System.currentTimeMillis()-t)+" name="+it.optString("vod_name")+" from="+it.optString("vod_play_from")+" urls="+it.optString("vod_play_url").split("#").length);
    String first=it.optString("vod_play_url").split("#")[0];
    String url=first.split("[$]",2)[1];
    System.out.println("play="+o.playerContent("欧乐",url,new ArrayList<String>()));
  }
  JSONObject hot=new JSONObject(o.action("hot:"));
  System.out.println("hot n="+hot.optJSONArray("list").length());
 }
}
