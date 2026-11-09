package com.github.catvod.spider;

import com.github.catvod.crawler.Spider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * 厂长资源（4kcz.com），WordPress + mibt 主题。
 * <p>
 * 列表：/movie_bt[/page/n]?movie_bt_series=..&movie_bt_tags=..&movie_bt_cat=..&movie_bt_year=..
 * 详情：/movie/{id}.html
 * 播放：/v_play/{base64}.html，页面里的 iframe 直接带 m3u8
 * 搜索：/nimasile/?q=关键词（注意必须带结尾斜杠）
 * <p>
 * 站点前面有雷池 WAF：curl/OpenSSL 指纹会被 403，Java 的 TLS 指纹实测可以正常拿到页面。
 */
public class Czzy extends Spider {

    private static final String DEFAULT_HOST = "https://www.4kcz.com";
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int PAGE_SIZE = 20;

    private String host = DEFAULT_HOST;

    private final List<String[]> series = new ArrayList<String[]>();
    private final List<String[]> tags = new ArrayList<String[]>();
    private final List<String[]> areas = new ArrayList<String[]>();
    private final List<String[]> years = new ArrayList<String[]>();

    /** 站点导航里的独立频道，不走筛选参数，路径直接翻页 */
    private static final String[][] SPECIALS = {
            {"s:/dbtop250", "豆瓣Top250"},
            {"s:/zuixindianying", "最新电影"},
            {"s:/dongmanjuchangban", "动漫剧场版"},
            {"s:/fanju", "番剧"},
            {"s:/gaofenyingshi", "高分影视"}
    };

    /** 站点分类的展示顺序，不在表里的分类排最后 */
    private static final List<String> SERIES_ORDER = Arrays.asList(
            "dyy", "dianshiju", "guochanju", "mj", "hj", "rj", "hwj",
            "huayudianying", "meiguodianying", "hanguodianying", "ribendianying",
            "yindudianying", "eluosidianying", "jianadadianying", "dohua",
            "zhanchangtuijian", "huiyuanzhuanqu"
    );

