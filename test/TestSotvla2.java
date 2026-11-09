import com.github.catvod.spider.Sotvla;
import org.json.*;
import java.util.*;
public class TestSotvla2 {
    public static void main(String[] a) throws Exception {
        Sotvla s = new Sotvla();
        JSONObject o = new JSONObject(s.detailContent(Arrays.asList("1_78507")));
        JSONObject it = o.getJSONArray("list").getJSONObject(0);
        System.out.println("FROM: " + it.optString("vod_play_from"));
        System.out.println("URL : " + it.optString("vod_play_url"));
    }
}
