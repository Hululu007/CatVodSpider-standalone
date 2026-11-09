import com.github.catvod.spider.Chabeihu;
import com.github.catvod.spider.Qinghuaci;
import java.util.*;

public class TestChabeihu {
    static String cut(String s) { return s == null ? "null" : (s.length() > 260 ? s.substring(0, 260) + "..." : s); }
    public static void main(String[] args) throws Exception {
        Chabeihu c = new Chabeihu();
        System.out.println("CB-HOME : " + cut(c.homeContent(true)));
        System.out.println("CB-CAT  : " + cut(c.categoryContent("3", "1", false, new HashMap<String,String>())));
        System.out.println("CB-SEARCH: " + cut(c.searchContent("我想留在你身边", false, "1")));
        System.out.println("CB-DETAIL: " + cut(c.detailContent(Arrays.asList("271068"))));
        System.out.println("CB-PLAY : " + cut(c.playerContent("茶杯狐", "271068-1-1", new ArrayList<String>())));
    }
}
