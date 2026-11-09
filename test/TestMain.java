import com.github.catvod.spider.Qinghuaci;
import java.util.*;

public class TestMain {
    static String cut(String s) { return s.length() > 320 ? s.substring(0, 320) + "..." : s; }
    public static void main(String[] args) throws Exception {
        Qinghuaci s = new Qinghuaci();
        System.out.println("HOME  : " + cut(s.homeContent(true)));
        HashMap<String,String> ext = new HashMap<>();
        ext.put("v_class", "喜剧"); ext.put("area", "中国大陆"); ext.put("sort", "3,1");
        System.out.println("CAT   : " + cut(s.categoryContent("1", "1", true, ext)));
        System.out.println("DETAIL: " + cut(s.detailContent(Arrays.asList("146691"))));
        System.out.println("SEARCH: " + cut(s.searchContent("灵境", false, "1")));
        System.out.println("PLAY  : " + cut(s.playerContent("青花瓷", "146691_1315525", new ArrayList<String>())));
    }
}
