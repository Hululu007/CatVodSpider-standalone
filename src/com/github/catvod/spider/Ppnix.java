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
 * PPnix（ppnix.com），自建站。
 * 播放地址规律：/info/m3u8/{infoid}/{param}.m3u8，param 取自详情页的 m3u8=[...]。
 */
public class Ppnix extends Spider {

    static final String DEFAULT_HOST = "https://www.ppnix.com";
    private static final int PAGE_SIZE = 24;
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
            classes.put(cls("2", "电视剧"));
            root.put("class", classes);
            root.put("list", cards(Http.get(host + "/cn/movie/"), PAGE_SIZE));
            return root.toString();
        } catch (Throwable e) {
            return error("首页加载失败");
        }
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, java.util.HashMap<String, String> extend) {
        try {
            int page = parseInt(pg, 1);
            String type = "2".equals(tid) ? "tv" : "movie";
            String url = host + "/cn/" + type + "/" + (page > 1 ? page + ".html" : "");
            JSONArray list = cards(Http.get(url), PAGE_SIZE);
            JSONObject root = new JSONObject();
            root.put("page", page);
            root.put("pagecount", list.length() >= PAGE_SIZE ? page + 1 : page);
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
            String url = host + "/cn/search/" + URLEncoder.encode(key == null ? "" : key, "UTF-8") + "--.html";
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
            String[] parts = ids.get(0).trim().split("_");
            if (parts.length < 2) return error("参数错误");
            String classId = parts[0];
            String infoId = parts[1];
            String type = "2".equals(classId) ? "tv" : "movie";
            String html = Http.get(host + "/cn/" + type + "/" + infoId + ".html");

            String name = clean(one(html, "<h1[^>]*>(.*?)</h1>"));
            int span = name.indexOf("(");
            if (span > 0) name = name.substring(0, span).trim();
            String year = one(html, "\\((\\d{4})\\)");
            String pic = one(html, "<img[^>]+src=\"(https://img\\.meituan[^\"]+)\"");
            if (pic.isEmpty()) pic = one(html, "<img[^>]+src=\"(https?://[^\"]+\\.(?:webp|jpg|jpeg|png))\"");
            String typeName = clean(one(html, "类型：<span>(.*?)</span>"));
            String area = clean(one(html, "国家：<span>(.*?)</span>"));
            String desc = clean(one(html, "简介：<span>(.*?)</span>"));

            List<String> params = new ArrayList<>();
            String array = one(html, "m3u8=\\[(.*?)\\]");
            Matcher matcher = Pattern.compile("'([^']*)'|\"([^\"]*)\"").matcher(array);
            while (matcher.find()) {
                String value = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
                if (value != null && !value.isEmpty()) params.add(value);
            }
            if (params.isEmpty()) return error("暂无播放源");

            StringBuilder episodes = new StringBuilder();
            for (String param : params) {
                if (episodes.length() > 0) episodes.append('#');
                String label = param.matches("\\d+") ? "第" + param + "集" : param;
                episodes.append(label).append('$').append(classId).append('_').append(infoId).append('_').append(param);
            }

            JSONObject item = new JSONObject();
            item.put("vod_id", classId + "_" + infoId);
            item.put("vod_name", name);
            item.put("vod_pic", pic);
            item.put("type_name", typeName);
            item.put("vod_year", year);
            item.put("vod_area", area);
            item.put("vod_content", desc);
            item.put("vod_play_from", "PPnix");
            item.put("vod_play_url", episodes.toString());
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
            String infoId = parts[1];
            String param = id.substring(parts[0].length() + infoId.length() + 2);
            String url = host + "/info/m3u8/" + infoId + "/" + URLEncoder.encode(param, "UTF-8") + ".m3u8";
            JSONObject root = new JSONObject();
            root.put("parse", 0);
            root.put("url", url);
            return root.toString();
        } catch (Throwable e) {
            return error("播放失败");
        }
    }

    private JSONArray cards(String html, int limit) throws Exception {
        JSONArray list = new JSONArray();
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        Matcher matcher = Pattern.compile("/cn/(movie|tv)/(\\d+)\\.html").matcher(html);
        while (matcher.find() && list.length() < limit) {
            String type = matcher.group(1);
            String id = matcher.group(2);
            if (seen.contains(id)) continue;
            int start = matcher.start();
            String block = html.substring(start, Math.min(html.length(), start + 700));
            String name = clean(one(block, "alt=\"([^\"]+)\""));
            if (name.isEmpty()) name = clean(one(block, "title=\"([^\"]+)\""));
            if (name.isEmpty()) continue;
            seen.add(id);
            String pic = one(block, "src=\"(https?://[^\"]+)\"");
            String remark = clean(one(block, "class=\"rate\">(.*?)</span>"));
            if (remark.isEmpty()) remark = clean(one(block, "class=\"orange\">(.*?)</span>"));
            JSONObject item = new JSONObject();
            item.put("vod_id", ("tv".equals(type) ? "2" : "1") + "_" + id);
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
