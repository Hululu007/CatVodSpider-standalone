import com.github.catvod.spider.Kfourkvm;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;

public class TestPerf {

    static long t;

    static void mark(String label) {
        long now = System.currentTimeMillis();
        System.out.println(String.format("%-28s %5d ms", label, now - t));
        t = now;
    }

    static String head(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", "Mozilla/5.0");
        long t0 = System.currentTimeMillis();
        int code = c.getResponseCode();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        InputStream in = c.getInputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
            if (out.size() > 512 * 1024) break;
        }
        in.close();
        long cost = System.currentTimeMillis() - t0;
        return code + "  " + cost + "ms  " + out.size() + "B";
    }

    public static void main(String[] args) throws Exception {
        Kfourkvm s = new Kfourkvm();
        s.init("{\"quality\":\"auto\"}");

        t = System.currentTimeMillis();
        String search = s.searchContent("凡人修仙传", false, "1");
        mark("[SEARCH]");
        JSONObject sr = new JSONObject(search);
        System.out.println("   结果数=" + sr.optJSONArray("list").length());
        String vodId = "";
        for (int i = 0; i < sr.optJSONArray("list").length(); i++) {
            JSONObject v = sr.optJSONArray("list").getJSONObject(i);
            if (v.optString("vod_name").contains("凡人修仙传")) {
                vodId = v.optString("vod_id");
                break;
            }
        }
        System.out.println("   选中=" + vodId);

        t = System.currentTimeMillis();
        String detail = s.detailContent(Arrays.asList(vodId));
        mark("[DETAIL 第1次]");
        JSONObject dr = new JSONObject(detail).getJSONArray("list").getJSONObject(0);
        String playUrl = dr.optString("vod_play_url");
        String[] eps = playUrl.split("#");
        System.out.println("   集数=" + eps.length + "  首集=" + eps[0]);

        String epId = eps[0].substring(eps[0].indexOf('$') + 1);
        System.out.println("   epId=" + epId);

        t = System.currentTimeMillis();
        String play1 = s.playerContent("4K", epId, new ArrayList<String>());
        mark("[PLAY 第1次]");
        JSONObject pr = new JSONObject(play1);
        String m3u8 = pr.optString("url");
        System.out.println("   m3u8=" + m3u8);

        t = System.currentTimeMillis();
        String play2 = s.playerContent("4K", epId, new ArrayList<String>());
        mark("[PLAY 第2次(缓存?)");
        System.out.println("   相同=" + play2.contains(m3u8));

        t = System.currentTimeMillis();
        String detail2 = s.detailContent(Arrays.asList(vodId));
        mark("[DETAIL 第2次]");

        System.out.println();
        if (!m3u8.isEmpty()) {
            System.out.println("m3u8 拉取      : " + head(m3u8));
            // 拉子 playlist 和首分片
            HttpURLConnection c = (HttpURLConnection) new URL(m3u8).openConnection();
            c.setRequestProperty("User-Agent", "Mozilla/5.0");
            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            in.close();
            String body = out.toString("UTF-8");
            System.out.println("m3u8 内容:");
            System.out.println(body);
            String last = "";
            for (String line : body.split("\n")) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                last = line;
            }
            if (!last.isEmpty()) {
                String seg = last.startsWith("http") ? last
                        : (m3u8.substring(0, m3u8.lastIndexOf('/') + 1) + last);
                System.out.println("首分片地址     : " + seg);
                System.out.println("首分片下载     : " + head(seg));
            }
        }
    }
}
