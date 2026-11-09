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
 * 稀饭影视（xifanys.com），苹果CMS + mxtheme 模板。
 * 列表 /vodshow，详情 /voddetail，播放 /vodplay，播放页 player_aaaa 带地址。
 */
public class Xifanys extends Spider {

    static final String DEFAULT_HOST = "https://www.xifanys.com";
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
            classes.put(cls("5", "短剧"));
            root.put("class", classes);
            root.put("list", cards(Http.get(showUrl("1", 1)), PAGE_SIZE));
            return root.toString();
        } catch (Throwable e) {
            return error("首页加载失败");
        }
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, java.util.HashMap<String, String> extend) {
        try {
            int page = parseInt(pg, 1);
            JSONArray list = cards(Http.get(showUrl(tid, page)), PAGE_SIZE);
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
            String url = host + "/vodsearch/" + URLEncoder.encode(key == null ? "" : key, "UTF-8") + "-------------.html";
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
            String html = Http.get(host + "/voddetail/" + id + ".html");

            String name = clean(one(html, "<h1[^>]*>(.*?)</h1>"));
            String pic = one(html, "<meta property=\"og:image\" content=\"([^\"]+)\"");
            if (pic.isEmpty()) pic = one(html, "data-original=\"([^\"]+)\"");
            String desc = clean(one(html, "module-info-introduction-content[^\"]*\"[^>]*>(.*?)</div>"));
            String year = one(html, "(\\d{4})<span class=\"slash\">");

            List<String> tabs = new ArrayList<>();
            Matcher tabMatcher = Pattern.compile("data-dropdown-value=\"([^\"]*)\"").matcher(html);
            while (tabMatcher.find()) tabs.add(clean(tabMatcher.group(1)));

            List<String[]> sources = new ArrayList<>();
            Matcher panels = Pattern.compile("<div class=\"module-play-list\">(.*?)</div></div>", Pattern.DOTALL).matcher(html);
            int index = 0;
            while (panels.find()) {
                StringBuilder episodes = new StringBuilder();
                Matcher links = Pattern.compile("<a[^>]+href=\"/vodplay/(\\d+)-(\\d+)-(\\d+)\\.html\"[^>]*>(.*?)</a>", Pattern.DOTALL).matcher(panels.group(1));
                while (links.find()) {
                    String epName = clean(links.group(4));
                    if (epName.isEmpty()) epName = "第" + links.group(3) + "集";
                    if (episodes.length() > 0) episodes.append('#');
                    episodes.append(epName).append('$').append(links.group(1)).append('_').append(links.group(2)).append('_').append(links.group(3));
                }
                if (episodes.length() > 0) {
                    String lineName = index < tabs.size() && !tabs.get(index).isEmpty() ? tabs.get(index) : "线路" + (index + 1);
                    sources.add(new String[]{lineName, episodes.toString()});
                }
                index++;
            }
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
            item.put("vod_year", year);
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
            String url = host + "/vodplay/" + parts[0] + "-" + parts[1] + "-" + parts[2] + ".html";
            String html = Http.get(url);
            String json = one(html, "player_aaaa=(\\{.*?\\})</script>");
            if (json.isEmpty()) return error("未找到播放数据");
            JSONObject data = new JSONObject(json);
            String playUrl = data.optString("url");
            if (playUrl.isEmpty()) return error("播放地址为空");
            JSONObject root = new JSONObject();
            boolean media = playUrl.contains(".m3u8") || playUrl.contains(".mp4") || playUrl.contains(".flv");
            root.put("parse", media ? 0 : 1);
            root.put("url", playUrl);
            return root.toString();
        } catch (Throwable e) {
            return error("播放失败");
        }
    }

    private String showUrl(String tid, int page) {
        return host + "/vodshow/" + tid + "--------" + page + "---.html";
    }

    private JSONArray cards(String html, int limit) throws Exception {
        JSONArray list = new JSONArray();
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        Matcher matcher = Pattern.compile("/voddetail/(\\d+)\\.html").matcher(html);
        while (matcher.find() && list.length() < limit) {
            String id = matcher.group(1);
            if (seen.contains(id)) continue;
            int start = matcher.start();
            String block = html.substring(start, Math.min(html.length(), start + 1200));
            String name = clean(one(block, "title=\"([^\"]+)\""));
            if (name.isEmpty()) name = clean(one(block, "<strong>(.*?)</strong>"));
            if (name.isEmpty()) continue;
            seen.add(id);
            String pic = one(block, "data-original=\"([^\"]+)\"");
            String remark = clean(one(block, "module-item-note\">(.*?)</div>"));
            JSONObject item = new JSONObject();
            item.put("vod_id", id);
            item.put("vod_name", name);
            item.put("vod_pic", pic);
            item.put("vod_remarks", remark);
            list.put(item);
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
