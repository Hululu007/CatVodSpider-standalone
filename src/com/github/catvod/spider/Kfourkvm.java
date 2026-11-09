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
 * 4K影视（4kvm.net），显示名「4K」。
 * <p>
 * 与两个BT（bttwo.life）同源，都是 nbmovie 模板：
 * 详情页带 {@code id="nb-st"} 服务器时间与 {@code userlink:'...'}，
 * 播放地址由 WASM 生成 /video/play?p=&v=&q=&s=&t=&k=。
 * <p>
 * 列表：/filter?classify=1&types=&areas=&years=&tags=&sort_by=&order=&page=
 * 详情：/play/{slug}，集数以 href + data-line + data-episode + dataid 给出
 * 搜索：/search?q=关键词
 * 播放：/video/play，sign = HMAC-SHA256(slug, "dataid:t:slug")[:32]，
 *      k = base64(XOR(userlink, "nbmovie2024secretkey"))。
 * <p>
 * 清晰度：服务端把「4K / 1080p」标成 isvip=true、locked=true、url="1"，
 * 只有带 VIP 登录态（Cookie）的请求才会下发真实直链；
 * 另外 q=4k 会被服务端以「仅支持客户端」挡掉（403），
 * 所以这里统一用 q=1080 拉全量 quality_urls，再按 bitrate 从高到低挑。
 * <p>
 * ext 配置：
 * {"host":"https://www.4kvm.net","cookie":"laravel_session=...","quality":"4k"}
 * quality 可选 auto / 4k / 1080 / 720，默认 auto（取最高可用档）。
 */
public class Kfourkvm extends Spider {

    private static final String DEFAULT_HOST = "https://www.4kvm.net";
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
    private static final Charset UTF8 = Charset.forName("UTF-8");
    /** nbmovie 模板固定密钥，play_key = base64(XOR(userlink, 该串)) */
    private static final String XOR_KEY = "nbmovie2024secretkey";
    private static final int PAGE_SIZE = 90;

    private static final String[][] CATEGORIES = {
            {"1", "电影"},
            {"2", "电视剧"},
            {"3", "动漫"}
    };

    private String host = DEFAULT_HOST;
    /** VIP 登录态，形如 laravel_session=xxx; XSRF-TOKEN=yyy */
    private String cookie = "";
    /** auto / 4k / 1080 / 720 */
    private String quality = "auto";

