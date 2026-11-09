import com.github.catvod.spider.*;
import org.json.*;
import java.util.*;
public class TestNew {
    static String cut(String s){return s==null?"null":(s.length()>200?s.substring(0,200)+"...":s);}
    public static void main(String[] a) throws Exception {
        Appmovie am = new Appmovie();
        JSONObject home = new JSONObject(am.homeContent(true));
        System.out.println("AM-FILTERS: " + home.optJSONObject("filters").keySet());
        HashMap<String,String> ext = new HashMap<>();
        ext.put("class", "爱情"); ext.put("area", "中国大陆");
        System.out.println("AM-CAT: " + cut(am.categoryContent("1", "1", true, ext)));

        Xifanys xf = new Xifanys();
        System.out.println("XF-HOME: " + cut(xf.homeContent(true)));
        System.out.println("XF-CAT : " + cut(xf.categoryContent("2", "1", false, new HashMap<String,String>())));
        System.out.println("XF-SRCH: " + cut(xf.searchContent("我想留在你身边", false, "1")));
        JSONObject d = new JSONObject(xf.detailContent(Arrays.asList("215278")));
        JSONObject it = d.getJSONArray("list").getJSONObject(0);
        System.out.println("XF-NAME: " + it.optString("vod_name"));
        System.out.println("XF-FROM: " + it.optString("vod_play_from"));
        System.out.println("XF-URL : " + cut(it.optString("vod_play_url")));
        System.out.println("XF-PLAY: " + cut(xf.playerContent("qq", "215278_1_1", new ArrayList<String>())));
    }
}
