import com.github.catvod.spider.*;
import org.json.*;
import java.util.*;

public class TestPpnix {
    static String cut(String s) { return s == null ? "null" : (s.length() > 400 ? s.substring(0, 400) + "..." : s); }

    public static void main(String[] a) throws Exception {
        Ppnix p = new Ppnix();
        JSONObject home = new JSONObject(p.homeContent(true));
        System.out.println("PP-CLASSES: " + home.optJSONArray("class"));
        JSONArray hl = home.optJSONArray("list");
        System.out.println("PP-HOME  : count=" + (hl == null ? -1 : hl.length()));
        if (hl != null && hl.length() > 0) System.out.println("PP-HOME[0]: " + hl.getJSONObject(0));

        System.out.println("PP-CAT   : " + cut(p.categoryContent("2", "1", false, new HashMap<String, String>())));
        System.out.println("PP-CAT2  : " + cut(p.categoryContent("1", "2", false, new HashMap<String, String>())));

        System.out.println("PP-SRCH  : " + cut(p.searchContent("流浪地球", false, "1")));

        JSONObject d = new JSONObject(p.detailContent(Arrays.asList("1_9089")));
        JSONObject it = d.getJSONArray("list").getJSONObject(0);
        System.out.println("PP-NAME  : " + it.optString("vod_name"));
        System.out.println("PP-PIC   : " + it.optString("vod_pic"));
        System.out.println("PP-YEAR  : " + it.optString("vod_year"));
        System.out.println("PP-TYPE  : " + it.optString("type_name"));
        System.out.println("PP-AREA  : " + it.optString("vod_area"));
        System.out.println("PP-DESC  : " + cut(it.optString("vod_content")));
        System.out.println("PP-FROM  : " + it.optString("vod_play_from"));
        System.out.println("PP-URL   : " + cut(it.optString("vod_play_url")));
        System.out.println("PP-PLAY  : " + cut(p.playerContent("PPnix", "1_9089_1080P", new ArrayList<String>())));

        JSONObject dt = new JSONObject(p.detailContent(Arrays.asList("2_9083")));
        JSONObject itt = dt.getJSONArray("list").getJSONObject(0);
        System.out.println("PP-TVNAME: " + itt.optString("vod_name"));
        System.out.println("PP-TVURL : " + cut(itt.optString("vod_play_url")));
    }
}
