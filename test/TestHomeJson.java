import com.github.catvod.spider.*;
import org.json.*;

public class TestHomeJson {
    public static void main(String[] a) throws Exception {
        Qinghuaci q = new Qinghuaci();
        JSONObject o = new JSONObject(q.homeContent(true));
        System.out.println("classes=" + o.optJSONArray("class").length());
        System.out.println("list=" + o.optJSONArray("list").length());
        System.out.println("banners=" + o.optJSONArray("banners").length());
        JSONArray sections = o.optJSONArray("sections");
        System.out.println("sections=" + sections.length());
        for (int i = 0; i < sections.length(); i++) {
            JSONObject s = sections.getJSONObject(i);
            System.out.println("  " + s.optString("title") + " -> " + s.optJSONArray("list").length());
        }
        if (o.optJSONArray("banners").length() > 0) {
            System.out.println("banner0=" + o.optJSONArray("banners").getJSONObject(0));
        }
    }
}
