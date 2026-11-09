import com.github.catvod.spider.*;
import org.json.*;
import java.io.*;
import java.util.*;

public class TestCzzy {
    static StringBuilder sb = new StringBuilder();

    static void log(String s) { sb.append(s).append('\n'); }

    static String cut(String s, int n) {
        return s == null ? "null" : (s.length() > n ? s.substring(0, n) + "..." : s);
    }

    public static void main(String[] a) throws Exception {
        Czzy c = new Czzy();

        JSONObject home = new JSONObject(c.homeContent(true));
        JSONArray classes = home.optJSONArray("class");
        log("HOME classes=" + (classes == null ? -1 : classes.length()));
        for (int i = 0; i < classes.length(); i++) {
            JSONObject o = classes.getJSONObject(i);
            log("  CLASS " + o.optString("type_id") + " | " + o.optString("type_name"));
        }
        JSONObject filters = home.optJSONObject("filters");
        log("HOME filterKeys=" + (filters == null ? "null" : filters.keySet().toString()));
        if (filters != null && filters.has("all")) {
            JSONArray fa = filters.getJSONArray("all");
            for (int i = 0; i < fa.length(); i++) {
                JSONObject f = fa.getJSONObject(i);
                log("  FILTER " + f.optString("key") + " | " + f.optString("name")
                        + " | values=" + f.getJSONArray("value").length());
            }
        }
        JSONArray hl = home.optJSONArray("list");
        log("HOME list=" + (hl == null ? -1 : hl.length()));
        if (hl != null && hl.length() > 0) log("  FIRST " + hl.getJSONObject(0));

        JSONObject catAll = new JSONObject(c.categoryContent("all", "1", false, new HashMap<String, String>()));
        log("CAT all p1 = " + catAll.optInt("pagecount") + " items=" + catAll.getJSONArray("list").length());
        JSONObject catTop = new JSONObject(c.categoryContent("s:/dbtop250", "1", false, new HashMap<String, String>()));
        log("CAT top250 = " + catTop.optInt("pagecount") + " items=" + catTop.getJSONArray("list").length());
        if (catTop.getJSONArray("list").length() > 0) log("  TOP " + catTop.getJSONArray("list").getJSONObject(0));
        JSONObject catP2 = new JSONObject(c.categoryContent("dyy", "2", false, new HashMap<String, String>()));
        log("CAT dyy p2 = " + catP2.optInt("pagecount") + " items=" + catP2.getJSONArray("list").length());
        if (catP2.getJSONArray("list").length() > 0) log("  FIRST " + catP2.getJSONArray("list").getJSONObject(0));

        HashMap<String, String> ext = new HashMap<String, String>();
        ext.put("tag", "juqing");
        ext.put("area", "zh");
        ext.put("year", "2025");
        JSONObject catFilter = new JSONObject(c.categoryContent("dyy", "1", true, ext));
        log("CAT dyy filter(juqing/zh/2025) items=" + catFilter.getJSONArray("list").length());
        if (catFilter.getJSONArray("list").length() > 0) log("  FIRST " + catFilter.getJSONArray("list").getJSONObject(0));

        JSONObject d = new JSONObject(c.detailContent(Arrays.asList("20294")));
        JSONArray dl = d.optJSONArray("list");
        log("DETAIL list=" + (dl == null ? -1 : dl.length()));
        if (dl != null && dl.length() > 0) {
            JSONObject it = dl.getJSONObject(0);
            log("  name=" + it.optString("vod_name"));
            log("  pic=" + it.optString("vod_pic"));
            log("  year=" + it.optString("vod_year") + " area=" + it.optString("vod_area") + " type=" + it.optString("type_name"));
            log("  actor=" + cut(it.optString("vod_actor"), 60));
            log("  content=" + cut(it.optString("vod_content"), 80));
            log("  from=" + it.optString("vod_play_from"));
            log("  url=" + cut(it.optString("vod_play_url"), 200));
        }

        log("PLAY=" + cut(c.playerContent("厂长资源", "/v_play/bXZfMjAyOTQtbm1fMQ==.html", new ArrayList<String>()), 300));

        JSONObject s = new JSONObject(c.searchContent("峡谷", false, "1"));
        JSONArray sl = s.optJSONArray("list");
        log("SEARCH list=" + (sl == null ? -1 : sl.length()));
        if (sl != null) for (int i = 0; i < sl.length() && i < 5; i++) log("  " + sl.getJSONObject(i));

        OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream("C:\\tmp\\czzy_test.txt"), "UTF-8");
        w.write(sb.toString());
        w.close();
        System.out.println("done");
    }
}
