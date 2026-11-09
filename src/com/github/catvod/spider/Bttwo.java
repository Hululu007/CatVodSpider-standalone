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
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * 两个BT影视（bttwo.life）。
 * <p>
 * 列表/筛选：/filter?classify=&areas=&types=&years=&tags=&sort_by=&order=&page=
 * 详情/剧集：/play/{slug}
 * 播放：页面 WASM 生成 /video/play?p=&v=&q=&s=&t=&k=，
 * s = HMAC-SHA256(key=slug, msg=dataid:t:slug) 前 32 位，
 * k = base64(XOR(userlink, "nbmovie2024secretkey"))。
 */
public class Bttwo extends Spider {

    private static final String DEFAULT_HOST = "https://www.bttwo.life";
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final String XOR_KEY = "nbmovie2024secretkey";
    private static final int PAGE_SIZE = 24;

    private String host = DEFAULT_HOST;

    private final List<String[]> types = new ArrayList<String[]>();
    private final List<String[]> areas = new ArrayList<String[]>();
    private final List<String[]> years = new ArrayList<String[]>();
    private final List<String[]> tags = new ArrayList<String[]>();
    private final List<String[]> sorts = new ArrayList<String[]>();

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
            String html = fetch(host + "/filter");
            loadFilters(html);

            JSONArray classes = new JSONArray();
            classes.put(cls("all", "全部"));
            classes.put(cls("1", "电影"));
            classes.put(cls("2", "电视剧"));
            classes.put(cls("3", "动漫"));
            classes.put(cls("4", "综艺"));

