package com.github.catvod.spider;

import com.github.catvod.crawler.Spider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SOTVLA（sotvla.cc）全网影视聚合搜索。
 * <p>
 * 首页分类来自热榜接口，热榜只有片名和海报，详情通过片名再搜索一次获得。
 * 该站自身不存片源，播放页由第三方解析服务处理，因此播放返回 parse=1。
 */
public class Sotvla extends Spider {

    static final String DEFAULT_HOST = "https://www.sotvla.cc";
    private String host = DEFAULT_HOST;

    @Override
    public void init(String extend) {
        if (extend == null) return;
        String ext = extend.trim();
        if (ext.isEmpty()) return;
        try {
            if (ext.startsWith("http")) {
                host = trim(ext);
            } else if (ext.startsWith("{")) {
                JSONObject object = new JSONObject(ext);
                if (object.has("host")) host = trim(object.optString("host"));
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public String homeContent(boolean filter) {
        try {
            JSONObject root = new JSONObject();
            JSONArray classes = new JSONArray();
            classes.put(cls("teleplay", "电视剧"));
            classes.put(cls("movie", "电影"));
            classes.put(cls("anime", "动漫"));
            classes.put(cls("variety", "综艺"));
            root.put("class", classes);
            root.put("list", hot("movie"));
            return root.toString();
        } catch (Throwable e) {
            return error("首页加载失败");
        }
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, java.util.HashMap<String, String> extend) {
        try {
            JSONArray list = hot(tid);
            JSONObject root = new JSONObject();
            root.put("page", 1);
            root.put("pagecount", 1);
            root.put("limit", list.length());
            root.put("total", list.length());
            root.put("list", list);
            return root.toString();
        } catch (Throwable e) {
            return error("列表加载失败");
        }
    }

    @Override
    public String searchContent(String key, boolean quick) {
        return searchContent(key, quick, "1");
    }

    @Override
    public String searchContent(String key, boolean quick, String pg) {
        try {
            List<JSONObject> items = parseSearch(Http.get(host + "/search.php?q=" + URLEncoder.encode(key == null ? "" : key, "UTF-8")));
            JSONObject root = new JSONObject();
            root.put("page", 1);
            root.put("pagecount", 1);
            root.put("limit", items.size());
            root.put("total", items.size());
            JSONArray list = new JSONArray();
            for (JSONObject item : items) list.put(item);
            root.put("list", list);
            return root.toString();
        } catch (Throwable e) {
            return error("搜索失败");
        }
    }

    @Override
    public String detailContent(List<String> ids) {
        try {
            if (ids == null || ids.isEmpty()) return error("参数错误");
            String id = ids.get(0).trim();
            if (id.startsWith("s:")) {
                // 热榜只有片名，先搜索拿到 api_id / vod_id
                String title = id.substring(2);
                List<JSONObject> items = parseSearch(Http.get(host + "/search.php?q=" + URLEncoder.encode(title, "UTF-8")));
                if (items.isEmpty()) return error("未找到资源");
                JSONObject match = items.get(0);
                for (JSONObject item : items) {
                    if (item.optString("vod_name").equals(title)) {
                        match = item;
                        break;
                    }
                }
                String[] parts = match.optString("vod_id").split("_");
                if (parts.length < 2) return error("资源ID异常");
                return buildDetail(parts[0], parts[1]);
            }
            String[] parts = id.split("_");
            if (parts.length < 2) return error("参数错误");
            return buildDetail(parts[0], parts[1]);
        } catch (Throwable e) {
            return error("详情加载失败");
        }
    }

    private String buildDetail(String apiId, String vodId) throws Exception {
        String html = Http.get(host + "/detail.php?api_id=" + apiId + "&vod_id=" + vodId);

        String title = clean(one(html, "<h1>(.*?)</h1>"));
        String pic = one(html, "<meta property=\"og:image\" content=\"([^\"]+)\"");
        if (pic.isEmpty()) pic = one(html, "class=\"sotv-poster-img\"[^>]*src=\"([^\"]+)\"");
        String blurb = clean(one(html, "<p class=\"blurb\">(.*?)</p>"));
        String type = clean(one(html, "<strong>分类</strong>(.*?)</div>"));
        String year = one(html, "<strong>年代</strong>\\s*(\\d{4})");
        String area = clean(one(html, "<strong>地区</strong>(.*?)</div>"));
        String actor = clean(one(html, "<strong>演员</strong>(.*?)</div>"));
        String director = clean(one(html, "<strong>导演</strong>(.*?)</div>"));

        Map<String, String> sourceNames = new LinkedHashMap<>();
        Matcher tabs = Pattern.compile("<button[^>]+data-src=\"(\\d+)\"[^>]*>(.*?)</button>", Pattern.DOTALL).matcher(html);
        while (tabs.find()) sourceNames.put(tabs.group(1), clean(tabs.group(2)));

        Map<String, List<String[]>> panels = new LinkedHashMap<>();
        Matcher panel = Pattern.compile("<div class=\"play-ep-panel\" data-src-panel=\"(\\d+)\"[^>]*>(.*?)</div>\\s*</div>", Pattern.DOTALL).matcher(html);
        while (panel.find()) {
            String src = panel.group(1);
            List<String[]> episodes = new ArrayList<>();
            Matcher links = Pattern.compile("<a[^>]+href=\"(/play\\.php\\?api_id=\\d+&amp;vod_id=\\d+&amp;src=\\d+&amp;ep=\\d+)\"[^>]*>(.*?)</a>", Pattern.DOTALL).matcher(panel.group(2));
            while (links.find()) {
                String href = links.group(1).replace("&amp;", "&");
                String ep = one(href, "ep=(\\d+)");
                String name = clean(links.group(2));
                if (name.isEmpty()) name = "第" + (ep.isEmpty() ? "1" : String.valueOf(Integer.parseInt(ep) + 1)) + "集";
                episodes.add(new String[]{name, apiId + "_" + vodId + "_" + src + "_" + ep});
            }
            if (!episodes.isEmpty()) panels.put(src, episodes);
        }
        if (panels.isEmpty()) return error("暂无播放源");

        StringBuilder from = new StringBuilder();
        StringBuilder urls = new StringBuilder();
        for (Map.Entry<String, List<String[]>> entry : panels.entrySet()) {
            String name = sourceNames.containsKey(entry.getKey()) ? sourceNames.get(entry.getKey()) : "线路" + entry.getKey();
            StringBuilder line = new StringBuilder();
            for (String[] episode : entry.getValue()) {
                if (line.length() > 0) line.append('#');
                line.append(episode[0]).append('$').append(episode[1]);
            }
            if (from.length() > 0) from.append("$$$");
            from.append(name);
            if (urls.length() > 0) urls.append("$$$");
            urls.append(line);
        }

        JSONObject item = new JSONObject();
        item.put("vod_id", apiId + "_" + vodId);
        item.put("vod_name", title);
        item.put("vod_pic", pic);
        item.put("type_name", type);
        item.put("vod_year", year);
        item.put("vod_area", area.replace("年代", "").replace("地区", "").trim());
        item.put("vod_actor", actor);
        item.put("vod_director", director);
        item.put("vod_content", blurb);
        item.put("vod_play_from", from.toString());
        item.put("vod_play_url", urls.toString());
        JSONArray list = new JSONArray();
        list.put(item);
        JSONObject root = new JSONObject();
        root.put("list", list);
        return root.toString();
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        try {
            String[] parts = id == null ? new String[0] : id.split("_");
            if (parts.length < 4) return error("播放参数错误");
            String url = host + "/play.php?api_id=" + parts[0] + "&vod_id=" + parts[1] + "&src=" + parts[2] + "&ep=" + parts[3];
            String html = Http.get(url);
            String iframe = one(html, "<iframe[^>]+src=\"([^\"]+)\"");
            if (iframe.isEmpty()) return error("未找到播放地址");
            iframe = iframe.replace("&amp;", "&");
            JSONObject root = new JSONObject();
            root.put("parse", 1);
            root.put("url", iframe);
            return root.toString();
        } catch (Throwable e) {
            return error("播放失败");
        }
    }

    private JSONArray hot(String tab) throws Exception {
        String body = Http.get(host + "/api/hot_movie.php?source=baidu&tab=" + URLEncoder.encode(tab == null ? "movie" : tab, "UTF-8"));
        JSONObject data = new JSONObject(body);
        JSONArray source = data.optJSONArray("items");
        JSONArray list = new JSONArray();
        if (source == null) return list;
        for (int i = 0; i < source.length(); i++) {
            JSONObject item = source.optJSONObject(i);
            if (item == null) continue;
            String title = item.optString("title");
            if (title.isEmpty()) continue;
            JSONObject vod = new JSONObject();
            vod.put("vod_id", "s:" + title);
            vod.put("vod_name", title);
            vod.put("vod_pic", item.optString("pic"));
            vod.put("vod_remarks", "热播");
            list.put(vod);
        }
        return list;
    }

    private List<JSONObject> parseSearch(String html) throws Exception {
        List<JSONObject> items = new ArrayList<>();
        Matcher matcher = Pattern.compile("<article class=\"search-result-item\">(.*?)</article>", Pattern.DOTALL).matcher(html);
        while (matcher.find()) {
            String block = matcher.group(1);
            String apiId = one(block, "/detail\\.php\\?api_id=(\\d+)");
            String vodId = one(block, "/detail\\.php\\?api_id=\\d+&amp;vod_id=(\\d+)");
            if (vodId.isEmpty()) vodId = one(block, "/detail\\.php\\?api_id=\\d+&vod_id=(\\d+)");
            String title = clean(one(block, "class=\"sr-title\"[^>]*>(.*?)</a>"));
            if (apiId.isEmpty() || vodId.isEmpty() || title.isEmpty()) continue;
            String pic = one(block, "class=\"sotv-poster-img\"[^>]*src=\"([^\"]+)\"");
            String year = one(block, "上映时间：</span><span class=\"sr-value\">(\\d{4})");
            String area = one(block, "地区：</span><span class=\"sr-value\">(.*?)</span>");
            String actors = one(block, "class=\"sr-actors\"[^>]*title=\"([^\"]*)\"");
            JSONObject item = new JSONObject();
            item.put("vod_id", apiId + "_" + vodId);
            item.put("vod_name", title);
            item.put("vod_pic", pic);
            item.put("vod_year", year);
            item.put("vod_area", clean(area));
            item.put("vod_actor", clean(actors));
            items.add(item);
        }
        return items;
    }

    private static JSONObject cls(String id, String name) {
        try {
            JSONObject object = new JSONObject();
            object.put("type_id", id);
            object.put("type_name", name);
            return object;
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static String one(String text, String regex) {
        try {
            Matcher matcher = Pattern.compile(regex, Pattern.DOTALL).matcher(text);
            if (matcher.find()) return matcher.group(1);
        } catch (Throwable ignored) {
        }
        return "";
    }

    static String clean(String text) {
        if (text == null) return "";
        String value = text.replaceAll("<[^>]+>", "");
        value = value.replace("&nbsp;", " ").replace("&amp;", "&").replace("&quot;", "\"")
                .replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&ldquo;", "“")
                .replace("&rdquo;", "”").replace("&middot;", "·").replace("&hellip;", "…");
        return value.replaceAll("\\s+", " ").trim();
    }

    private static String trim(String value) {
        String text = value == null ? "" : value.trim();
        while (text.endsWith("/")) text = text.substring(0, text.length() - 1);
        return text.isEmpty() ? DEFAULT_HOST : text;
    }

    private static String error(String message) {
        try {
            JSONObject root = new JSONObject();
            root.put("list", new JSONArray());
            root.put("msg", message);
            return root.toString();
        } catch (Exception e) {
            return "{\"list\":[]}";
        }
    }
}
