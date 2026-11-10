package com.github.catvod.spider;

import com.github.catvod.crawler.Spider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
 * 聚合影视：把 10 个苹果CMS（MacCMS）采集源合成同一个站点。
 * <p>
 * 首页/分类走 360资源（直链、最快）；搜索和详情会并发请求全部 10 个源，
 * 每命中一个源就生成一条播放线路，因此一部剧在播放器里能切 10 条线路。
 * <p>
 * 采集接口统一为：
 * 列表 {api}?ac=videolist&t={type_id}&pg={page}
 * 搜索 {api}?ac=videolist&wd={关键词}
 * 详情 {api}?ac=detail&ids={vod_id}
 * <p>
 * 播放地址有两种形态，这里都处理：
 * 1) 直链，vod_play_url 里直接是 .m3u8/.mp4；
 * 2) share 页，形如 https://v.lzcdn31.com/share/xxxx，
 *    需要再请求一次，页面里 var main = "/xxxx/index.m3u8?sign=..." 才是真地址。
 * <p>
 * ext 配置：{"cookie":"...","timeout":6000,"sources":"360,dytt,ffzy"}
 */
public class MacAgg extends Spider {

    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
    private static final Charset UTF8 = Charset.forName("UTF-8");

    /** {站名, 接口, 是否启用} —— 顺序即线路顺序，按实测速度排 */
    private static final String[][] SITES = {
            {"360资源", "https://360zy.com/api.php/provide/vod/", "1", "0", ""},
            {"暴风资源", "https://bfzyapi.com/api.php/provide/vod/", "1", "0", ""},
            {"非凡资源", "https://cj.ffzyapi.com/api.php/provide/vod/", "1", "0", ""},
            {"量子资源", "https://cj.lziapi.com/api.php/provide/vod/", "1", "0", ""},
            {"电影天堂", "https://caiji.dyttzyapi.com/api.php/provide/vod/", "1", "0", ""},
            {"光速资源", "https://api.guangsuapi.com/api.php/provide/vod/", "1", "0", ""},
            {"魔都资源", "https://www.mdzyapi.com/api.php/provide/vod/", "1", "0", ""},
            {"闪电资源", "https://sdzyapi.com/api.php/provide/vod/", "1", "0", ""},
            {"无尽资源", "https://api.wujinapi.me/api.php/provide/vod/", "1", "0", ""},
            {"百度资源", "https://api.apibdzy.com/api.php/provide/vod/", "1", "0", ""},
            {"红牛资源", "https://yingshidaquan.top/api_proxy.php", "1", "1", "hongniu"},
            {"速播资源", "https://yingshidaquan.top/api_proxy.php", "1", "1", "subo"},
            {"金鹰资源", "https://yingshidaquan.top/api_proxy.php", "1", "1", "jinying"}
    };

    /** 首页/分类只走这一个源 */
    private static final int MAIN = 0;

    private String cookie = "";
    private long timeout = 8000;
    private String only = "";

    /** key -> [时间戳, 结果] */
    private final Map<String, Object[]> cache = new ConcurrentHashMap<String, Object[]>();
    private JSONArray cachedClasses;
    private long classesTime;

    @Override
    public void init(String extend) {
        if (extend == null) return;
        String ext = extend.trim();
        if (ext.isEmpty()) return;
        try {
            if (ext.startsWith("{")) {
                JSONObject object = new JSONObject(ext);
                if (object.has("cookie")) cookie = object.optString("cookie", "").trim();
                if (object.has("timeout")) timeout = object.optLong("timeout", 6000);
                if (object.has("sources")) only = object.optString("sources", "").trim();
            }
        } catch (Throwable ignored) {
        }
    }

    // ---------------------------------------------------------------- 首页 / 分类

    @Override
    public String homeContent(boolean filter) {
        try {
            warmup(SITES[MAIN][1]);
            JSONObject root = new JSONObject();
            root.put("class", classes());
            root.put("list", cards(fetchJson(SITES[MAIN][1] + "?ac=videolist&pg=1")));
            return root.toString();
        } catch (Throwable e) {
            return error("首页加载失败：" + brief(e));
        }
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        try {
            int page = parseInt(pg, 1);
            String type = tid == null ? "" : tid.trim();
            String url = SITES[MAIN][1] + "?ac=videolist"
                    + ("".equals(type) || "all".equals(type) ? "" : "&t=" + encode(type))
                    + "&pg=" + page;
            JSONObject data = fetchJson(url);
            JSONArray list = cards(data);
            int total = data.optInt("total", 0);
            int pageCount = data.optInt("pagecount", 0);
            if (pageCount < page) pageCount = page;
            if (pageCount == 0) pageCount = list.length() >= 20 ? page + 1 : page;

            JSONObject root = new JSONObject();
            root.put("page", page);
            root.put("pagecount", pageCount);
            root.put("limit", list.length());
            root.put("total", total > 0 ? total : pageCount * 20);
            root.put("list", list);
            return root.toString();
        } catch (Throwable e) {
            return error("列表加载失败：" + brief(e));
        }
    }