    @Override
    public void init(String extend) {
        if (extend == null) return;
        String ext = extend.trim();
        if (ext.isEmpty()) return;
        try {
            if (ext.startsWith("http")) {
                host = trimSlash(ext);
            } else if (ext.startsWith("{")) {
                JSONObject object = new JSONObject(ext);
                if (object.has("host")) host = trimSlash(object.optString("host"));
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public String homeContent(boolean filter) {
        try {
            String html = fetch(host + "/movie_bt");
            loadTaxonomies(html);

            JSONArray classes = new JSONArray();
            classes.put(cls("all", "全部"));
            for (String[] item : series) classes.put(cls(item[0], item[1]));
            for (String[] item : SPECIALS) classes.put(cls(item[0], item[1]));

            JSONObject root = new JSONObject();
            root.put("class", classes);
            if (filter) {
                JSONArray filterArray = buildFilters();
                JSONObject filters = new JSONObject();
                for (int i = 0; i < classes.length(); i++) {
                    String typeId = classes.getJSONObject(i).optString("type_id");
                    // 独立频道是固定模板，不支持影片类型/地区/年份筛选
                    if (!typeId.startsWith("s:")) filters.put(typeId, filterArray);
                }
                root.put("filters", filters);
            }
            root.put("list", parseCards(html));
            return root.toString();
        } catch (Throwable e) {
            return error("首页加载失败：" + brief(e));
        }
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        try {
            int page = parseInt(pg, 1);
            String url;
            if (tid != null && tid.startsWith("s:")) {
                url = host + tid.substring(2) + (page > 1 ? "/page/" + page : "");
            } else {
                url = buildListUrl(tid, page, extend);
            }
            JSONArray list = parseCards(fetch(url));

            int pageCount = list.length() >= PAGE_SIZE ? page + 1 : page;
            JSONObject root = new JSONObject();
            root.put("page", page);
            root.put("pagecount", pageCount);
            root.put("limit", list.length());
            root.put("total", pageCount * PAGE_SIZE);
            root.put("list", list);
            return root.toString();
        } catch (Throwable e) {
            return error("列表加载失败：" + brief(e));
        }
    }

    @Override
    public String searchContent(String key, boolean quick) {
        return searchContent(key, quick, "1");
    }

    @Override
    public String searchContent(String key, boolean quick, String pg) {
        try {
            String html = fetch(host + "/nimasile/?q=" + encode(key == null ? "" : key));
            JSONArray list = parseSearch(html);
            JSONObject root = new JSONObject();
            root.put("page", 1);
            root.put("pagecount", 1);
            root.put("limit", list.length());
            root.put("total", list.length());
            root.put("list", list);
            return root.toString();
        } catch (Throwable e) {
            return error("搜索失败：" + brief(e));
        }
    }

    @Override
    public String detailContent(List<String> ids) {
        try {
            if (ids == null || ids.isEmpty()) return error("参数错误");
            String id = ids.get(0).trim();
            String html = fetch(host + "/movie/" + id + ".html");

            String name = clean(one(html, "<div class=\"moviedteail_tt\">\\s*<h1>(.*?)</h1>"));
            if (name.isEmpty()) name = clean(one(html, "<h3 class=\"dy_tit_big[^\"]*\">(.*?)<var>"));
            String year = one(html, "<h3 class=\"dy_tit_big[^\"]*\">.*?<span>\\s*(\\d{4})\\s*</span>");
            if (year.isEmpty()) year = info(html, "年份");
            String pic = one(html, "<div class=\"dyimg[^\"]*\">\\s*<img[^>]+src=\"([^\"]+)\"");
            if (pic.isEmpty()) pic = one(html, "<div class=\"dyimg[^\"]*\">.*?<img[^>]+src=\"([^\"]+)\"");
            String type = info(html, "类型");
            String area = info(html, "地区");
            String actor = info(html, "主演");
            String director = info(html, "导演");
            String content = clean(one(html, "<div class=\"yp_context\">(.*?)</div>"));

            String playlist = one(html, "<div class=\"paly_list_btn\">(.*?)</div>");
            Matcher matcher = Pattern.compile("<a[^>]+href=\"([^\"]+)\"[^>]*>(.*?)</a>", Pattern.DOTALL).matcher(playlist);
            StringBuilder playUrl = new StringBuilder();
            int index = 0;
            while (matcher.find()) {
                String href = normalizePlayPath(matcher.group(1));
                String label = clean(matcher.group(2));
                if (href.isEmpty()) continue;
                if (label.isEmpty()) label = "第" + (++index) + "集";
                if (playUrl.length() > 0) playUrl.append('#');
                playUrl.append(label).append('$').append(href);
            }
            if (playUrl.length() == 0) return error("暂无播放源");

            JSONObject item = new JSONObject();
            item.put("vod_id", id);
            item.put("vod_name", name);
            item.put("vod_pic", pic);
            item.put("type_name", type);
            item.put("vod_year", year);
            item.put("vod_area", area);
            item.put("vod_actor", actor);
            item.put("vod_director", director);
            item.put("vod_content", content);
            item.put("vod_play_from", "厂长资源");
            item.put("vod_play_url", playUrl.toString());

            JSONArray list = new JSONArray();
            list.put(item);
            JSONObject root = new JSONObject();
            root.put("list", list);
            return root.toString();
        } catch (Throwable e) {
            return error("详情加载失败：" + brief(e));
        }
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        try {
            if (id == null || id.trim().isEmpty()) return error("播放参数错误");
            String value = id.trim();
            String pageUrl = value.startsWith("http") ? value : host + (value.startsWith("/") ? value : "/v_play/" + value + ".html");
            String html = fetch(pageUrl);

            String iframe = "";
            Matcher frames = Pattern.compile("<iframe[^>]+src=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(html);
            while (frames.find()) {
                String src = decodeHtml(frames.group(1));
                if (src.contains("player") || src.contains("url=")) {
                    iframe = src;
                    break;
                }
            }

            if (!iframe.isEmpty()) {
                String direct = queryParam(iframe, "url");
                if (!direct.isEmpty() && isVideo(direct)) return play(0, direct).toString();
                // iframe 本身是播放器页，交给宿主嗅探
                return play(1, iframe).toString();
            }

            String direct = one(html, "(https?://[^\"'\\s<>]+\\.(?:m3u8|mp4|flv)[^\"'\\s<>]*)");
            if (!direct.isEmpty()) return play(0, decodeHtml(direct)).toString();

            return error("未找到播放地址");
        } catch (Throwable e) {
            return error("播放失败：" + brief(e));
        }
    }

    // ---------------------------------------------------------------- 分类 / 筛选

    private void loadTaxonomies(String html) {
        series.clear();
        tags.clear();
        areas.clear();
        years.clear();
        parseTaxonomy(html, "series", series);
        parseTaxonomy(html, "tags", tags);
        parseTaxonomy(html, "cat", areas);
        parseTaxonomy(html, "year", years);

        if (series.isEmpty()) addDefaultSeries();
        Collections.sort(series, new Comparator<String[]>() {
            public int compare(String[] a, String[] b) {
                int ia = SERIES_ORDER.indexOf(a[0]);
                int ib = SERIES_ORDER.indexOf(b[0]);
                if (ia < 0) ia = SERIES_ORDER.size();
                if (ib < 0) ib = SERIES_ORDER.size();
                if (ia != ib) return ia - ib;
                return a[1].compareTo(b[1]);
            }
        });
    }

    private void parseTaxonomy(String html, String taxonomy, List<String[]> target) {
        int start = html.indexOf("id=\"beautiful-taxonomy-filters-tax-movie_bt_" + taxonomy + "\"");
        if (start < 0) return;
        int end = html.indexOf("</div>", start);
        if (end < 0) end = html.length();
        String block = html.substring(start, end);
        Matcher matcher = Pattern.compile("cat-url=\"([^\"]+)\"[^>]*>(.*?)</a>", Pattern.DOTALL).matcher(block);
        while (matcher.find()) {
            String url = matcher.group(1).trim();
            String name = clean(matcher.group(2));
            String slug = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
            slug = slug.substring(slug.lastIndexOf('/') + 1);
            try {
                slug = URLDecoder.decode(slug, "UTF-8");
            } catch (Exception ignored) {
            }
            if (!name.isEmpty() && !slug.isEmpty()) target.add(new String[]{slug, name});
        }
    }

    private void addDefaultSeries() {
        series.add(new String[]{"dyy", "电影"});
        series.add(new String[]{"dianshiju", "电视剧"});
        series.add(new String[]{"guochanju", "国产剧"});
        series.add(new String[]{"mj", "美剧"});
        series.add(new String[]{"hj", "韩剧"});
        series.add(new String[]{"rj", "日剧"});
        series.add(new String[]{"hwj", "海外剧（其他）"});
        series.add(new String[]{"huayudianying", "华语电影"});
        series.add(new String[]{"meiguodianying", "欧美电影"});
        series.add(new String[]{"hanguodianying", "韩国电影"});
        series.add(new String[]{"ribendianying", "日本电影"});
        series.add(new String[]{"yindudianying", "印度电影"});
        series.add(new String[]{"eluosidianying", "俄罗斯电影"});
        series.add(new String[]{"jianadadianying", "加拿大电影"});
        series.add(new String[]{"dohua", "动画"});
    }

    private JSONArray buildFilters() throws Exception {
        JSONArray filters = new JSONArray();
        filters.put(filter("tag", "影片类型", tags));
        filters.put(filter("area", "地区", areas));
        filters.put(filter("year", "年份", years));
        return filters;
    }

    private JSONObject filter(String key, String name, List<String[]> values) throws Exception {
        JSONArray array = new JSONArray();
        array.put(value("全部", ""));
        for (String[] item : values) array.put(value(item[1], item[0]));
        JSONObject object = new JSONObject();
        object.put("key", key);
        object.put("name", name);
        object.put("value", array);
        return object;
    }

    private String buildListUrl(String tid, int page, HashMap<String, String> extend) throws Exception {
        StringBuilder url = new StringBuilder(host).append("/movie_bt");
        if (page > 1) url.append("/page/").append(page);
        List<String> query = new ArrayList<String>();
        if (tid != null && !tid.isEmpty() && !"all".equals(tid)) {
            query.add("movie_bt_series=" + encode(tid));
        }
        if (extend != null) {
            addQuery(query, "movie_bt_tags", extend.get("tag"));
            addQuery(query, "movie_bt_cat", extend.get("area"));
            addQuery(query, "movie_bt_year", extend.get("year"));
        }
        if (!query.isEmpty()) url.append('?').append(join(query, "&"));
        return url.toString();
    }

    private void addQuery(List<String> query, String key, String value) throws Exception {
        if (value != null && !value.trim().isEmpty()) query.add(key + "=" + encode(value.trim()));
    }

    // ---------------------------------------------------------------- 解析

    private JSONArray parseCards(String html) throws Exception {
        String block = section(html, "mi_ne_kd mrb", "</ul>");
        if (block.isEmpty()) block = section(html, "search_list", "</ul>");
        if (block.isEmpty()) return new JSONArray();

        JSONArray list = new JSONArray();
        java.util.HashSet<String> seen = new java.util.HashSet<String>();
        Matcher items = Pattern.compile("<li>.*?</li>", Pattern.DOTALL).matcher(block);
        while (items.find()) {
            String item = items.group(0);
            String id = one(item, "/movie/(\\d+)\\.html");
            if (id.isEmpty() || seen.contains(id)) continue;
            String name = clean(one(item, "alt=\"([^\"]+)\""));
            if (name.isEmpty()) name = clean(one(item, "<h3[^>]*>\\s*<a[^>]*>(.*?)</a>"));
            if (name.isEmpty()) continue;
            String pic = one(item, "data-original=\"([^\"]+)\"");
            if (pic.isEmpty()) pic = one(item, "<img[^>]+src=\"(https?://[^\"]+)\"");
            String remark = clean(one(item, "<div class=\"hdinfo\">(.*?)</div>"));
            if (remark.isEmpty()) remark = clean(one(item, "<div class=\"rating\">(.*?)</div>"));
            seen.add(id);
            JSONObject vod = new JSONObject();
            vod.put("vod_id", id);
            vod.put("vod_name", name);
            vod.put("vod_pic", pic);
            vod.put("vod_remarks", remark);
            list.put(vod);
        }
        return list;
    }

    private JSONArray parseSearch(String html) throws Exception {
        String block = section(html, "search_list", "</ul>");
        if (block.isEmpty()) return new JSONArray();
        JSONArray list = new JSONArray();
        java.util.HashSet<String> seen = new java.util.HashSet<String>();
        Matcher items = Pattern.compile("<li>.*?</li>", Pattern.DOTALL).matcher(block);
        while (items.find()) {
            String item = items.group(0);
            String id = one(item, "/movie/(\\d+)\\.html");
            if (id.isEmpty() || seen.contains(id)) continue;
            String name = clean(one(item, "alt=\"([^\"]+)\""));
            if (name.isEmpty()) name = clean(one(item, "<h3[^>]*>\\s*<a[^>]*>(.*?)</a>"));
            if (name.isEmpty()) continue;
            String pic = one(item, "<img[^>]+src=\"(https?://[^\"]+)\"");
            seen.add(id);
            JSONObject vod = new JSONObject();
            vod.put("vod_id", id);
            vod.put("vod_name", name);
            vod.put("vod_pic", pic);
            vod.put("vod_remarks", "");
            list.put(vod);
        }
        return list;
    }

    private String info(String html, String label) {
        Matcher matcher = Pattern.compile("<li>\\s*" + Pattern.quote(label) + "：?(.*?)</li>", Pattern.DOTALL).matcher(html);
        if (matcher.find()) return clean(matcher.group(1));
        return "";
    }

    private String section(String html, String marker, String endMarker) {
        int start = html.indexOf(marker);
        if (start < 0) return "";
        int end = html.indexOf(endMarker, start);
        if (end < 0) end = html.length();
        return html.substring(start, end);
    }

    private String normalizePlayPath(String href) {
        String value = href.trim();
        if (value.startsWith("http")) {
            try {
                URL url = new URL(value);
                return url.getFile();
            } catch (Exception ignored) {
                return value;
            }
        }
        return value.startsWith("/") ? value : "/" + value;
    }

    private boolean isVideo(String url) {
        String lower = url.toLowerCase();
        return lower.contains(".m3u8") || lower.contains(".mp4") || lower.contains(".flv");
    }

    private String queryParam(String url, String key) {
        int q = url.indexOf('?');
        if (q < 0) return "";
        String query = url.substring(q + 1);
        for (String part : query.split("&")) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            if (part.substring(0, eq).equals(key)) {
                String value = part.substring(eq + 1);
                try {
                    return URLDecoder.decode(value, "UTF-8");
                } catch (Exception e) {
                    return value;
                }
            }
        }
        return "";
    }

    // ---------------------------------------------------------------- HTTP

    private String fetch(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(20000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestMethod("GET");
        connection.setRequestProperty("User-Agent", UA);
        connection.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8");
        connection.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
        connection.setRequestProperty("Referer", host + "/");
        connection.setRequestProperty("Upgrade-Insecure-Requests", "1");
        connection.setRequestProperty("Connection", "close");
        if (connection instanceof HttpsURLConnection) {
            HttpsURLConnection https = (HttpsURLConnection) connection;
            https.setSSLSocketFactory(trustAll());
            https.setHostnameVerifier(new HostnameVerifier() {
                public boolean verify(String hostname, SSLSession session) {
                    return true;
                }
            });
        }

        int code = connection.getResponseCode();
        InputStream input = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
        if (input == null) throw new IOException("HTTP " + code);
        String encoding = connection.getContentEncoding();
        if (encoding != null && encoding.toLowerCase().contains("gzip")) {
            input = new GZIPInputStream(input);
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
            output.write(buffer, 0, count);
            if (output.size() > 8 * 1024 * 1024) break;
        }
        input.close();
        String body = new String(output.toByteArray(), UTF8);

        if (code != 200) throw new IOException("HTTP " + code);
        if (body.contains("slg-title") || body.contains("访问已被拦截")) {
            throw new IOException("被雷池 WAF 拦截");
        }
        if (body.contains("该网页已被加密保护")) {
            throw new IOException("站点返回 WAF 加密页");
        }
        return body;
    }

    private static SSLSocketFactory trustAllFactory;

    private static synchronized SSLSocketFactory trustAll() throws Exception {
        if (trustAllFactory != null) return trustAllFactory;
        TrustManager[] managers = new TrustManager[]{new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }

            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        }};
        SSLContext context;
        try {
            context = SSLContext.getInstance("TLSv1.2");
        } catch (Exception e) {
            context = SSLContext.getInstance("TLS");
        }
        context.init(null, managers, new SecureRandom());
        trustAllFactory = context.getSocketFactory();
        return trustAllFactory;
    }

    // ---------------------------------------------------------------- 工具

    private JSONObject cls(String id, String name) throws Exception {
        JSONObject object = new JSONObject();
        object.put("type_id", id);
        object.put("type_name", name);
        return object;
    }

    private JSONObject value(String name, String value) throws Exception {
        JSONObject object = new JSONObject();
        object.put("n", name);
        object.put("v", value);
        return object;
    }

    private JSONObject play(int parse, String url) throws Exception {
        JSONObject object = new JSONObject();
        object.put("parse", parse);
        object.put("url", url);
        return object;
    }

    private static String one(String text, String regex) {
        try {
            Matcher matcher = Pattern.compile(regex, Pattern.DOTALL).matcher(text);
            if (matcher.find()) return matcher.group(1);
        } catch (Throwable ignored) {
        }
        return "";
    }

    private static String clean(String text) {
        if (text == null) return "";
        String value = text.replaceAll("<[^>]+>", " ");
        value = decodeHtml(value);
        return value.replaceAll("\\s+", " ").trim();
    }

    private static String decodeHtml(String text) {
        if (text == null) return "";
        return text.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")
                .replace("&ldquo;", "“").replace("&rdquo;", "”").replace("&middot;", "·")
                .replace("&hellip;", "…");
    }

    private static String encode(String value) throws Exception {
        return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
    }

    private static String join(List<String> values, String separator) {
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (builder.length() > 0) builder.append(separator);
            builder.append(value);
        }
        return builder.toString();
    }

    private static int parseInt(String value, int defaultValue) {
        try {
            return Integer.parseInt(value);
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private static String trimSlash(String value) {
        String text = value == null ? "" : value.trim();
        while (text.endsWith("/")) text = text.substring(0, text.length() - 1);
        return text.isEmpty() ? DEFAULT_HOST : text;
    }

    private static String brief(Throwable e) {
        String text = String.valueOf(e);
        return text.length() > 160 ? text.substring(0, 160) : text;
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