    /**
     * 页面级缓存。4kvm 的详情页有 100KB 左右、单次要 2~3 秒，
     * 而 playerContent 和 detailContent 都需要同一份页面，
     * 不缓存就会出现「进详情 2s、点播放又 2s」的重复等待。
     * key 为 slug，value 为 [时间戳, 页面 HTML, userlink, 服务器时间戳]。
     */
    private final java.util.Map<String, Object[]> pageCache = new java.util.concurrent.ConcurrentHashMap<String, Object[]>();
    /** 播放地址缓存，key 为 epId|dataId */
    private final java.util.Map<String, Object[]> playCache = new java.util.concurrent.ConcurrentHashMap<String, Object[]>();
    /** 搜索结果缓存 */
    private final java.util.Map<String, Object[]> searchCache = new java.util.concurrent.ConcurrentHashMap<String, Object[]>();
    private static final long PAGE_TTL = 600000L;
    private static final long PLAY_TTL = 600000L;
    private static final long SEARCH_TTL = 180000L;

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
                if (object.has("cookie")) cookie = object.optString("cookie", "").trim();
                if (object.has("quality")) {
                    String value = object.optString("quality", "").trim().toLowerCase();
                    if (!value.isEmpty()) quality = value;
                }
            }
        } catch (Throwable ignored) {
        }
    }

    // ---------------------------------------------------------------- 首页 / 分类

    @Override
    public String homeContent(boolean filter) {
        try {
            warmup(host);
            String html = fetch(host + "/filter?classify=1");
            loadFilters(html);

            JSONArray classes = new JSONArray();
            for (String[] category : CATEGORIES) classes.put(cls(category[0], category[1]));

            JSONObject root = new JSONObject();
            root.put("class", classes);
            if (filter) {
                JSONArray filterArray = buildFilters();
                JSONObject filters = new JSONObject();
                for (String[] category : CATEGORIES) filters.put(category[0], filterArray);
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
            String type = tid == null || tid.trim().isEmpty() ? "1" : tid.trim();
            List<String> query = new ArrayList<String>();
            query.add("classify=" + encode(type));
            if (extend != null) {
                addQuery(query, "types", extend.get("type"));
                addQuery(query, "areas", extend.get("area"));
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

            String html = fetch(host + "/filter?" + join(query, "&"));
            JSONArray list = parseCards(html, PAGE_SIZE);
            int pageCount = maxPage(html);
            if (pageCount < page) pageCount = page;
            // 站点每页实际只渲染 30 部（其余是悬停预览的重复节点），
            // 所以以「本页是否取满」作为翻页依据，避免翻到大量空白页。
            if (list.length() >= 20) {
                pageCount = Math.min(pageCount, page + 1);
            } else {
                pageCount = page;
            }

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
        String cacheKey = "s|" + (key == null ? "" : key);
        Object[] cached = searchCache.get(cacheKey);
        long now = System.currentTimeMillis();
        if (cached != null && now - (Long) cached[0] < SEARCH_TTL) return (String) cached[1];
        try {
            String html = fetch(host + "/search?q=" + encode(key == null ? "" : key));
            JSONArray list = parseCards(html, 60);
            JSONObject root = new JSONObject();
            root.put("page", 1);
            root.put("pagecount", 1);
            root.put("limit", list.length());
            root.put("total", list.length());
            root.put("list", list);
            String result = root.toString();
            if (searchCache.size() > 200) searchCache.clear();
            searchCache.put(cacheKey, new Object[]{now, result});
            return result;
        } catch (Throwable e) {
            return error("搜索失败：" + brief(e));
        }
    }

    // ---------------------------------------------------------------- 详情

    @Override
    public String detailContent(List<String> ids) {
        try {
            if (ids == null || ids.isEmpty()) return error("参数错误");
            String slug = ids.get(0).trim();
            if (slug.contains("|")) slug = slug.substring(0, slug.indexOf('|'));
            String html = (String) page(slug)[1];

            String name = clean(one(html, "<h1[^>]*>(.*?)</h1>"));
            if (name.isEmpty()) {
                name = clean(one(html, "<meta property=\"og:title\" content=\"([^\"]+)\""));
                int dash = name.indexOf(" - ");
                if (dash > 0) name = name.substring(0, dash).trim();
            }
            String pic = decodeHtml(one(html, "<meta property=\"og:image\" content=\"([^\"]+)\""));
            String content = decodeHtml(one(html, "<meta property=\"og:description\" content=\"([^\"]+)\""));

            HashMap<String, String> info = parseInfo(html);
            String type = info.containsKey("类型") ? info.get("类型") : "";
            String area = info.containsKey("地区") ? info.get("地区") : "";
            String actor = info.containsKey("主演") ? info.get("主演") : "";
            String director = info.containsKey("导演") ? info.get("导演") : "";
            String year = "";
            String release = info.containsKey("上映") ? info.get("上映") : "";
            if (release.isEmpty() && info.containsKey("首播")) release = info.get("首播");
            Matcher yearMatcher = Pattern.compile("(\\d{4})").matcher(release);
            if (yearMatcher.find()) year = yearMatcher.group(1);

            // 线路名：episodeManager(..., [{lineName:'alists', episodeCount:N}, ...])
            List<String> lineNames = new ArrayList<String>();
            Matcher lineMatcher = Pattern.compile(
                    "lineName:\\s*'([^']*)'\\s*,\\s*episodeCount:\\s*\\d+",
                    Pattern.DOTALL).matcher(html);
            while (lineMatcher.find()) {
                String lineName = clean(lineMatcher.group(1));
                if (lineName.isEmpty() || "alists".equalsIgnoreCase(lineName)) {
                    lineName = "线路" + (lineNames.size() + 1);
                }
                lineNames.add(lineName);
            }

            // 集数：<a href="/play/xxx" ... data-line="1" data-episode="1" dataid="41891">
            Matcher anchors = Pattern.compile(
                    "href=\"(/play/[A-Za-z0-9]+)\"[^>]*?data-line=\"(\\d+)\"[^>]*?data-episode=\"(\\d+)\"[^>]*?dataid=\"(\\d+)\"",
                    Pattern.DOTALL).matcher(html);
            Map<Integer, List<String[]>> grouped = new TreeMap<Integer, List<String[]>>();
            while (anchors.find()) {
                String href = anchors.group(1);
                int line = parseInt(anchors.group(2), 1);
                int episode = parseInt(anchors.group(3), 1);
                String dataId = anchors.group(4);
                String epSlug = href.substring(href.lastIndexOf('/') + 1);
                List<String[]> episodes = grouped.get(line);
                if (episodes == null) {
                    episodes = new ArrayList<String[]>();
                    grouped.put(line, episodes);
                }
                episodes.add(new String[]{String.valueOf(episode), epSlug, dataId});
            }
            if (grouped.isEmpty()) return error("暂无播放源");

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

    // ---------------------------------------------------------------- 播放

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        try {
            if (id == null || id.indexOf('|') < 0) return error("播放参数错误");
            String[] parts = id.split("\\|");
            if (parts.length < 2) return error("播放参数错误");
            String epSlug = parts[0];
            String dataId = parts[1];

            // 同一集短时间内重复点播放，直接返回上次结果，避免再打一次接口
            String playKey = epSlug + "|" + dataId;
            Object[] hit = playCache.get(playKey);
            long now = System.currentTimeMillis();
            if (hit != null && now - (Long) hit[0] < PLAY_TTL) return (String) hit[1];

            // 页面走缓存：详情页刚拉过就直接复用，不再整页重拉
            Object[] page = page(epSlug);
            String userlink = (String) page[2];
            long serverTime = (Long) page[3];
            long loadTime = System.currentTimeMillis();

            String token = encode(base64(xor(userlink)));
            String[] wanted = qualities();

            JSONObject best = null;
            JSONArray all = new JSONArray();
            String lastError = "";

            for (int i = 0; i < wanted.length; i++) {
                JSONObject data = requestPlay(epSlug, dataId, wanted[i], token, serverTime, loadTime);
                if (data == null) continue;
                JSONArray urls = data.optJSONArray("quality_urls");
                if (urls == null || urls.length() == 0) continue;

                List<JSONObject> playable = new ArrayList<JSONObject>();
                boolean hasLocked = false;
                for (int k = 0; k < urls.length(); k++) {
                    JSONObject item = urls.optJSONObject(k);
                    if (item == null) continue;
                    String url = item.optString("url");
                    boolean locked = item.optBoolean("locked", false) || item.optBoolean("isvip", false);
                    if (locked || url.isEmpty() || "1".equals(url)) {
                        hasLocked = true;
                        if (!url.isEmpty() && !"1".equals(url)) lastError = "该清晰度需要会员";
                        continue;
                    }
                    String mtype = item.optString("mtype");
                    if (!mtype.isEmpty() && !mtype.contains("m3u8")
                            && !mtype.contains("mp4") && !mtype.contains("flv")) continue;
                    if (url.startsWith("/")) url = host + url;
                    if (!url.startsWith("http")) continue;
                    item.put("url", url);
                    playable.add(item);
                }

                if (!playable.isEmpty()) {
                    Collections.sort(playable, new Comparator<JSONObject>() {
                        public int compare(JSONObject a, JSONObject b) {
                            return rank(b) - rank(a);
                        }
                    });
                    for (int k = 0; k < playable.size(); k++) all.put(playable.get(k));
                    best = playable.get(0);
                    break;
                }
                if (hasLocked) lastError = lastError.isEmpty() ? "该清晰度需要会员，请在 ext 里配置 VIP 账号的 cookie" : lastError;
            }

            if (best == null) return error("播放失败：" + (lastError.isEmpty() ? "无可用地址" : lastError));

            JSONObject root = new JSONObject();
            root.put("parse", 0);
            root.put("url", best.optString("url"));
            JSONArray urls = new JSONArray();
            Set<String> seen = new HashSet<String>();
            for (int i = 0; i < all.length(); i++) {
                JSONObject item = all.optJSONObject(i);
                if (item == null) continue;
                String url = item.optString("url");
                if (!seen.add(url)) continue;
                JSONObject entry = new JSONObject();
                entry.put("name", label(item));
                entry.put("url", url);
                urls.put(entry);
            }
            if (urls.length() > 0) root.put("urls", urls);
            root.put("header", "{\"User-Agent\":\"" + UA + "\",\"Referer\":\"" + host + "/\"}");
            String result = root.toString();
            if (playCache.size() > 200) playCache.clear();
            playCache.put(playKey, new Object[]{System.currentTimeMillis(), result});
            // 提前把 m3u8 域名的 DNS 和 TCP 管道打通，并预取首个分片，
            // 播放器接手时首帧就不用再干等 CDN
            warmup(best.optString("url"));
            prefetch(best.optString("url"));
            return result;
        } catch (Throwable e) {
            return error("播放失败：" + brief(e));
        }
    }

    /**
     * 详情页按 slug 缓存，detailContent 与 playerContent 共用一份。
     * 返回 [时间戳, html, userlink, 服务器时间戳]。
     */
    private Object[] page(String slug) throws Exception {
        Object[] cached = pageCache.get(slug);
        long now = System.currentTimeMillis();
        if (cached != null && now - (Long) cached[0] < PAGE_TTL) return cached;
        String html = fetch(host + "/play/" + slug);
        String userlink = one(html, "userlink:'([^']+)'");
        if (userlink.isEmpty()) userlink = "0";
        long serverTime = parseLong(one(html, "id=\"nb-st\" content=\"(\\d+)\""), 0);
        Object[] entry = new Object[]{now, html, userlink, serverTime};
        if (pageCache.size() > 100) pageCache.clear();
        pageCache.put(slug, entry);
        return entry;
    }

    /**
     * 后台预取：拉一次 m3u8 解析出首分片，把首分片预下载一遍。
     * 播放器真正开播时，CDN 边缘节点已经有这份数据，首帧等待明显缩短。
     */
    private static void prefetch(final String m3u8) {
        if (m3u8 == null || m3u8.isEmpty()) return;
        Thread thread = new Thread(new Runnable() {
            public void run() {
                try {
                    String body = readUrl(m3u8);
                    if (body == null || body.isEmpty()) return;
                    String seg = firstSegment(body);
                    if (seg.isEmpty()) return;
                    if (seg.endsWith(".m3u8")) {
                        // master playlist 再下钻一层
                        String sub = seg.startsWith("http") ? seg : resolve(m3u8, seg);
                        String inner = readUrl(sub);
                        if (inner == null) return;
                        String seg2 = firstSegment(inner);
                        if (seg2.isEmpty()) return;
                        seg = seg2.startsWith("http") ? seg2 : resolve(sub, seg2);
                    } else if (!seg.startsWith("http")) {
                        seg = resolve(m3u8, seg);
                    }
                    download(seg);
                } catch (Throwable ignored) {
                }
            }
        });
        thread.setDaemon(true);
        thread.start();
    }

    private static String firstSegment(String body) {
        String found = "";
        for (String line : body.split("\n")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            found = line;
        }
        return found;
    }

    private static String resolve(String base, String relative) {
        int slash = base.lastIndexOf('/');
        return (slash > 0 ? base.substring(0, slash + 1) : base + "/") + relative;
    }

    private static String readUrl(String url) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(6000);
            connection.setReadTimeout(8000);
            connection.setRequestProperty("User-Agent", UA);
            connection.setRequestProperty("Accept-Encoding", "identity");
            if (connection instanceof HttpsURLConnection) {
                HttpsURLConnection https = (HttpsURLConnection) connection;
                https.setSSLSocketFactory(trustAll());
                https.setHostnameVerifier(new HostnameVerifier() {
                    public boolean verify(String hostname, SSLSession session) {
                        return true;
                    }
                });
            }
            InputStream input = connection.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = input.read(buffer)) != -1) {
                out.write(buffer, 0, n);
                if (out.size() > 2 * 1024 * 1024) break;
            }
            input.close();
            return new String(out.toByteArray(), UTF8);
        } catch (Throwable e) {
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static void download(String url) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(6000);
            connection.setReadTimeout(10000);
            connection.setRequestProperty("User-Agent", UA);
            connection.setRequestProperty("Range", "bytes=0-524287");
            if (connection instanceof HttpsURLConnection) {
                HttpsURLConnection https = (HttpsURLConnection) connection;
                https.setSSLSocketFactory(trustAll());
                https.setHostnameVerifier(new HostnameVerifier() {
                    public boolean verify(String hostname, SSLSession session) {
                        return true;
                    }
                });
            }
            InputStream input = connection.getInputStream();
            byte[] buffer = new byte[32768];
            int total = 0;
            int n;
            while ((n = input.read(buffer)) != -1) {
                total += n;
                if (total > 512 * 1024) break;
            }
            input.close();
        } catch (Throwable ignored) {
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    /** DNS 预热：该站 CDN 首次解析要 2 秒，异步解析一次，播放器接手就不用等 */
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

    /**
     * 请求 /video/play。服务端对 q=4k 会返回 403（仅客户端），
     * 因此 4K 档必须通过普通 q 值的全量列表来拿，这里逐个 q 试探。
     */
    private JSONObject requestPlay(String slug, String dataId, String q, String token,
                                   long serverTime, long loadTime) throws Exception {
        long timestamp = serverTime > 0 ? System.currentTimeMillis() + (loadTime - serverTime) : System.currentTimeMillis();
        String sign = sign(slug, dataId + ":" + timestamp + ":" + slug);
        if (sign.isEmpty()) return null;
        String url = host + "/video/play?p=" + encode(dataId)
                + "&v=" + encode(slug)
                + "&q=" + q
                + "&s=" + sign
                + "&t=" + timestamp
                + "&k=" + token;
        try {
            JSONObject root = new JSONObject(fetch(url));
            if (root.optInt("code", 0) != 200) return null;
            return root.optJSONObject("data");
        } catch (Throwable e) {
            return null;
        }
    }

    /** 优先顺序：先试配置的档位，再补其余档位，最后用 1080 拉全量列表 */
    private String[] qualities() {
        List<String> list = new ArrayList<String>();
        if ("4k".equals(quality)) list.add("4k");
        else if ("1080".equals(quality)) list.add("1080");
        else if ("720".equals(quality)) list.add("720");
        for (String q : new String[]{"1080", "2160"}) {
            if (!list.contains(q)) list.add(q);
        }
        return list.toArray(new String[0]);
    }

    /** 排序权重：4K 最高，其次按 bitrate */
    private static int rank(JSONObject item) {
        String title = item.optString("title").toUpperCase();
        int bitrate = item.optInt("bitrate", 0);
        if (title.contains("4K") || title.contains("2160") || bitrate >= 2000000) return 4000;
        if (bitrate > 0) return bitrate;
        return 0;
    }

    private static String label(JSONObject item) {
        String title = item.optString("title");
        if (!title.isEmpty()) return title;
        int bitrate = item.optInt("bitrate", 0);
        return bitrate > 0 ? bitrate + "p" : "线路";
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
                "href=\"\\?[^\"]*?sort_by=([^&\"&]+)&(?:amp;)?order=([^\"&]+)\"[^>]*>\\s*([^<]+?)\\s*</a>",
                Pattern.DOTALL).matcher(html);
        Set<String> seen = new HashSet<String>();
        while (sortMatcher.find()) {
            String value = sortMatcher.group(1) + "," + sortMatcher.group(2);
            String label = clean(sortMatcher.group(3));
            if (label.isEmpty() || !seen.add(value)) continue;
            sorts.add(new String[]{value, label});
        }
    }

    /**
     * 本站在筛选链接里会把当前分类一起带上：
     * href="?classify=1&amp;areas=100"，所以不能只匹配 href="?areas="。
     */
    private void parseOptions(String html, String key, List<String[]> target) {
        Matcher matcher = Pattern.compile(
                "href=\"\\?[^\"]*?(?:&amp;|&|\\?)" + key + "=(\\d+)\"[^>]*>\\s*([^<]+?)\\s*</a>",
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
        if (!types.isEmpty()) filters.put(filter("type", "类型", types));
        if (!areas.isEmpty()) filters.put(filter("area", "地区", areas));
        if (!years.isEmpty()) filters.put(filter("year", "年份", years));
        if (!tags.isEmpty()) filters.put(filter("tag", "标签", tags));
        if (!sorts.isEmpty()) filters.put(filter("sort", "排序", sorts));
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
        Matcher matcher = Pattern.compile(
                "<a href=\"/play/([A-Za-z0-9]+)\"\\s+data-vod=\"[A-Za-z0-9]*\"[^>]*>(.*?)</a>",
                Pattern.DOTALL).matcher(html);
        while (matcher.find() && list.length() < limit) {
            String slug = matcher.group(1);
            if (!seen.add(slug)) continue;
            String block = matcher.group(2);
            String name = clean(one(block, "alt=\"([^\"]+)\""));
            if (name.isEmpty()) name = clean(one(block, "<h3[^>]*>(.*?)</h3>"));
            if (name.isEmpty()) continue;
            String pic = decodeHtml(one(block, "data-src=\"([^\"]+)\""));
            if (pic.isEmpty()) pic = decodeHtml(one(block, "<img[^>]+src=\"(https?://[^\"]+)\""));
            // 卡片里不带年份/连载数，留空比填错更安全
            String remark = "";

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

    // ---------------------------------------------------------------- 签名

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
        connection.setRequestProperty("Accept-Encoding", "gzip");
        connection.setRequestProperty("Referer", host + "/");
        connection.setRequestProperty("Upgrade-Insecure-Requests", "1");
        connection.setRequestProperty("Connection", "close");
        if (cookie != null && !cookie.isEmpty()) {
            connection.setRequestProperty("Cookie", cookie);
        }
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
            input = new java.util.zip.GZIPInputStream(input);
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
        if (value == null) return "";
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
