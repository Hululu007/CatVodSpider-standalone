import com.github.catvod.spider.MacAgg;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;

public class TestMacAgg {

    static String cut(String s, int n) {
        if (s == null) return "null";
        return s.length() > n ? s.substring(0, n) + " ...(" + s.length() + ")" : s;
    }

    public static void main(String[] args) throws Exception {
        MacAgg s = new MacAgg();
        s.init(args.length > 0 ? args[0] : "{}");

        long t0 = System.currentTimeMillis();
        s.homeContent(true);
        System.out.println("[HOME] " + (System.currentTimeMillis() - t0) + "ms");

        t0 = System.currentTimeMillis();
        s.categoryContent("38", "1", true, new HashMap<String, String>());
        System.out.println("[CAT-国产动漫] " + (System.currentTimeMillis() - t0) + "ms");

        t0 = System.currentTimeMillis();
        s.searchContent("十三邀", true, "1");
        System.out.println("[SEARCH quick] " + (System.currentTimeMillis() - t0) + "ms");

        t0 = System.currentTimeMillis();
        String search = s.searchContent("十三邀", false, "1");
        System.out.println("[SEARCH full] " + (System.currentTimeMillis() - t0) + "ms  total="
                + new JSONObject(search).optInt("total"));

        for (String id : new String[]{"agg_十三邀第九季", "agg_仙逆", "agg_庆余年"}) {
            t0 = System.currentTimeMillis();
            String detail = s.detailContent(Arrays.asList(id));
            long cost = System.currentTimeMillis() - t0;
            JSONObject root = new JSONObject(detail);
            JSONObject vod = root.getJSONArray("list").getJSONObject(0);
            String from = vod.optString("vod_play_from");
            String urls = vod.optString("vod_play_url");
            String[] lines = from.split("\\$\\$\\$");
            String[] groups = urls.split("\\$\\$\\$");
            System.out.println();
            System.out.println("[DETAIL " + id + "] " + cost + "ms  线路数=" + lines.length + " 分组=" + groups.length);
            for (int i = 0; i < lines.length; i++) {
                String g = i < groups.length ? groups[i] : "";
                int eps = g.isEmpty() ? 0 : g.split("#").length;
                String first = "";
                if (!g.isEmpty()) {
                    String e0 = g.split("#")[0];
                    int d = e0.indexOf('$');
                    first = d > 0 ? e0.substring(d + 1) : e0;
                }
                System.out.println("   " + (i + 1) + ") " + lines[i] + "  集=" + eps + "  " + cut(first, 100));
            }
            // 逐条线路测播放解析
            for (int i = 0; i < Math.min(lines.length, groups.length); i++) {
                String g = groups[i];
                if (g.isEmpty()) continue;
                String e0 = g.split("#")[0];
                int d = e0.indexOf('$');
                String firstUrl = d > 0 ? e0.substring(d + 1) : e0;
                long p0 = System.currentTimeMillis();
                String play = s.playerContent(lines[i], firstUrl, new ArrayList<String>());
                long pcost = System.currentTimeMillis() - p0;
                JSONObject pj = new JSONObject(play);
                String finalUrl = pj.optString("url");
                String err = pj.optString("msg");
                System.out.println("      播放 " + lines[i] + " " + pcost + "ms -> "
                        + (finalUrl.isEmpty() ? ("ERR " + err) : cut(finalUrl, 95)));
            }
        }
    }
}
