package com.github.catvod.spider;

import com.github.catvod.crawler.Spider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * 欧乐影院（olevod.tv）。
 * 直接使用官方 APP/网页接口，只取免费直链，不进入网页播放器，因此没有网页广告。
 */
public class Olevod extends Spider {

    private static final String HOST = "https://api.olelive.com";
    private static final String IMAGE_BASE = "https://static.olelive.com";
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private static final int[] ROOT_ORDER = {1, 2, 3, 4, 14};
    private static final String[] ROOT_NAMES = {"电影", "连续剧", "综艺", "动漫", "短剧"};

    private String host = HOST;
    private JSONArray typeRoots;

    @Override
    public void init(String extend) {
        if (extend == null) return;
        String ext = extend.trim();
        if (ext.isEmpty()) return;
        try {
            if (ext.startsWith("http")) {
                host = ext;
            } else if (ext.startsWith("{")) {
                JSONObject object = new JSONObject(ext);
                if (object.has("host")) host = object.optString("host");
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public String homeContent(boolean filter) {
        try {
            JSONArray roots = types();
            JSONObject rootById = new JSONObject();
            for (int i = 0; i < roots.length(); i++) {
                JSONObject root = roots.optJSONObject(i);
                if (root != null) rootById.put(String.valueOf(root.optInt("typeId")), root);
            }

            JSONArray classes = new JSONArray();
            JSONObject filters = new JSONObject();
            JSONArray sections = new JSONArray();
            JSONArray banners = banners();

            for (int i = 0; i < ROOT_ORDER.length; i++) {
                int id = ROOT_ORDER[i];
                JSONObject root = rootById.optJSONObject(String.valueOf(id));
                if (root == null) continue;
                JSONObject cls = new JSONObject();
                cls.put("type_id", String.valueOf(id));
                cls.put("type_name", ROOT_NAMES[i]);
                classes.put(cls);
                filters.put(String.valueOf(id), buildFilters(root));

                try {
                    JSONArray list = list(id, 0, "", "", "", "update", 1, 12);
                    if (list.length() > 0) {
                        JSONObject section = new JSONObject();
                        section.put("title", ROOT_NAMES[i]);
                        JSONArray mapped = new JSONArray();
                        for (int k = 0; k < list.length(); k++) {
                            JSONObject item = list.optJSONObject(k);
                            if (item != null && !item.optBoolean("vip", false)) mapped.put(vod(item));
                        }
                        section.put("list", mapped);
                        sections.put(section);
                    }
                } catch (Throwable ignored) {
                }
            }

            JSONObject root = new JSONObject();
            root.put("class", classes);
            if (filter) root.put("filters", filters);
            root.put("banners", banners);
            root.put("sections", sections);
            JArrayHelper.putAll(root, "list", sections, "list");
            return root.toString();
        } catch (Throwable e) {
            return error("首页加载失败: " + e.getMessage());
        }
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        try {
            int page = parseInt(pg, 1);
            int typeId = parseInt(tid, 1);
            String type2 = extend == null ? "" : trim(extend.get("type2"));
            String area = extend == null ? "" : trim(extend.get("area"));
            String year = extend == null ? "" : trim(extend.get("year"));
            String sort = extend == null ? "update" : trim(extend.get("sort"));
            if (sort.isEmpty()) sort = "update";
            JSONArray list = list(typeId, parseInt(type2, 0), area, year, "", sort, page, 48);
            JSONObject data = lastData;
            int total = data == null ? list.length() : data.optInt("total", list.length());
            int pageSize = data == null ? 48 : Math.max(1, data.optInt("pageSize", 48));

            JSONArray items = new JSONArray();
            for (int i = 0; i < list.length(); i++) {
                JSONObject item = list.optJSONObject(i);
                if (item != null && !item.optBoolean("vip", false)) {
                    JSONObject mapped = vod(item);
                    if (mapped.optString("vod_id").isEmpty()) continue;
                    items.put(mapped);
                }
            }
            JSONObject root = new JSONObject();
            root.put("page", page);
            root.put("pagecount", total <= 0 ? page : (total + pageSize - 1) / pageSize);
            root.put("limit", items.length());
            root.put("total", total);
            root.put("list", items);
            return root.toString();
        } catch (Throwable e) {
            return error("列表加载失败: " + e.getMessage());
        }
    }

    @Override
    public String detailContent(List<String> ids) {
        try {
            if (ids == null || ids.isEmpty()) return error("参数错误");
            String vodId = ids.get(0).trim();
            JSONObject data = api("/v1/pub/vod/detail/" + vodId + "/true").optJSONObject("data");
            JSONObject item = new JSONObject();
            item.put("vod_id", vodId);
            item.put("vod_name", str(data, "name"));
            item.put("vod_pic", image(str(data, "pic")));
            item.put("vod_content", str(data, "content"));
            item.put("vod_year", str(data, "year"));
            item.put("vod_area", str(data, "area"));
            item.put("vod_actor", str(data, "actor"));
            item.put("vod_director", str(data, "director"));
            item.put("type_name", firstNonEmpty(str(data, "typeId1Name"), str(data, "typeIdName")));
            item.put("vod_remarks", remarks(data));

            JSONArray urls = data.optJSONArray("urls");
            List<String> episodes = new ArrayList<>();
            if (urls != null) {
                for (int i = 0; i < urls.length(); i++) {
                    JSONObject url = urls.optJSONObject(i);
                    if (url == null || url.optBoolean("vip", false)) continue;
                    String play = str(url, "url");
                    if (play.isEmpty() || !isDirect(play)) continue;
                    String title = str(url, "title");
                    if (title.isEmpty()) title = "第" + (i + 1) + "集";
                    episodes.add(title + "$" + play);
                }
            }
            if (episodes.isEmpty()) return error("暂无免费播放源");
            StringBuilder playUrl = new StringBuilder();
            for (String episode : episodes) {
                if (playUrl.length() > 0) playUrl.append('#');
                playUrl.append(episode);
            }
            item.put("vod_play_from", "欧乐");
            item.put("vod_play_url", playUrl.toString());

            JSONArray result = new JSONArray();
            result.put(item);
            JSONObject root = new JSONObject();
            root.put("list", result);
            return root.toString();
        } catch (Throwable e) {
            return error("详情加载失败: " + e.getMessage());
        }
    }

    @Override
    public String searchContent(String key, boolean quick) {
        return searchContent(key, quick, "1");
    }

    @Override
    public String searchContent(String key, boolean quick, String pg) {
        try {
            int page = parseInt(pg, 1);
            JSONObject response = api("/v1/pub/index/search/" + encode(key) + "/vod/0/" + page + "/30");
            JSONObject data = response.optJSONObject("data");
            JSONArray groups = data == null ? null : data.optJSONArray("data");
            JSONArray items = new JSONArray();
            if (groups != null) {
                for (int i = 0; i < groups.length(); i++) {
                    JSONObject group = groups.optJSONObject(i);
                    if (group == null) continue;
                    JSONArray list = group.optJSONArray("list");
                    if (list == null) continue;
                    for (int j = 0; j < list.length(); j++) {
                        JSONObject item = list.optJSONObject(j);
                        if (item == null || item.optBoolean("vip", false)) continue;
                        JSONObject mapped = vod(item);
                        if (!mapped.optString("vod_id").isEmpty()) items.put(mapped);
                    }
                }
            }
            int total = data == null ? items.length() : data.optInt("total", items.length());
            JSONObject root = new JSONObject();
            root.put("page", page);
            root.put("pagecount", total <= 0 ? page : (total + 29) / 30);
            root.put("limit", items.length());
            root.put("total", total);
            root.put("list", items);
            return root.toString();
        } catch (Throwable e) {
            return error("搜索失败: " + e.getMessage());
        }
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        try {
            if (id != null && id.startsWith("http")) {
                JSONObject root = new JSONObject();
                root.put("parse", 0);
                root.put("url", id);
                return root.toString();
            }
        } catch (Throwable ignored) {
        }
        return error("播放地址为空");
    }

    @Override
    public String action(String action) {
        try {
            if (action != null && action.startsWith("hot:")) {
                JSONObject data = api("/v1/pub/index/vod/data/1").optJSONObject("data");
                JSONArray rank = data == null ? null : data.optJSONArray("rank");
                JSONArray list = new JSONArray();
                if (rank != null) {
                    for (int i = 0; i < rank.length(); i++) {
                        JSONObject item = rank.optJSONObject(i);
                        if (item == null || item.optBoolean("vip", false)) continue;
                        JSONObject mapped = vod(item);
                        if (!mapped.optString("vod_id").isEmpty()) list.put(mapped);
                    }
                }
                JSONObject root = new JSONObject();
                root.put("list", list);
                return root.toString();
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    private JSONObject lastData;

    private JSONArray list(int typeId, int type2, String area, String year, String letter,
                           String sort, int page, int pageSize) throws Exception {
        String path = "/v1/pub/vod/list/true/2/"
                + (letter == null || letter.isEmpty() ? "0" : encode(letter)) + "/"
                + (area == null || area.isEmpty() ? "0" : encode(area)) + "/"
                + typeId + "/" + (type2 <= 0 ? "0" : String.valueOf(type2)) + "/"
                + (year == null || year.isEmpty() ? "0" : encode(year)) + "/"
                + encode(sort) + "/" + page + "/" + pageSize;
        JSONObject response = api(path);
        JSONObject data = response.optJSONObject("data");
        lastData = data;
        if (data == null) return new JSONArray();
        JSONArray list = data.optJSONArray("list");
        return list == null ? new JSONArray() : list;
    }

    private JSONArray types() throws Exception {
        if (typeRoots != null) return typeRoots;
        JSONObject response = api("/v1/pub/vod/list/type");
        JSONArray array = response.optJSONArray("data");
        typeRoots = array == null ? new JSONArray() : array;
        return typeRoots;
    }

    private JSONArray banners() {
        JSONArray result = new JSONArray();
        try {
            JSONObject response = api("/v1/pub/index/banners");
            JSONArray data = response.optJSONArray("data");
            if (data == null) return result;
            for (int i = 0; i < data.length() && result.length() < 8; i++) {
                JSONObject banner = data.optJSONObject(i);
                if (banner == null) continue;
                String id = str(banner, "id");
                String pic = image(firstNonEmpty(str(banner, "img"), str(banner, "shadowImg")));
                if (id.isEmpty() || pic.isEmpty()) continue;
                JSONObject item = new JSONObject();
                item.put("vod_id", id);
                item.put("vod_name", str(banner, "title"));
                item.put("vod_pic", pic);
                item.put("vod_remarks", str(banner, "desc"));
                item.put("vod_content", str(banner, "desc"));
                result.put(item);
            }
        } catch (Throwable ignored) {
        }
        return result;
    }

    private JSONObject vod(JSONObject object) throws Exception {
        JSONObject item = new JSONObject();
        String id = str(object, "id");
        item.put("vod_id", id);
        item.put("vod_name", str(object, "name"));
        String pic = firstNonEmpty(str(object, "picThumb"), str(object, "pic"));
        item.put("vod_pic", image(pic));
        item.put("vod_remarks", remarks(object));
        item.put("vod_year", str(object, "year"));
        item.put("vod_area", str(object, "area"));
        item.put("vod_content", str(object, "blurb"));
        String type = str(object, "typeId1Name");
        if (type.isEmpty()) {
            int rootId = object.optInt("typeId1", 0);
            type = rootName(rootId);
        }
        item.put("type_name", type);
        return item;
    }

    private String remarks(JSONObject object) {
        String remarks = str(object, "remarks");
        if (!remarks.isEmpty()) return remarks;
        String version = str(object, "version");
        if (!version.isEmpty()) return version;
        Object score = object.opt("score");
        if (score instanceof Number && ((Number) score).doubleValue() > 0) {
            return String.valueOf(score);
        }
        return "";
    }

    private JSONArray buildFilters(JSONObject root) throws Exception {
        JSONArray filters = new JSONArray();
        JSONArray children = root.optJSONArray("children");
        if (children != null && children.length() > 0) {
            JSONArray values = new JSONArray();
            values.put(value("全部", ""));
            for (int i = 0; i < children.length(); i++) {
                JSONObject child = children.optJSONObject(i);
                if (child == null) continue;
                values.put(value(str(child, "typeName"), str(child, "typeId")));
            }
            filters.put(filter("type2", "类型", values));
        }
        JSONArray areas = root.optJSONArray("area");
        if (areas != null && areas.length() > 0) {
            JSONArray values = new JSONArray();
            values.put(value("全部", ""));
            for (int i = 0; i < areas.length(); i++) {
                String area = areas.optString(i);
                if (!area.isEmpty()) values.put(value(area, area));
            }
            filters.put(filter("area", "地区", values));
        }
        JSONArray years = new JSONArray();
        years.put(value("全部", ""));
        int current = Calendar.getInstance().get(Calendar.YEAR);
        for (int year = current; year >= current - 25; year--) {
            years.put(value(String.valueOf(year), String.valueOf(year)));
        }
        filters.put(filter("year", "年份", years));

        JSONArray sorts = new JSONArray();
        sorts.put(value("最新", "update"));
        sorts.put(value("添加", "desc"));
        sorts.put(value("最热", "hot"));
        sorts.put(value("评分", "score"));
        filters.put(filter("sort", "排序", sorts));
        return filters;
    }

    private JSONObject filter(String key, String name, JSONArray values) throws Exception {
        JSONObject filter = new JSONObject();
        filter.put("key", key);
        filter.put("name", name);
        filter.put("value", values);
        return filter;
    }

    private JSONObject value(String name, String value) throws Exception {
        JSONObject object = new JSONObject();
        object.put("n", name);
        object.put("v", value);
        return object;
    }

    private String rootName(int rootId) {
        for (int i = 0; i < ROOT_ORDER.length; i++) {
            if (ROOT_ORDER[i] == rootId) return ROOT_NAMES[i];
        }
        return "";
    }

    private JSONObject api(String path) throws Exception {
        String body = get(path);
        JSONObject response = new JSONObject(body);
        if (response.optInt("code", 0) != 0) {
            throw new IllegalStateException(response.optString("msg", "接口错误"));
        }
        return response;
    }

    private String get(String path) throws Exception {
        String url = host + path + (path.contains("?") ? "&" : "?") + "_vv=" + vv();
        HttpURLConnection connection = null;
        try {
            connection = open(url);
            connection.setRequestProperty("User-Agent", UA);
            connection.setRequestProperty("Referer", "https://www.olevod.tv/");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Accept-Encoding", "gzip");
            int code = connection.getResponseCode();
            InputStream input = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (input == null) throw new IllegalStateException("HTTP " + code);
            String encoding = connection.getContentEncoding();
            if (encoding != null && encoding.toLowerCase().contains("gzip")) {
                input = new GZIPInputStream(input);
            }
            String body = read(input);
            if (code >= 400) throw new IllegalStateException("HTTP " + code + " " + body);
            return body;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private HttpURLConnection open(String url) throws Exception {
        URL target = new URL(url);
        HttpURLConnection connection = (HttpURLConnection) target.openConnection();
        if (connection instanceof HttpsURLConnection) {
            HttpsURLConnection https = (HttpsURLConnection) connection;
            https.setSSLSocketFactory(ssl().getSocketFactory());
            https.setHostnameVerifier(new HostnameVerifier() {
                @Override
                public boolean verify(String hostname, SSLSession session) {
                    return true;
                }
            });
        }
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(20000);
        connection.setInstanceFollowRedirects(true);
        return connection;
    }

    private SSLContext ssl() throws Exception {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[]{new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        }}, new SecureRandom());
        return context;
    }

    private String vv() throws Exception {
        String ts = String.valueOf(System.currentTimeMillis() / 1000);
        String[] groups = {"", "", "", ""};
        for (char c : ts.toCharArray()) {
            String bits = Integer.toBinaryString(c);
            if (bits.length() < 6) continue;
            groups[0] += bits.charAt(2);
            groups[1] += bits.charAt(3);
            groups[2] += bits.charAt(4);
            groups[3] += bits.substring(5);
        }
        String md5 = md5(ts);
        StringBuilder builder = new StringBuilder();
        builder.append(md5, 0, 3).append(hex3(groups[0]));
        builder.append(md5, 6, 11).append(hex3(groups[1]));
        builder.append(md5, 14, 19).append(hex3(groups[2]));
        builder.append(md5, 22, 27).append(hex3(groups[3]));
        builder.append(md5.substring(30));
        return builder.toString();
    }

    private String hex3(String bits) {
        String hex = Integer.toHexString(Integer.parseInt(bits, 2));
        while (hex.length() < 3) hex = "0" + hex;
        return hex;
    }

    private String md5(String text) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("MD5");
        byte[] bytes = digest.digest(text.getBytes(UTF8));
        StringBuilder builder = new StringBuilder();
        for (byte b : bytes) {
            String hex = Integer.toHexString(b & 0xff);
            if (hex.length() == 1) builder.append('0');
            builder.append(hex);
        }
        return builder.toString();
    }

    private String read(InputStream input) throws Exception {
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = stream.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toString("UTF-8");
        }
    }

    private String image(String path) {
        if (path == null) return "";
        String value = path.trim();
        if (value.startsWith("http")) return value;
        while (value.startsWith("/")) value = value.substring(1);
        return value.isEmpty() ? "" : IMAGE_BASE + "/" + value;
    }

    private String encode(String value) throws Exception {
        if (value == null) return "";
        return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
    }

    private boolean isDirect(String url) {
        String value = url.toLowerCase();
        return value.startsWith("http") && (value.contains(".m3u8") || value.contains(".mp4"));
    }

    private String str(JSONObject object, String key) {
        if (object == null || !object.has(key) || object.isNull(key)) return "";
        return object.optString(key, "").trim();
    }

    private String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) return value.trim();
        }
        return "";
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private String error(String message) {
        try {
            JSONObject root = new JSONObject();
            root.put("code", 0);
            root.put("msg", message);
            root.put("list", new JSONArray());
            return root.toString();
        } catch (Throwable ignored) {
            return "{}";
        }
    }

    private static class JArrayHelper {
        static void putAll(JSONObject root, String key, JSONArray sections, String childKey) {
            try {
                JSONArray list = new JSONArray();
                for (int i = 0; i < sections.length(); i++) {
                    JSONObject section = sections.optJSONObject(i);
                    if (section == null) continue;
                    JSONArray items = section.optJSONArray(childKey);
                    if (items == null) continue;
                    for (int j = 0; j < items.length(); j++) {
                        JSONObject item = items.optJSONObject(j);
                        if (item != null) list.put(item);
                    }
                }
                root.put(key, list);
            } catch (Throwable ignored) {
            }
        }
    }
}
