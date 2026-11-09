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
 * 茶杯狐 Cupfox（chabeihu.com.cn）。
 * 搜索、分类、详情、播放均可独立使用，也被青花瓷作为聚合线路调用。
 */
public class Chabeihu extends Spider {

    static final String DEFAULT_HOST = "https://chabeihu.com.cn";
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
            classes.put(cls("3", "电影"));
            classes.put(cls("2", "影视剧"));
            classes.put(cls("21", "短剧"));
            root.put("class", classes);
            root.put("list", toArray(cards(Http.get(host + "/t/3/"), 30)));
            return root.toString();
        } catch (Throwable e) {
            return error("首页加载失败: " + e);
        }
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, java.util.HashMap<String, String> extend) {
        try {
            String url = host + "/t/" + tid + "/page/" + pg + "/";
            List<JSONObject> items = cards(Http.get(url), 90);
            JSONObject root = new JSONObject();
            root.put("page", parseInt(pg, 1));
            root.put("pagecount", items.isEmpty() ? parseInt(pg, 1) : parseInt(pg, 1) + 1);
            root.put("limit", items.size());
            root.put("total", 9999);
            root.put("list", toArray(items));
            return root.toString();
        } catch (Throwable e) {
            return error("列表加载失败: " + e);
        }
    }

    @Override
    public String searchContent(String key, boolean quick) {
        return searchContent(key, quick, "1");
    }

    @Override
    public String searchContent(String key, boolean quick, String pg) {
        try {
            String url = host + "/u--/?wd=" + URLEncoder.encode(key == null ? "" : key, "UTF-8");
            List<JSONObject> items = cards(Http.get(url), 90);
            JSONObject root = new JSONObject();
            root.put("page", 1);
            root.put("pagecount", 1);
            root.put("limit", items.size());
            root.put("total", items.size());
            root.put("list", toArray(items));
            return root.toString();
        } catch (Throwable e) {
            return error("搜索失败: " + e);
        }
    }

    @Override
    public String detailContent(List<String> ids) {
        try {
            if (ids == null || ids.isEmpty()) return error("参数错误");
            String id = ids.get(0).replaceAll("\\D", "");
            String html = Http.get(host + "/p/" + id + "/");
            JSONObject item = new JSONObject();
            String title = one(html, "<title>(.*?)</title>");
            int cut = title.indexOf(" | ");
            if (cut > 0) title = title.substring(0, cut);
            String pic = one(html, "<meta property=\"og:image\" content=\"([^\"]+)\"");
            if (pic.isEmpty()) pic = one(html, "data-original=\"([^\"]+)\"");
            String desc = one(html, "<meta name=\"description\" content=\"([^\"]+)\"");
            String year = "";
            Matcher y = Pattern.compile("首播:\\s*(\\d{4})").matcher(html);
            if (y.find()) year = y.group(1);
            List<String[]> episodes = episodes(html);
            if (episodes.isEmpty()) return error("暂无播放源");
            StringBuilder play = new StringBuilder();
            for (String[] episode : episodes) {
                if (play.length() > 0) play.append('#');
                play.append(episode[0]).append('$').append(episode[1]);
            }
            item.put("vod_id", id);
            item.put("vod_name", clean(title));
            item.put("vod_pic", pic);
            item.put("vod_year", year);
            item.put("vod_content", clean(desc));
            item.put("vod_play_from", "茶杯狐");
            item.put("vod_play_url", play.toString());
            JSONArray list = new JSONArray();
            list.put(item);
            JSONObject root = new JSONObject();
            root.put("list", list);
            return root.toString();
        } catch (Throwable e) {
            return error("详情加载失败: " + e);
        }
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        try {
            String vid = id == null ? "" : id.replaceFirst("^cb:", "");
            String url = play(host, vid);
            if (url.isEmpty()) return error("播放地址为空");
            JSONObject root = new JSONObject();
            root.put("parse", 0);
            root.put("url", url);
            return root.toString();
        } catch (Throwable e) {
            return error("播放失败");
        }
    }

    /**
     * 给青花瓷用的聚合线路：按片名搜索茶杯狐，返回 "集名$cb:vid#..."。
     */
    static String line(String host, String name) {
        if (name == null || name.trim().isEmpty()) return null;
        String base = trim(host);
        try {
            String html = Http.get(base + "/u--/?wd=" + URLEncoder.encode(name.trim(), "UTF-8"));
            List<JSONObject> items = cards(html, 20);
            String id = null;
            for (JSONObject item : items) {
                String title = item.optString("vod_name");
                if (title.equals(name) || title.contains(name) || name.contains(title)) {
                    id = item.optString("vod_id");
                    break;
                }
            }
            if (id == null || id.isEmpty()) return null;
            String detailHtml = Http.get(base + "/p/" + id + "/");
            List<String[]> episodes = episodes(detailHtml);
            if (episodes.isEmpty()) return null;
            StringBuilder builder = new StringBuilder();
            for (String[] episode : episodes) {
                if (builder.length() > 0) builder.append('#');
                builder.append(episode[0]).append("$cb:").append(episode[1]);
            }
            return builder.toString();
        } catch (Throwable e) {
            return null;
        }
    }

    static String play(String host, String vid) {
        try {
            String html = Http.get(host + "/v/" + vid.replaceAll("[^0-9\\-]", "") + "/");
            String json = one(html, "var player_aaaa=(\\{.*?\\})</script>");
            if (json.isEmpty()) return "";
            JSONObject object = new JSONObject(json);
            return object.optString("url");
        } catch (Throwable e) {
            return "";
        }
    }

    static List<String[]> episodes(String html) {
        List<String[]> list = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        Matcher matcher = Pattern.compile("<a[^>]+href=\"/v/(\\d+-\\d+-\\d+)/\"[^>]*>(.*?)</a>", Pattern.DOTALL).matcher(html);
        while (matcher.find()) {
            String vid = matcher.group(1);
            if (seen.contains(vid)) continue;
            seen.add(vid);
            String name = clean(matcher.group(2));
            if (name.isEmpty()) name = vid;
            list.add(new String[]{name, vid});
        }
        return list;
    }

    static List<JSONObject> cards(String html, int limit) throws Exception {
        List<JSONObject> list = new ArrayList<>();
        Matcher matcher = Pattern.compile("<article class=\"u-movie\">(.*?)</article>", Pattern.DOTALL).matcher(html);
        while (matcher.find() && list.size() < limit) {
            String block = matcher.group(1);
            String id = one(block, "href=\"/p/(\\d+)/\"");
            String name = clean(one(block, "<h2>(.*?)</h2>"));
            if (id.isEmpty() || name.isEmpty()) continue;
            String pic = one(block, "data-original=\"([^\"]+)\"");
            String remark = clean(one(block, "class=\"zhuangtai\"><span>(.*?)</span>"));
            JSONObject item = new JSONObject();
            item.put("vod_id", id);
            item.put("vod_name", name);
            item.put("vod_pic", pic);
            item.put("vod_remarks", remark);
            list.add(item);
        }
        return list;
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

    private static JSONArray toArray(List<JSONObject> items) {
        JSONArray array = new JSONArray();
        for (JSONObject item : items) array.put(item);
        return array;
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
