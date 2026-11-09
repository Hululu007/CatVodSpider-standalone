import com.github.catvod.spider.Qinghuaci;
import java.util.*;

public class TestAgg {
    public static void main(String[] args) throws Exception {
        Qinghuaci q = new Qinghuaci();
        String detail = q.detailContent(Arrays.asList("147990"));
        System.out.println(detail);
        int i = detail.indexOf("cb:");
        if (i > 0) {
            String tail = detail.substring(i);
            String vid = tail.substring(3, Math.min(tail.length(), 3 + 13));
            System.out.println("AGG VID: " + vid);
            System.out.println("AGG PLAY: " + q.playerContent("茶杯狐", "cb:" + vid, new ArrayList<String>()));
        }
    }
}