            JSONObject root = new JSONObject();
            root.put("class", classes);
            if (filter) {
                JSONArray filterArray = buildFilters();
                JSONObject filters = new JSONObject();
                for (int i = 0; i < classes.length(); i++) {
                    filters.put(classes.getJSONObject(i).optString("type_id"), filterArray);
                }
                root.put("filters", filters);
            }
            root.put("list", parseCards(html, PAGE_SIZE));
            return root.toString();
        } catch (Throwable e) {
            return error("首页加载失败：" + brief(e));
        }
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        try {
            int page = parseInt(pg, 1);
            StringBuilder url = new StringBuilder(host).append("/filter");
            List<String> query = new ArrayList<String>();
            if (tid != null && !tid.isEmpty() && !"all".equals(tid)) {
                query.add("classify=" + encode(tid));
            }
            if (extend != null) {
                addQuery(query, "areas", extend.get("area"));
                addQuery(query, "types", extend.get("type"));
                addQuery(query, "years", extend.get("year"));
                addQuery(query, "tags", extend.get("tag"));
                String sort = extend.get("sort");
                if (sort != null && sort.contains(",")) {
                    String[] parts = sort.split(",");
                    if (parts.length == 2) {
                        query.add("sort_by=" + encode(parts[0]));
                        query.add("order=" + encode(parts[1]));
                    }
                }
            }
            if (page > 1) query.add("page=" + page);
            if (!query.isEmpty()) url.append('?').append(join(query, "&"));

            String html = fetch(url.toString());
            JSONArray list = parseCards(html, PAGE_SIZE);
            int pageCount = maxPage(html);
            if (pageCount < page) pageCount = page;
            if (pageCount == 0) pageCount = list.length() >= PAGE_SIZE ? page + 1 : page;

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
            String html = fetch(host + "/search?q=" + encode(key == null ? "" : key));
            JSONArray list = parseCards(html, 40);
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
            String slug = ids.get(0).trim();
            String html = fetch(host + "/play/" + slug);

            String name = clean(one(html, "<h1[^>]*>(.*?)</h1>"));
            if (name.isEmpty()) {
                name = clean(one(html, "<meta property=\"og:title\" content=\"([^\"]+)\""));
                int dash = name.indexOf(" - ");
                if (dash > 0) name = name.substring(0, dash).trim();
            }
            String pic = decodeHtml(one(html, "<meta property=\"og:image\" content=\"([^\"]+)\""));
            String content = decodeHtml(one(html, "<meta property=\"og:description\" content=\"([^\"]+)\""));

            HashMap<String, String> info = parseInfo(html);
            String type = info.get("类型");
            String area = info.get("地区");
            String actor = info.get("主演");
            String director = info.get("导演");
            String release = info.get("上映");
            String year = "";
            Matcher yearMatcher = Pattern.compile("(\\d{4})").matcher(release);
            if (yearMatcher.find()) year = yearMatcher.group(1);

            // 线路名称：episodeManager(..., [{lineName:'alists', episodeCount:N}, ...])
            List<String> lineNames = new ArrayList<String>();
            Matcher lineMatcher = Pattern.compile(
                    "lineName:\\s*'([^']*)'\\s*,\\s*episodeCount:\\s*\\d+",
                    Pattern.DOTALL).matcher(html);
            while (lineMatcher.find()) {
                String lineName = clean(lineMatcher.group(1));
                if (lineName.isEmpty()) lineName = "线路" + (lineNames.size() + 1);
                // alists 是站点内部名，显示成线路1更直观
                if ("alists".equalsIgnoreCase(lineName)) lineName = "线路" + (lineNames.size() + 1);
                lineNames.add(lineName);
            }

            Matcher anchors = Pattern.compile(
                    "href=\"(/play/[A-Za-z0-9]+)\"[^>]*?data-line=\"(\\d+)\"[^>]*?data-episode=\"(\\d+)\"[^>]*?dataid=\"(\\d+)\"",
                    Pattern.DOTALL).matcher(html);
            Map<Integer, List<String[]>> grouped = new TreeMap<Integer, List<String[]>>();
            while (anchors.find()) {
                String href = anchors.group(1);
                int line = parseInt(anchors.group(2), 1);
                int episode = parseInt(anchors.group(3), 1);
                String dataId = anchors.group(4);
                String episodeSlug = href.substring(href.lastIndexOf('/') + 1);
                List<String[]> episodes = grouped.get(line);
                if (episodes == null) {
                    episodes = new ArrayList<String[]>();
                    grouped.put(line, episodes);
                }
                episodes.add(new String[]{String.valueOf(episode), episodeSlug, dataId});
            }
            if (grouped.isEmpty()) return error("暂无播放源");

            // TVBox 的多线路格式：from 用 $$$ 分隔，url 也按 $$$ 分组
            StringBuilder playFrom = new StringBuilder();
            StringBuilder playUrl = new StringBuilder();
            Set<String> usedNames = new HashSet<String>();
            for (Map.Entry<Integer, List<String[]>> entry : grouped.entrySet()) {
                int line = entry.getKey();
                List<String[]> episodes = entry.getValue();
                Collections.sort(episodes, new Comparator<String[]>() {
                    public int compare(String[] a, String[] b) {
                        return parseInt(a[0], 0) - parseInt(b[0], 0);
                    }
                });
                String lineName = line >= 1 && line <= lineNames.size() ? lineNames.get(line - 1) : "线路" + line;
                if (lineName.trim().isEmpty()) lineName = "线路" + line;
                if (!usedNames.add(lineName)) lineName = lineName + "-" + line;

                if (playFrom.length() > 0) {
                    playFrom.append("$$$");
                    playUrl.append("$$$");
                }
                playFrom.append(lineName);
                for (int i = 0; i < episodes.size(); i++) {
                    String[] episode = episodes.get(i);
                    if (i > 0) playUrl.append('#');
                    playUrl.append("第").append(episode[0]).append("集$")
                            .append(episode[1]).append('|').append(episode[2]);
                }
            }

            JSONObject item = new JSONObject();
            item.put("vod_id", slug);
            item.put("vod_name", name);
            item.put("vod_pic", pic);
            item.put("type_name", type);
            item.put("vod_year", year);
            item.put("vod_area", area);
            item.put("vod_actor", actor);
            item.put("vod_director", director);
            item.put("vod_content", content);
            item.put("vod_play_from", playFrom.toString());
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
            if (id == null || id.indexOf('|') < 0) return error("播放参数错误");
            String[] parts = id.split("\\|");
            if (parts.length < 2) return error("播放参数错误");
            String slug = parts[0];
            String dataId = parts[1];

            String pageUrl = host + "/play/" + slug;
            String html = fetch(pageUrl);

            String userlink = one(html, "userlink:'([^']+)'");
            if (userlink.isEmpty()) userlink = "0";
            long serverTime = parseLong(one(html, "id=\"nb-st\" content=\"(\\d+)\""), 0);
            long loadTime = System.currentTimeMillis();

            String playUrl = "";
            String lastError = "";
            String[] qualities = {"1080", "720", "480"};
            for (String quality : qualities) {
                long timestamp = serverTime > 0 ? System.currentTimeMillis() + (loadTime - serverTime) : System.currentTimeMillis();
                String sign = sign(slug, dataId + ":" + timestamp + ":" + slug);
                if (sign.isEmpty()) return error("签名失败");
                String token = encode(base64(xor(userlink)));

                String api = host + "/video/play?p=" + encode(dataId)
                        + "&v=" + encode(slug)
                        + "&q=" + quality
                        + "&s=" + sign
                        + "&t=" + timestamp
                        + "&k=" + token;
                try {
                    String body = fetch(api);
                    JSONObject root = new JSONObject(body);
                    if (root.optInt("code", 0) != 200) {
                        lastError = root.optString("message", "播放接口返回异常");
                        continue;
                    }
                    JSONObject data = root.optJSONObject("data");
                    JSONArray urls = data == null ? null : data.optJSONArray("quality_urls");
                    if (urls == null || urls.length() == 0) {
                        lastError = "播放地址为空";
                        continue;
                    }
                    int bestBitrate = -1;
                    boolean hasLocked = false;
                    for (int i = 0; i < urls.length(); i++) {
                        JSONObject item = urls.optJSONObject(i);
                        if (item == null) continue;
                        String url = item.optString("url");
                        if (url.isEmpty()) continue;
                        // 4K/VIP 清晰度会返回 locked=true、url="1" 这种占位，
                        // 直接交给播放器会报 ERROR_CODE_IO_FILE_NOT_FOUND
                        if (item.optBoolean("locked", false) || item.optBoolean("isvip", false)) {
                            hasLocked = true;
                            continue;
                        }
                        if (url.startsWith("/")) url = host + url;
                        if (!url.startsWith("http")) continue;
                        String mtype = item.optString("mtype");
                        if (!mtype.isEmpty() && !mtype.contains("m3u8")
                                && !mtype.contains("mp4") && !mtype.contains("flv")) continue;

                        int bitrate = item.optInt("bitrate", 0);
                        if (bitrate > bestBitrate) {
                            bestBitrate = bitrate;
                            playUrl = url;
                        }
                    }
                    if (!playUrl.isEmpty()) break;
                    if (hasLocked) lastError = "该清晰度需要会员";
                } catch (Throwable e) {
                    lastError = brief(e);
                }
            }
            if (playUrl.isEmpty()) return error("播放失败：" + lastError);

            JSONObject result = new JSONObject();
            result.put("parse", 0);
            result.put("url", playUrl);
            return result.toString();
        } catch (Throwable e) {
            return error("播放失败：" + brief(e));
        }
    }

    // ---------------------------------------------------------------- 筛选

    private void loadFilters(String html) {
        types.clear();
        areas.clear();
        years.clear();
        tags.clear();
        sorts.clear();
        parseOptions(html, "types", types);
        parseOptions(html, "areas", areas);
        parseOptions(html, "years", years);
        parseOptions(html, "tags", tags);
        Matcher sortMatcher = Pattern.compile(
                "href=\"\\?sort_by=([^&\"&]+)&(?:amp;)?order=([^\"&]+)\"[^>]*>\\s*([^<]+?)\\s*</a>",
                Pattern.DOTALL).matcher(html);
        Set<String> seen = new HashSet<String>();
        while (sortMatcher.find()) {
            String value = sortMatcher.group(1) + "," + sortMatcher.group(2);
            String label = clean(sortMatcher.group(3));
            if (label.isEmpty() || !seen.add(value)) continue;
            sorts.add(new String[]{value, label});
        }
    }

    private void parseOptions(String html, String key, List<String[]> target) {
        Matcher matcher = Pattern.compile(
                "href=\"\\?" + key + "=(\\d+)\"[^>]*>\\s*([^<]+?)\\s*</a>",
                Pattern.DOTALL).matcher(html);
        Set<String> seen = new HashSet<String>();
        while (matcher.find()) {
            String value = matcher.group(1);
            String label = clean(matcher.group(2));
            if (label.isEmpty() || !seen.add(value)) continue;
            target.add(new String[]{value, label});
        }
    }

    private JSONArray buildFilters() throws Exception {
        JSONArray filters = new JSONArray();
        filters.put(filter("type", "类型", types));
        filters.put(filter("area", "地区", areas));
        filters.put(filter("year", "年份", years));
        filters.put(filter("tag", "标签", tags));
        filters.put(filter("sort", "排序", sorts));
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

    // ---------------------------------------------------------------- 解析

    private JSONArray parseCards(String html, int limit) throws Exception {
        JSONArray list = new JSONArray();
        Set<String> seen = new HashSet<String>();
        Matcher matcher = Pattern.compile("<a href=\"/play/([A-Za-z0-9]+)\"[^>]*>(.*?)</a>", Pattern.DOTALL).matcher(html);
        while (matcher.find() && list.length() < limit) {
            String slug = matcher.group(1);
            if (!seen.add(slug)) continue;
            String block = matcher.group(2);
            String name = clean(one(block, "alt=\"([^\"]+)\""));
            if (name.isEmpty()) name = clean(one(block, "<h3[^>]*>(.*?)</h3>"));
            if (name.isEmpty()) continue;
            String pic = decodeHtml(one(block, "data-src=\"([^\"]+)\""));
            if (pic.isEmpty()) pic = decodeHtml(one(block, "<img[^>]+src=\"(https?://[^\"]+)\""));
            String remark = clean(one(block, "top-2 left-2[^>]*>\\s*(\\d{4})\\s*</div>"));

            JSONObject vod = new JSONObject();
            vod.put("vod_id", slug);
            vod.put("vod_name", name);
            vod.put("vod_pic", pic);
            vod.put("vod_remarks", remark);
            list.put(vod);
        }
        return list;
    }

    private HashMap<String, String> parseInfo(String html) {
        HashMap<String, String> info = new HashMap<String, String>();
        Matcher matcher = Pattern.compile(
                "<div class=\"col-span-1 text-gray-500\">([^<]+)</div>\\s*<div class=\"col-span-2 text-gray-300\">(.*?)</div>",
                Pattern.DOTALL).matcher(html);
        while (matcher.find()) {
            String key = clean(matcher.group(1));
            String value = clean(matcher.group(2));
            if (!key.isEmpty()) info.put(key, value);
        }
        return info;
    }

    private int maxPage(String html) {
        int max = 0;
        Matcher matcher = Pattern.compile("[?&](?:amp;)?page=(\\d+)").matcher(html);
        while (matcher.find()) {
            int page = parseInt(matcher.group(1), 0);
            if (page > max) max = page;
        }
        return max;
    }

    private String sign(String key, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(UTF8), "HmacSHA256"));
            byte[] bytes = mac.doFinal(message.getBytes(UTF8));
            String hex = hex(bytes);
            return hex.length() >= 32 ? hex.substring(0, 32) : hex;
        } catch (Throwable e) {
            return "";
        }
    }

    private byte[] xor(String text) {
        byte[] key = XOR_KEY.getBytes(UTF8);
        byte[] data = text.getBytes(UTF8);
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (data[i] ^ key[i % key.length]);
        }
        return data;
    }

    private static String base64(byte[] data) {
        final char[] table = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();
        StringBuilder builder = new StringBuilder((data.length + 2) / 3 * 4);
        for (int i = 0; i < data.length; i += 3) {
            int b0 = data[i] & 0xff;
            int b1 = i + 1 < data.length ? data[i + 1] & 0xff : 0;
            int b2 = i + 2 < data.length ? data[i + 2] & 0xff : 0;
            builder.append(table[b0 >> 2]);
            builder.append(table[((b0 & 0x03) << 4) | (b1 >> 4)]);
            builder.append(i + 1 < data.length ? table[((b1 & 0x0f) << 2) | (b2 >> 6)] : '=');
            builder.append(i + 2 < data.length ? table[b2 & 0x3f] : '=');
        }
        return builder.toString();
    }

    private static String hex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            String value = Integer.toHexString(b & 0xff);
            if (value.length() == 1) builder.append('0');
            builder.append(value);
        }
        return builder.toString();
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

    private void addQuery(List<String> query, String key, String value) throws Exception {
        if (value != null && !value.trim().isEmpty()) query.add(key + "=" + encode(value.trim()));
    }

    private static int parseInt(String value, int defaultValue) {
        try {
            return Integer.parseInt(value);
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private static long parseLong(String value, long defaultValue) {
        try {
            return Long.parseLong(value);
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
