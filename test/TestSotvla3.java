import com.github.catvod.spider.Sotvla;
import org.json.*;
import java.util.*;
public class TestSotvla3 {
    static String cut(String s){return s==null?"null":(s.length()>240?s.substring(0,240)+"...":s);}
    public static void main(String[] a) throws Exception {
        Sotvla s = new Sotvla();
        System.out.println("HOME  : " + cut(s.homeContent(true)));
        System.out.println("CAT   : " + cut(s.categoryContent("teleplay", "1", false, new HashMap<String,String>())));
        System.out.println("SEARCH: " + cut(s.searchContent("我想留在你身边", false, "1")));
        JSONObject d = new JSONObject(s.detailContent(Arrays.asList("s:我想留在你身边")));
        JSONObject it = d.getJSONArray("list").getJSONObject(0);
        System.out.println("D-NAME: " + it.optString("vod_name"));
        System.out.println("D-FROM: " + it.optString("vod_play_from"));
        System.out.println("D-URL : " + cut(it.optString("vod_play_url")));
    }
}
