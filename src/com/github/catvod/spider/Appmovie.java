package com.github.catvod.spider;

import com.github.catvod.crawler.Spider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * APP影院（appmovie.art），苹果CMS（MacCMS）站点。
 * 播放页 player_data.url 就是直链 m3u8。
 */
public class Appmovie extends Spider {

    static final String DEFAULT_HOST = "https://www.appmovie.art";
    private static final int PAGE_SIZE = 30;
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
            classes.put(cls("1", "电影"));
            classes.put(cls("2", "剧集"));
            classes.put(cls("3", "综艺"));
            classes.put(cls("4", "动漫"));
            root.put("class", classes);
            root.put("list", cards(Http.get(host + "/index.php/vod/type/id/1.html"), PAGE_SIZE));
            JSONObject filters = new JSONObject();
            for (String id : new String[]{"1", "2", "3", "4"}) filters.put(id, filterArray());
            root.put("filters", filters);
            return root.toString();
        } catch (Throwable e) {
            return error("首页加载失败");
        }
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, java.util.HashMap<String, String> extend) {
        try {
            int page = parseInt(pg, 1);
            StringBuilder url = new StringBuilder(host).append("/index.php/vod/type/id/").append(tid);
            if (page > 1) url.append("/page/").append(page);
            url.append(".html");
            StringBuilder query = new StringBuilder();
            if (extend != null) {
                append(query, "class", extend.get("class"));
                append(query, "area", extend.get("area"));
                append(query, "year", extend.get("year"));
            }
            if (query.length() > 0) url.append('?').append(query);
            JSONArray list = cards(Http.get(url.toString()), PAGE_SIZE);
            JSONObject root = new JSONObject();
            root.put("page", page);
            root.put("pagecount", list.length() == 0 ? page : page + 1);
            root.put("limit", list.length());
            root.put("total", 9999);
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
            String url = host + "/index.php/vod/search.html?wd=" + URLEncoder.encode(key == null ? "" : key, "UTF-8");
            JSONArray list = cards(Http.get(url), 90);
            JSONObject root = new JSONObject();
            root.put("page", 1);
            root.put("pagecount", 1);
            root.put("limit", list.length());
            root.put("total", list.length());
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
            String id = ids.get(0).replaceAll("\\D", "");
            String html = Http.get(host + "/index.php/vod/detail/id/" + id + ".html");

            String name = clean(one(html, "<h3 class=\"title\">\\s*(.*?)\\s*</h3>"));
            if (name.isEmpty()) name = clean(String.valueOf(one(html, "<title>(.*?)</title>")).split("详情")[0]);
            String pic = one(html, "class=\"stui-content__thumb[^\"]*\">.*?data-original=\"([^\"]+)\"");
            if (pic.isEmpty()) pic = one(html, "data-original=\"([^\"]+)\"");
            String type = clean(one(html, "类型：</span><a[^>]*>(.*?)</a>"));
            String area = clean(one(html, "地区：</span><a[^>]*>(.*?)</a>"));
            String year = one(html, "年份：</span><a[^>]*>(\\d{4})</a>");
            String actor = clean(one(html, "<span>主演：</span>(.*?)</p>"));
            String director = clean(one(html, "<span>导演：</span>(.*?)</p>"));
            String desc = clean(one(html, "<p class=\"desc[^\"]*\">(.*?)</p>"));
            desc = desc.replace("简介：", "").replace("详细 >", "").trim();

            List<String[]> sources = playSources(html);
            if (sources.isEmpty()) return error("暂无播放源");

            StringBuilder from = new StringBuilder();
            StringBuilder urls = new StringBuilder();
            for (String[] source : sources) {
                if (from.length() > 0) from.append("$$$");
                from.append(source[0]);
                if (urls.length() > 0) urls.append("$$$");
                urls.append(source[1]);
            }

            JSONObject item = new JSONObject();
            item.put("vod_id", id);
            item.put("vod_name", name);
            item.put("vod_pic", pic);
            item.put("type_name", type);
            item.put("vod_year", year);
            item.put("vod_area", area);
            item.put("vod_actor", actor);
            item.put("vod_director", director);
            item.put("vod_content", desc);
            item.put("vod_play_from", from.toString());
            item.put("vod_play_url", urls.toString());
            JSONArray list = new JSONArray();
            list.put(item);
            JSONObject root = new JSONObject();
            root.put("list", list);
            return root.toString();
        } catch (Throwable e) {
            return error("详情加载失败");
        }
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        try {
            String[] parts = id == null ? new String[0] : id.split("_");
            if (parts.length < 3) return error("播放参数错误");
            String url = host + "/index.php/vod/play/id/" + parts[0] + "/sid/" + parts[1] + "/nid/" + parts[2] + ".html";
            String html = Http.get(url);
            String json = one(html, "var player_data=(\\{.*?\\})</script>");
            if (json.isEmpty()) return error("未找到播放数据");
            JSONObject data = new JSONObject(json);
            String playUrl = data.optString("url");
            if (playUrl.isEmpty() || !playUrl.startsWith("http")) return error("播放地址为空");
            JSONObject root = new JSONObject();
            root.put("parse", 0);
            root.put("url", playUrl);
            return root.toString();
        } catch (Throwable e) {
            return error("播放失败");
        }
    }

    /**
     * 详情页按「线路」分块，每块一个 <ul class="stui-content__playlist">。
     * 返回 {线路名, 集名$id_sid_nid#...} 列表。
     */
    private List<String[]> playSources(String html) {
        List<String[]> result = new ArrayList<>();
        Matcher uls = Pattern.compile("<ul class=\"stui-content__playlist[^\"]*\">(.*?)</ul>", Pattern.DOTALL).matcher(html);
        while (uls.find()) {
            String block = uls.group(1);
            String before = html.substring(0, uls.start());
            String lineName = "";
            Matcher title = Pattern.compile("<h3 class=\"title\">\\s*(.*?)\\s*</h3>", Pattern.DOTALL).matcher(before);
            while (title.find()) lineName = clean(title.group(1));
            if (lineName.isEmpty()) lineName = "线路" + (result.size() + 1);

            StringBuilder episodes = new StringBuilder();
            Matcher links = Pattern.compile("<a[^>]+href=\"/index\\.php/vod/play/id/(\\d+)/sid/(\\d+)/nid/(\\d+)\\.html\"[^>]*>(.*?)</a>", Pattern.DOTALL).matcher(block);
            while (links.find()) {
                String epName = clean(links.group(4));
                if (epName.isEmpty()) epName = "第" + links.group(3) + "集";
                if (episodes.length() > 0) episodes.append('#');
                episodes.append(epName).append('$').append(links.group(1)).append('_').append(links.group(2)).append('_').append(links.group(3));
            }
            if (episodes.length() > 0) result.add(new String[]{lineName, episodes.toString()});
        }
        return result;
    }

    private JSONArray cards(String html, int limit) throws Exception {
        JSONArray list = new JSONArray();
        Matcher matcher = Pattern.compile("<li class=\"stui-vodlist__item\">(.*?)</li>", Pattern.DOTALL).matcher(html);
        while (matcher.find() && list.length() < limit) {
            String block = matcher.group(1);
            String id = one(block, "/index\\.php/vod/detail/id/(\\d+)\\.html");
            String name = clean(one(block, "title=\"([^\"]+)\""));
            if (id.isEmpty() || name.isEmpty()) continue;
            String pic = one(block, "data-original=\"([^\"]+)\"");
            String remark = clean(one(block, "class=\"pic-text text-right\">(.*?)</span>"));
            JSONObject item = new JSONObject();
            item.put("vod_id", id);
            item.put("vod_name", name);
            item.put("vod_pic", pic);
            item.put("vod_remarks", remark);
            list.put(item);
        }
        return list;
    }

    private JSONArray filterArray() throws Exception {
        JSONArray array = new JSONArray();
        array.put(filter("class", "类型", new String[]{"动作", "喜剧", "爱情", "科幻", "恐怖", "剧情", "战争", "动画", "悬疑", "惊悚", "犯罪", "冒险", "奇幻", "武侠", "古装", "家庭", "其他"}));
        array.put(filter("area", "地区", new String[]{"中国大陆", "中国香港", "中国台湾", "美国", "日本", "韩国", "英国", "法国", "印度", "泰国", "其他"}));
        String[] years = new String[12];
        for (int i = 0; i < years.length; i++) years[i] = String.valueOf(2026 - i);
        array.put(filter("year", "年份", years));
        return array;
    }

    private JSONObject filter(String key, String name, String[] values) throws Exception {
        JSONObject filter = new JSONObject();
        filter.put("key", key);
        filter.put("name", name);
        JSONArray list = new JSONArray();
        JSONObject all = new JSONObject();
        all.put("n", "全部");
        all.put("v", "");
        list.put(all);
        for (String value : values) {
            JSONObject item = new JSONObject();
            item.put("n", value);
            item.put("v", value);
            list.put(item);
        }
        filter.put("value", list);
        return filter;
    }

    private void append(StringBuilder query, String key, String value) {
        if (value == null || value.trim().isEmpty()) return;
        if (query.length() > 0) query.append('&');
        try {
            query.append(key).append('=').append(URLEncoder.encode(value.trim(), "UTF-8"));
        } catch (Exception ignored) {
        }
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

    private static int parseInt(String value, int def) {
        try {
            return Integer.parseInt(value);
        } catch (Exception e) {
            return def;
        }
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
