import com.github.catvod.spider.*;
import org.json.*;
import java.util.*;
public class TestDetail {
  public static void main(String[] a) throws Exception {
    Qinghuaci q = new Qinghuaci();
    q.init("{\"aggregate\":false}");
    long t = System.currentTimeMillis();
    JSONObject d = new JSONObject(q.detailContent(Arrays.asList("146691")));
    System.out.println("detail ms=" + (System.currentTimeMillis()-t));
    JSONObject item = d.getJSONArray("list").getJSONObject(0);
    System.out.println("from=" + item.optString("vod_play_from"));
    long t2 = System.currentTimeMillis();
    JSONObject act = new JSONObject(q.action("cb:" + item.optString("vod_name")));
    System.out.println("action ms=" + (System.currentTimeMillis()-t2) + " line=" + act.optString("line").length());
  }
}
