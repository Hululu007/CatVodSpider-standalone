import com.github.catvod.spider.*;
import org.json.*;
import java.io.*;
import java.util.*;

public class TestBttwo {
    static StringBuilder sb = new StringBuilder();
    static void log(String s) { sb.append(s).append('\n'); }
    static String cut(String s, int n) { return s == null ? "null" : (s.length() > n ? s.substring(0, n) + "..." : s); }

    public static void main(String[] a) throws Exception {
        Bttwo b = new Bttwo();

        JSONObject home = new JSONObject(b.homeContent(true));
        JSONArray classes = home.optJSONArray("class");
        log("HOME classes=" + (classes == null ? -1 : classes.length()));
        for (int i = 0; i < classes.length(); i++) {
            JSONObject o = classes.getJSONObject(i);
            log("  CLASS " + o.optString("type_id") + " | " + o.optString("type_name"));
        }
        JSONObject filters = home.optJSONObject("filters");
        log("FILTER keys=" + (filters == null ? "null" : filters.keySet().toString()));
        if (filters != null && filters.has("all")) {
            JSONArray fa = filters.getJSONArray("all");
            for (int i = 0; i < fa.length(); i++) {
                JSONObject f = fa.getJSONObject(i);
                log("  F " + f.optString("key") + " " + f.optString("name") + " values=" + f.getJSONArray("value").length());
            }
        }
        JSONArray hl = home.optJSONArray("list");
        log("HOME list=" + (hl == null ? -1 : hl.length()));
        if (hl != null && hl.length() > 0) log("  FIRST " + hl.getJSONObject(0));

        JSONObject c1 = new JSONObject(b.categoryContent("1", "1", false, new HashMap<String, String>()));
        log("CAT 电影 p1 pagecount=" + c1.optInt("pagecount") + " items=" + c1.getJSONArray("list").length());
        if (c1.getJSONArray("list").length() > 0) log("  FIRST " + c1.getJSONArray("list").getJSONObject(0));
        JSONObject c2 = new JSONObject(b.categoryContent("1", "2", false, new HashMap<String, String>()));
        log("CAT 电影 p2 pagecount=" + c2.optInt("pagecount") + " items=" + c2.getJSONArray("list").length());
        if (c2.getJSONArray("list").length() > 0) log("  FIRST " + c2.getJSONArray("list").getJSONObject(0));

        HashMap<String, String> ext = new HashMap<String, String>();
        ext.put("type", "1");
        ext.put("area", "5");
        ext.put("year", "3");
        JSONObject cf = new JSONObject(b.categoryContent("1", "1", true, ext));
        log("CAT filter(剧情/美国/2025) pagecount=" + cf.optInt("pagecount") + " items=" + cf.getJSONArray("list").length());
        if (cf.getJSONArray("list").length() > 0) log("  FIRST " + cf.getJSONArray("list").getJSONObject(0));

        JSONObject s = new JSONObject(b.searchContent("中国", false, "1"));
        log("SEARCH 中国 items=" + s.getJSONArray("list").length());
        for (int i = 0; i < Math.min(5, s.getJSONArray("list").length()); i++) log("  " + s.getJSONArray("list").getJSONObject(i));

        JSONObject d = new JSONObject(b.detailContent(Arrays.asList("ch4qhla8l")));
        JSONArray dl = d.getJSONArray("list");
        log("DETAIL 中国式 items=" + dl.length());
        JSONObject it = dl.getJSONObject(0);
        log("  name=" + it.optString("vod_name"));
        log("  year=" + it.optString("vod_year") + " area=" + it.optString("vod_area") + " type=" + it.optString("type_name"));
        log("  pic=" + cut(it.optString("vod_pic"), 100));
        log("  actor=" + cut(it.optString("vod_actor"), 60));
        log("  from=" + it.optString("vod_play_from"));
        String pu = it.optString("vod_play_url");
        log("  episodes=" + (pu.split("#").length));
        log("  first=" + cut(pu.split("#")[0], 80));
        log("  last=" + cut(pu.substring(pu.lastIndexOf('#') + 1), 80));

        log("PLAY 中国式 ep1=" + cut(b.playerContent("两个BT", "ch4qhla8l|41045", new ArrayList<String>()), 400));

        JSONObject dm = new JSONObject(b.detailContent(Arrays.asList("ch4ro1k43")));
        log("DETAIL 蚁人 name=" + dm.getJSONArray("list").getJSONObject(0).optString("vod_name")
                + " eps=" + dm.getJSONArray("list").getJSONObject(0).optString("vod_play_url"));
        log("PLAY 蚁人=" + cut(b.playerContent("两个BT", "ch4ro1k43|41762", new ArrayList<String>()), 400));

        OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream("C:\\tmp\\bttwo_test.txt"), "UTF-8");
        w.write(sb.toString());
        w.close();
        System.out.println("done");
    }
}
