import com.github.catvod.spider.*;
import org.json.*;
import java.io.*;
import java.util.*;

public class TestBttwoFix {
    static StringBuilder sb = new StringBuilder();
    static void log(String s) { sb.append(s).append('\n'); }

    public static void main(String[] a) throws Exception {
        Bttwo b = new Bttwo();

        JSONObject d = new JSONObject(b.detailContent(Arrays.asList("ch4j2e597")));
        JSONObject it = d.getJSONArray("list").getJSONObject(0);
        log("NAME=" + it.optString("vod_name"));
        log("FROM=" + it.optString("vod_play_from"));
        log("URLS=" + it.optString("vod_play_url"));

        String p1 = b.playerContent("线路1", "ch4j2e597|38344", new ArrayList<String>());
        log("PLAY line1=" + p1);
        String p2 = b.playerContent("方言", "ch4j2gye3|38708", new ArrayList<String>());
        log("PLAY line2=" + p2);

        JSONObject dm = new JSONObject(b.detailContent(Arrays.asList("ch4ro1k43")));
        JSONObject im = dm.getJSONArray("list").getJSONObject(0);
        log("MOVIE FROM=" + im.optString("vod_play_from"));
        log("MOVIE URL=" + im.optString("vod_play_url"));
        log("MOVIE PLAY=" + b.playerContent("线路1", "ch4ro1k43|41762", new ArrayList<String>()));

        OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream("C:\\tmp\\bttwo_fix.txt"), "UTF-8");
        w.write(sb.toString());
        w.close();
        System.out.println("done");
    }
}