    /** 精选分类：只留最常看的 10 个，避免首页分类条拉太长；可用 ext 的 cats 覆盖 */
    private static final String[][] PICK_CATS = {
            {"all", "全部"},
            {"13", "国产剧"},
            {"16", "欧美剧"},
            {"15", "韩国剧"},
            {"31", "日本剧"},
            {"34", "大陆综艺"},
            {"38", "国产动漫"},
            {"40", "日韩动漫"},
            {"39", "欧美动漫"},
            {"6", "动作片"},
            {"7", "喜剧片"},
            {"46", "短剧"}
    };

    /** 分类用固定精选表，不再额外请求站点分类接口，首页少一次往返 */
    private JSONArray classes() throws Exception {
        if (cachedClasses != null) return cachedClasses;
        JSONArray result = new JSONArray();
        for (String[] item : PICK_CATS) {
            JSONObject cls = new JSONObject();
            cls.put("type_id", item[0]);
            cls.put("type_name", item[1]);
            result.put(cls);
        }
        cachedClasses = result;
        classesTime = System.currentTimeMillis();
        return result;
    }

    // ---------------------------------------------------------------- 搜索

    @Override
    public String searchContent(String key, boolean quick) {
        return searchContent(key, quick, "1");
    }

    @Override
    public String searchContent(String key, boolean quick, String pg) {
        try {
            String name = key == null ? "" : key.trim();
            if (name.isEmpty()) return error("关键词为空");
            Map<String, JSONObject> merged = new LinkedHashMap<String, JSONObject>();
            // 快速搜索只打前 5 个源，避免 TVBox 搜索页等待过久；完整详情仍会查全部源
            List<JSONObject> hits = searchAll(name, quick ? 3 : SITES.length, quick ? 3000L : 4500L);
            for (int i = 0; i < hits.size(); i++) {
                JSONObject hit = hits.get(i);
                String title = hit.optString("vod_name");
                String norm = normalize(title);
                if (norm.isEmpty()) continue;
                if (!merged.containsKey(norm)) {
                    JSONObject vod = new JSONObject();
                    vod.put("vod_id", "agg_" + title);
                    vod.put("vod_name", title);
                    vod.put("vod_pic", hit.optString("vod_pic"));
                    vod.put("vod_remarks", hit.optString("remarks"));
                    vod.put("vod_year", hit.optString("vod_year"));
                    merged.put(norm, vod);
                }
            }
            JSONArray list = new JSONArray();
            for (JSONObject vod : merged.values()) list.put(vod);

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

    // ---------------------------------------------------------------- 详情

    @Override
    public String detailContent(List<String> ids) {
        try {
            if (ids == null || ids.isEmpty()) return error("参数错误");
            String raw = ids.get(0).trim();
            String searchName;
            String title;
            // vod_id 统一为 agg_片名：TVBox 对 vod_id 里的 | 会当作分隔符，
            // 用竖线会导致搜索结果被宿主丢掉，这里改成下划线前缀。
            if (raw.startsWith("agg_") && raw.length() > 4) {
                title = raw.substring(4);
                searchName = title;
            } else {
                searchName = raw;
                title = raw;
            }

            List<JSONObject> hits = searchAll(searchName, SITES.length, 5000L);
            // 按配置里的网站顺序排线路，保证 360资源 这类最快的源永远排第一
            Collections.sort(hits, new Comparator<JSONObject>() {
                public int compare(JSONObject a, JSONObject b) {
                    return a.optInt("order", 99) - b.optInt("order", 99);
                }
            });
            List<String> lineNames = new ArrayList<String>();
            List<String> lineUrls = new ArrayList<String>();
            Set<String> usedLine = new HashSet<String>();

            for (int i = 0; i < hits.size(); i++) {
                JSONObject hit = hits.get(i);
                String siteName = hit.optString("site");
                String playUrl = hit.optString("play_url");
                if (playUrl.isEmpty()) continue;

                // 一个源可能带多组线路，优先取直链那组，其次取第一组可用，
                // 每个源只出一条线路，保证线路名就是站名。
                String[] groups = playUrl.split("\\$\\$\\$");
                String direct = null;
                String any = null;
                for (int g = 0; g < groups.length; g++) {
                    String body = cleanPlayUrl(groups[g]);
                    if (body.isEmpty()) continue;
                    if (any == null) any = body;
                    if (direct == null && (body.contains(".m3u8") || body.contains(".mp4"))) direct = body;
                }
                String body = direct != null ? direct : any;
                if (body == null) continue;

                String lineName = siteName;
                if (usedLine.contains(lineName)) {
                    int suffix = 2;
                    while (usedLine.contains(lineName + "(" + suffix + ")")) suffix++;
                    lineName = lineName + "(" + suffix + ")";
                }
                usedLine.add(lineName);
                lineNames.add(lineName);
                lineUrls.add(body);
            }

            JSONObject item = new JSONObject();
            item.put("vod_id", raw);
            item.put("vod_name", title);
            if (!hits.isEmpty()) {
                JSONObject first = hits.get(0);
                item.put("vod_pic", first.optString("vod_pic"));
                item.put("type_name", first.optString("type_name"));
                item.put("vod_year", first.optString("vod_year"));
                item.put("vod_area", first.optString("vod_area"));
                item.put("vod_actor", first.optString("vod_actor"));
                item.put("vod_director", first.optString("vod_director"));
                item.put("vod_content", first.optString("vod_content"));
            }
            if (lineNames.isEmpty()) {
                item.put("vod_play_from", "聚合");
                item.put("vod_play_url", "");
            } else {
                StringBuilder from = new StringBuilder();
                StringBuilder urls = new StringBuilder();
                for (int i = 0; i < lineNames.size(); i++) {
                    if (i > 0) {
                        from.append("$$$");
                        urls.append("$$$");
                    }
                    from.append(lineNames.get(i));
                    urls.append(lineUrls.get(i));
                }
                item.put("vod_play_from", from.toString());
                item.put("vod_play_url", urls.toString());
            }

            JSONArray list = new JSONArray();
            list.put(item);
            JSONObject root = new JSONObject();
            root.put("list", list);
            return root.toString();
        } catch (Throwable e) {
            return error("详情加载失败：" + brief(e));
        }
    }

    // ---------------------------------------------------------------- 播放

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        try {
            if (id == null) return error("播放参数错误");
            String url = id.trim();
            if (url.isEmpty()) return error("播放参数错误");
            url = url.replace("%23", "#").replace("%24", "$");

            // 已经是直链
            if (isDirect(url)) return play(url);

            // share / play 页，需要二次解析
            String direct = resolveShare(url);
            if (direct.isEmpty()) return error("播放地址解析失败");
            return play(direct);
        } catch (Throwable e) {
            return error("播放失败：" + brief(e));
        }
    }

    private String play(String url) throws Exception {
        JSONObject root = new JSONObject();
        root.put("parse", 0);
        root.put("url", url);
        root.put("header", "{\"User-Agent\":\"" + UA + "\"}");
        return root.toString();
    }

    private static boolean isDirect(String url) {
        String value = url.toLowerCase();
        return value.startsWith("http") && (value.contains(".m3u8") || value.contains(".mp4")
                || value.contains(".flv") || value.contains(".mkv"));
    }

    /**
     * share 页里真正的地址在这些变量里，取第一个能用的：
     * var main = "/20261004/xxx/index.m3u8?sign=...";
     * url = "/20261004/xxx/index.m3u8?sign=...";
     */
    private String resolveShare(String page) {
        try {
            Object[] cached = cache.get("s|" + page);
            long now = System.currentTimeMillis();
            if (cached != null && now - (Long) cached[0] < 300000L) return (String) cached[1];
            String html = fetch(page);
            String path = "";
            Matcher matcher = Pattern.compile("var\\s+main\\s*=\\s*[\"']([^\"']+)[\"']").matcher(html);
            if (matcher.find()) path = matcher.group(1);
            if (path.isEmpty()) {
                matcher = Pattern.compile("url\\s*=\\s*[\"']([^\"']+\\.m3u8[^\"']*)[\"']").matcher(html);
                if (matcher.find()) path = matcher.group(1);
            }
            if (path.isEmpty()) {
                matcher = Pattern.compile("(https?://[^\"'\\s<>\\\\]+\\.m3u8[^\"'\\s<>\\\\]*)").matcher(html);
                if (matcher.find()) return matcher.group(1).replace("&amp;", "&");
            }
            if (path.isEmpty()) return "";
            String result;
            if (path.startsWith("http")) {
                result = path;
            } else {
                URL parsed = new URL(page);
                String base = parsed.getProtocol() + "://" + parsed.getHost();
                result = path.startsWith("/") ? base + path : base + "/" + path;
            }
            cache.put("s|" + page, new Object[]{System.currentTimeMillis(), result});
            if (cache.size() > 400) cache.clear();
            return result;
        } catch (Throwable e) {
            return "";
        }
    }

    // ---------------------------------------------------------------- 并发搜索

    /**
     * 并发查询多个源，用「渐进收集」代替「按序等待」：
     * 谁先返回先收谁，到总预算时间就收工，慢站不会拖住整体。
     */
    private List<JSONObject> searchAll(final String key, int limit, long budget) throws Exception {
        final String wd = encode(key);
        int count = Math.min(limit, SITES.length);
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, count));
        List<Future<JSONObject>> futures = new ArrayList<Future<JSONObject>>();
        for (int i = 0; i < count; i++) {
            if (!enabled(SITES[i][0])) continue;
            final String[] site = SITES[i];
            final int order = i;
            futures.add(pool.submit(new Callable<JSONObject>() {
                public JSONObject call() {
                    try {
                        return searchOne(site, wd, key, order);
                    } catch (Throwable e) {
                        return null;
                    }
                }
            }));
        }
        List<JSONObject> results = new ArrayList<JSONObject>();
        boolean[] done = new boolean[futures.size()];
        int remaining = futures.size();
        long deadline = System.currentTimeMillis() + budget;
        while (remaining > 0 && System.currentTimeMillis() < deadline) {
            for (int i = 0; i < futures.size(); i++) {
                if (done[i] || !futures.get(i).isDone()) continue;
                done[i] = true;
                remaining--;
                try {
                    JSONObject result = futures.get(i).get(200, TimeUnit.MILLISECONDS);
                    if (result != null) results.add(result);
                } catch (Throwable ignored) {
                }
            }
            if (remaining > 0) {
                try {
                    Thread.sleep(60);
                } catch (InterruptedException ignored) {
                }
            }
        }
        pool.shutdownNow();
        return results;
    }

    /** 单个源搜索，返回命中的那一条（含 play_url） */
    private JSONObject searchOne(String[] site, String wd, String key, int order) throws Exception {
        String url;
        if (site.length > 3 && "1".equals(site[3])) {
            // 代理型源：{api}?do=search&wd=xx&source=yy
            url = site[1] + "?do=search&wd=" + wd + "&source=" + site[4];
        } else {
            url = site[1] + "?ac=videolist&wd=" + wd;
        }
        JSONObject data = fetchJson(url);
        JSONArray list = data.optJSONArray("list");
        if (list == null || list.length() == 0) return null;

        JSONObject best = null;
        String target = normalize(key);
        for (int i = 0; i < list.length(); i++) {
            JSONObject item = list.optJSONObject(i);
            if (item == null) continue;
            String name = normalize(item.optString("vod_name"));
            if (name.equals(target)) {
                best = item;
                break;
            }
            if (best == null && (name.contains(target) || target.contains(name))) best = item;
        }
        if (best == null) return null;

        String playUrl = best.optString("vod_play_url");
        if (playUrl.isEmpty() && best.optInt("vod_id", 0) > 0) {
            try {
                JSONObject detail = fetchJson(site[1] + "?ac=detail&ids=" + best.optInt("vod_id", 0));
                JSONArray one = detail.optJSONArray("list");
                if (one != null && one.length() > 0) best = one.optJSONObject(0);
            } catch (Throwable ignored) {
            }
        }
        if (best == null) return null;
        playUrl = best.optString("vod_play_url");
        if (playUrl.isEmpty()) return null;

        JSONObject hit = new JSONObject();
        hit.put("order", order);
        hit.put("site", site[0]);
        hit.put("vod_name", best.optString("vod_name"));
        hit.put("vod_id", best.optInt("vod_id", 0));
        hit.put("vod_pic", best.optString("vod_pic"));
        hit.put("vod_year", best.optString("vod_year"));
        hit.put("vod_area", best.optString("vod_area"));
        hit.put("vod_actor", best.optString("vod_actor"));
        hit.put("vod_director", best.optString("vod_director"));
        hit.put("type_name", best.optString("type_name"));
        hit.put("vod_content", clean(best.optString("vod_content")));
        hit.put("remarks", best.optString("vod_remarks"));
        hit.put("play_from", best.optString("vod_play_from"));
        hit.put("play_url", playUrl);
        return hit;
    }

    private boolean enabled(String name) {
        if (only == null || only.isEmpty()) return true;
        return only.contains(name) || only.contains(name.substring(0, Math.min(2, name.length())));
    }

    // ---------------------------------------------------------------- 解析

    private JSONArray cards(JSONObject data) throws Exception {
        JSONArray list = new JSONArray();
        JSONArray raw = data == null ? null : data.optJSONArray("list");
        if (raw == null) return list;
        for (int i = 0; i < raw.length(); i++) {
            JSONObject item = raw.optJSONObject(i);
            if (item == null) continue;
            String name = item.optString("vod_name");
            if (name.isEmpty()) continue;
            JSONObject vod = new JSONObject();
            vod.put("vod_id", "agg_" + name);
            vod.put("vod_name", name);
            vod.put("vod_pic", item.optString("vod_pic"));
            vod.put("vod_remarks", item.optString("vod_remarks"));
            vod.put("vod_year", item.optString("vod_year"));
            list.put(vod);
        }
        return list;
    }

    /** 把 MacCMS 的 "第1集$url#第2集$url" 清洗成可安全放进 JSON 的格式 */
    private static String cleanPlayUrl(String group) {
        if (group == null) return "";
        StringBuilder builder = new StringBuilder();
        String[] episodes = group.split("#");
        for (int i = 0; i < episodes.length; i++) {
            String episode = episodes[i].trim();
            if (episode.isEmpty()) continue;
            String name;
            String url;
            int split = episode.indexOf('$');
            if (split > 0) {
                name = episode.substring(0, split).trim();
                url = episode.substring(split + 1).trim();
            } else {
                name = "第" + (i + 1) + "集";
                url = episode;
            }
            if (url.isEmpty() || !url.startsWith("http")) continue;
            url = url.replace("&amp;", "&").replace("$", "%24").replace("#", "%23");
            if (builder.length() > 0) builder.append('#');
            builder.append(name).append('$').append(url);
        }
        return builder.toString();
    }

    private static String normalize(String text) {
        if (text == null) return "";
        String value = text.toLowerCase();
        value = value.replaceAll("[\\(（\\[【].*?[\\)）\\]】]", "");
        value = value.replaceAll("[\\s\\-_·:：,，.。!！?？'\"“”‘’/\\\\|]+", "");
        value = value.replace("第", "第");
        return value.trim();
    }

    private static String clean(String text) {
        if (text == null) return "";
        String value = text.replaceAll("<[^>]+>", " ");
        value = value.replace("&amp;", "&").replace("&quot;", "\"").replace("&nbsp;", " ")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&#39;", "'");
        return value.replaceAll("\\s+", " ").trim();
    }

    // ---------------------------------------------------------------- HTTP

    /**
     * 带缓存的 JSON 请求。搜索/详情一次会打 10 个源，
     * 同一个片名短时间内会重复查询，缓存 60 秒足够。
     */
    private JSONObject fetchJson(String url) throws Exception {
        Object[] cached = cache.get("j|" + url);
        long now = System.currentTimeMillis();
        if (cached != null && now - (Long) cached[0] < 60000L) return (JSONObject) cached[1];
        JSONObject result = new JSONObject(fetch(url));
        cache.put("j|" + url, new Object[]{now, result});
        if (cache.size() > 400) cache.clear();
        return result;
    }

    private String fetch(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout((int) Math.min(timeout, 8000));
        connection.setReadTimeout((int) Math.min(timeout, 12000));
        connection.setInstanceFollowRedirects(true);
        connection.setRequestMethod("GET");
        connection.setRequestProperty("User-Agent", UA);
        connection.setRequestProperty("Accept", "application/json, text/plain, */*");
        connection.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
        connection.setRequestProperty("Accept-Encoding", "gzip");
        connection.setRequestProperty("Connection", "close");
        if (cookie != null && !cookie.isEmpty()) connection.setRequestProperty("Cookie", cookie);
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
        if (encoding != null && encoding.toLowerCase().contains("gzip")) input = new GZIPInputStream(input);
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

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value == null ? "" : value, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    /** DNS 预热，避免首次搜索卡在域名解析上 */
    private static void warmup(final String url) {
        try {
            if (url == null || url.isEmpty()) return;
            final String hostName = new java.net.URL(url).getHost();
            Thread thread = new Thread(new Runnable() {
                public void run() {
                    try {
                        java.net.InetAddress.getAllByName(hostName);
                    } catch (Throwable ignored) {
                    }
                }
            });
            thread.setDaemon(true);
            thread.start();
        } catch (Throwable ignored) {
        }
    }

    private static int parseInt(String value, int defaultValue) {
        try {
            return Integer.parseInt(value);
        } catch (Exception e) {
            return defaultValue;
        }
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
