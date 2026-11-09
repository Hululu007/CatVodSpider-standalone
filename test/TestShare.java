import com.github.catvod.spider.MacAgg;

import org.json.JSONObject;

import java.util.ArrayList;

public class TestShare {

    public static void main(String[] args) throws Exception {
        MacAgg s = new MacAgg();
        s.init("{}");
        String[] cases = {
                "https://v.gsuus.com/play/bo2V1QBa",
                "https://vip.dytt-cinema.com/share/167b07d756acd5b288f3b8ad0e06673f",
                "https://vip.ffzy-online3.com/share/59a487d990cb3421411d85f2e98fde24",
                "https://v.lzcdn28.com/share/f35fd567065af297ae65b621e0a21ae9",
                "https://vod1.maowushi.com/20260128/ZPyutGwC/index.m3u8"
        };
        for (String url : cases) {
            long t0 = System.currentTimeMillis();
            String r = s.playerContent("聚合", url, new ArrayList<String>());
            long cost = System.currentTimeMillis() - t0;
            JSONObject o = new JSONObject(r);
            String u = o.optString("url");
            System.out.println(cost + "ms  " + (u.isEmpty() ? ("ERR " + o.optString("msg")) : u));
        }
    }
}
