import com.github.catvod.spider.Appmovie;
import org.json.*;
import java.util.*;
public class TestAppmovie {
    static String cut(String s){return s==null?"null":(s.length()>220?s.substring(0,220)+"...":s);}
    public static void main(String[] a) throws Exception {
        Appmovie s = new Appmovie();
        System.out.println("HOME : " + cut(s.homeContent(true)));
        System.out.println("CAT  : " + cut(s.categoryContent("2", "1", false, new HashMap<String,String>())));
        System.out.println("SEARCH: " + cut(s.searchContent("我想留在你身边", false, "1")));
        JSONObject d = new JSONObject(s.detailContent(Arrays.asList("600017")));
        JSONObject it = d.getJSONArray("list").getJSONObject(0);
        System.out.println("NAME : " + it.optString("vod_name") + " | " + it.optString("vod_year") + " | " + it.optString("vod_area"));
        System.out.println("FROM : " + it.optString("vod_play_from"));
        System.out.println("URL  : " + cut(it.optString("vod_play_url")));
        System.out.println("PLAY : " + cut(s.playerContent("线路", "600017_1_1", new ArrayList<String>())));
    }
}
