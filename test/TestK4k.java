import com.github.catvod.spider.Kfourkvm;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;

public class TestK4k {

    static String cut(String s) {
        if (s == null) return "null";
        return s.length() > 700 ? s.substring(0, 700) + " ...(" + s.length() + ")" : s;
    }

    public static void main(String[] args) throws Exception {
        Kfourkvm s = new Kfourkvm();
        s.init(args.length > 0 ? args[0] : "{\"quality\":\"auto\"}");

        System.out.println("HOME   : " + cut(s.homeContent(true)));

        HashMap<String, String> ext = new HashMap<String, String>();
        ext.put("area", "美国");
        System.out.println("CAT    : " + cut(s.categoryContent("1", "1", true, ext)));

        System.out.println("SEARCH : " + cut(s.searchContent("沉香", false, "1")));

        String detail = s.detailContent(Arrays.asList("ch4o3fsbe"));
        System.out.println("DETAIL : " + cut(detail));

        String play = s.playerContent("4K", "ch4o3fsbe|40372", new ArrayList<String>());
        System.out.println("PLAY   : " + play);
    }
}
