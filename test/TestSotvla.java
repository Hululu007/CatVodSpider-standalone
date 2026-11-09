import com.github.catvod.spider.Sotvla;
import java.util.*;
public class TestSotvla {
    static String cut(String s){return s==null?"null":(s.length()>420?s.substring(0,420)+"...":s);}
    public static void main(String[] a) throws Exception {
        Sotvla s = new Sotvla();
        System.out.println("S-SEARCH: " + cut(s.searchContent("我想留在你身边", false, "1")));
        System.out.println("S-DETAIL: " + cut(s.detailContent(Arrays.asList("1_78507"))));
        System.out.println("S-PLAY  : " + cut(s.playerContent("QQ", "1_78507_0_0", new ArrayList<String>())));
    }
}
