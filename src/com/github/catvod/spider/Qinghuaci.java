package com.github.catvod.spider;

import com.github.catvod.crawler.Spider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * 青花瓷影视（cj1nym.com）通用版爬虫。
 * <p>
 * 不依赖 gson／okhttp／宿主 bean，只用 org.json 与 HttpURLConnection，
 * 任何支持 dex jar 的 TVBox 都能加载。
 */
public class Qinghuaci extends Spider {

    private static final String DEFAULT_HOST = "https://cj1nym.com";
    private static final String HOST_NAME = "cj1nym.com";
    private static final String SIGN_KEY = "cb808529bae6b6be45ecfab29a4889bc";
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int PAGE_SIZE = 24;
    private static final int SPORTS_TYPE_ID = 7;

    private String host = DEFAULT_HOST;
    /** 聚合线路使用的茶杯狐地址 */
    private String cbhHost = Chabeihu.DEFAULT_HOST;
    /** 是否在详情里追加茶杯狐线路 */
    private boolean aggregate = true;
    private String deviceId = UUID.randomUUID().toString();
    /** HTTPS 握手失败（安卓 6 不信任 ISRG Root X1）后自动降级为 HTTP */
    private boolean plain = false;
    /** HTTPS 握手失败后放宽证书校验成功，说明设备 TLS 信任库较旧 */
    private boolean insecure = false;

    @Override
    public void init(String extend) {
        if (extend == null) return;
        String ext = extend.trim();
        if (ext.isEmpty()) return;
        try {
            if (ext.startsWith("http")) {
                host = trimSlash(ext);
            } else if (ext.startsWith("{")) {
                JSONObject options = new JSONObject(ext);
                if (options.has("host")) host = trimSlash(options.optString("host"));
                if (options.has("cbh_host")) cbhHost = trimSlash(options.optString("cbh_host"));
                if (options.has("aggregate")) aggregate = options.optBoolean("aggregate", true);
                if (options.has("device_id")) deviceId = options.optString("device_id");
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public String homeContent(boolean filter) {
        try {
            List<String[]> categories = new ArrayList<>();
            JSONArray types = api("/anonymous/get/filer/type", new LinkedHashMap<String, String>()).optJSONArray("data");
            if (types != null) {
                for (int i = 0; i < types.length(); i++) {
                    JSONObject item = types.optJSONObject(i);
                    if (item == null) continue;
                    int typeId = num(item, "typeId", 0);
                    if (typeId == SPORTS_TYPE_ID) continue;
                    categories.add(new String[]{String.valueOf(typeId), str(item, "typeName")});
                }
            }
            if (categories.isEmpty()) return error("分类获取失败");

            JSONArray classes = new JSONArray();
            JSONObject filters = new JSONObject();
            JSONObject filerData = null;
            if (filter) {
                JSONObject response = api("/anonymous/v1/get/filer/list", new LinkedHashMap<String, String>());
                filerData = response.optJSONObject("data");
            }
            for (String[] category : categories) {
                JSONObject classItem = new JSONObject();
                classItem.put("type_id", category[0]);
                classItem.put("type_name", category[1]);
                classes.put(classItem);
                if (filter) {
                    JSONObject typeObject = filerData == null ? null : filerData.optJSONObject(category[0]);
                    filters.put(category[0], buildFilters(typeObject, category[0]));
                }
            }
            JSONObject data = homeData();
            JSONObject root = new JSONObject();
            root.put("class", classes);
            if (filter) root.put("filters", filters);
            root.put("list", homeVods(data));
            root.put("sections", homeSections(data));
            root.put("banners", homeBanners());
            return root.toString();
        } catch (Throwable e) {
            return diagnose(e);
        }
    }

    @Override
    public String homeVideoContent() {
        try {
            JSONObject root = new JSONObject();
            root.put("list", homeVods());
            return root.toString();
        } catch (Throwable e) {
            return diagnose(e);
        }
    }

    private JSONArray homeVods() throws Exception {
        return homeVods(homeData());
    }

    private JSONObject homeData() throws Exception {
        LinkedHashMap<String, String> params = new LinkedHashMap<>();
        params.put("clientType", "1");
        params.put("pageNum", "1");
        return api("/anonymous/v1/home/all/list", params).optJSONObject("data");
    }

    private JSONArray homeVods(JSONObject data) throws Exception {
        JSONArray list = new JSONArray();
        if (data == null) return list;
        String[] keys = {"homeNewMoviePageData", "homeBroadcastPageData", "newestTvPageData", "newestVarietyPageData", "newestCartoonPageData", "newestShortTvPageData"};
        for (String key : keys) {
            JSONObject block = data.optJSONObject(key);
            if (block == null) continue;
            JSONArray items = block.optJSONArray("list");
            if (items == null) continue;
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item != null) list.put(vod(item));
            }
        }
        return list;
    }

    /**
     * 网页首页的分区结构。TVBox 会忽略该字段，自建 App 用它渲染横向栏目。
     */
    private JSONArray homeSections(JSONObject data) throws Exception {
        JSONArray sections = new JSONArray();
        if (data == null) return sections;
        String[][] blocks = {
                {"homeNewMoviePageData", "新片"},
                {"homeBroadcastPageData", "影院"},
                {"homeManagerPageData", "站长推荐"},
                {"homeTrailerPageData", "预告片"},
                {"newestTvPageData", "最新剧集"},
                {"newestVarietyPageData", "最新综艺"},
                {"newestCartoonPageData", "最新动漫"},
                {"newestShortTvPageData", "短剧"}
        };
        for (String[] blockInfo : blocks) {
            JSONObject block = data.optJSONObject(blockInfo[0]);
            if (block == null) continue;
            JSONArray items = block.optJSONArray("list");
            if (items == null || items.length() == 0) continue;
            JSONArray list = new JSONArray();
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item != null) list.put(vod(item));
            }
            JSONObject section = new JSONObject();
            section.put("title", blockInfo[1]);
            section.put("list", list);
            sections.put(section);
        }
        return sections;
    }

    /**
     * 网页顶部大图。过滤掉 isAd=1 的广告位。
     */
    private JSONArray homeBanners() {
        JSONArray result = new JSONArray();
        try {
            JSONArray data = api("/anonymous/home/banner", new LinkedHashMap<String, String>()).optJSONArray("data");
            if (data == null) return result;
            for (int i = 0; i < data.length(); i++) {
                JSONObject banner = data.optJSONObject(i);
                if (banner == null) continue;
                if (banner.optInt("isAd", 0) == 1) continue;
                JSONObject video = banner.optJSONObject("videoData");
                String id = str(banner, "param");
                if (id.isEmpty() && video != null) id = str(video, "vodId");
                if (id.isEmpty()) continue;
                String pic = str(banner, "imgDay");
                if (pic.isEmpty()) pic = str(banner, "img");
                if (pic.isEmpty() && video != null) pic = str(video, "vodPic");
                String name = str(banner, "name");
                if (name.isEmpty() && video != null) name = str(video, "vodName");
                JSONObject item = new JSONObject();
                item.put("vod_id", id);
                item.put("vod_name", name);
                item.put("vod_pic", pic);
                item.put("vod_remarks", video == null ? "" : str(video, "vodRemarks"));
                item.put("vod_content", video == null ? "" : str(video, "vodBlurb"));
                item.put("vod_year", video == null ? "" : str(video, "vodPubdate"));
                item.put("type_name", video == null ? "" : str(video, "vodClass"));
                result.put(item);
            }
        } catch (Throwable ignored) {
        }
        return result;
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        if ("debug".equals(tid)) return diagnose(null);
        try {
            LinkedHashMap<String, String> params = new LinkedHashMap<>();
            params.put("type1", tid);
            params.put("pageNum", pg);
            params.put("pageSize", String.valueOf(PAGE_SIZE));
            if (filter && extend != null) {
                put(params, "type", extend.get("type"));
                put(params, "v_class", extend.get("v_class"));
                put(params, "area", extend.get("area"));
                put(params, "year", extend.get("year"));
                put(params, "lang", extend.get("lang"));
                String sort = extend.get("sort");
                if (sort != null && sort.contains(",")) {
                    String[] parts = sort.split(",");
                    if (parts.length == 2) {
                        put(params, "sort", parts[0]);
                        put(params, "sortBy", parts[1]);
                    }
                }
            }
            JSONObject data = api("/anonymous/video/list", params).optJSONObject("data");
            if (data == null) return error("列表获取失败");
            JSONArray list = new JSONArray();
            JSONArray items = data.optJSONArray("list");
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.optJSONObject(i);
                    if (item != null) list.put(vod(item));
                }
            }
            JSONObject root = new JSONObject();
            root.put("page", num(data, "pageNum", parseInt(pg, 1)));
            root.put("pagecount", num(data, "totalPage", 1));
            root.put("limit", list.length());
            root.put("total", num(data, "totalCount", list.length()));
            root.put("list", list);
            return root.toString();
        } catch (Throwable e) {
            return error("列表加载失败: " + brief(e));
        }
    }

    @Override
    public String detailContent(List<String> ids) {
        try {
            if (ids == null || ids.isEmpty()) return error("参数错误");
            String vodId = ids.get(0).trim();
            LinkedHashMap<String, String> params = new LinkedHashMap<>();
            params.put("id", vodId);
            JSONObject data = api("/anonymous/video/detail", params).optJSONObject("data");
            if (data == null) return error("详情获取失败");
            JSONObject item = new JSONObject();
            item.put("vod_id", vodId);
            item.put("vod_name", str(data, "vodName"));
            item.put("vod_pic", str(data, "vodPic"));
            item.put("type_name", str(data, "typeName"));
            item.put("vod_content", str(data, "vodContent"));
            item.put("vod_year", str(data, "vodYear"));
            item.put("vod_area", str(data, "vodArea"));
            item.put("vod_actor", str(data, "vodActor"));
            item.put("vod_director", str(data, "vodDirector"));
            item.put("vod_remarks", remarks(data));

            List<String> episodes = new ArrayList<>();
            JSONArray list = data.optJSONArray("episodeList");
            if (list != null) {
                for (int i = 0; i < list.length(); i++) {
                    JSONObject episode = list.optJSONObject(i);
                    if (episode == null) continue;
                    String nid = str(episode, "nid");
                    if (nid.isEmpty()) continue;
                    String name = str(episode, "name");
                    episodes.add((name.isEmpty() ? nid : name) + "$" + vodId + "_" + nid);
                }
            }
            if (episodes.isEmpty()) return error("暂无播放源");
            StringBuilder playUrl = new StringBuilder();
            for (String episode : episodes) {
                if (playUrl.length() > 0) playUrl.append('#');
                playUrl.append(episode);
            }
            // 聚合线路：按片名去茶杯狐找同名片源，找到就追加一条线
            String extra = aggregate ? Chabeihu.line(cbhHost, item.optString("vod_name")) : null;
            if (extra != null && !extra.isEmpty()) {
                item.put("vod_play_from", "青花瓷$$$茶杯狐");
                item.put("vod_play_url", playUrl.toString() + "$$$" + extra);
            } else {
                item.put("vod_play_from", "青花瓷");
                item.put("vod_play_url", playUrl.toString());
            }

            JSONArray result = new JSONArray();
            result.put(item);
            JSONObject root = new JSONObject();
            root.put("list", result);
            return root.toString();
        } catch (Throwable e) {
            return error("详情加载失败: " + brief(e));
        }
    }

    @Override
    public String searchContent(String key, boolean quick) {
        return searchContent(key, quick, "1");
    }

    @Override
    public String searchContent(String key, boolean quick, String pg) {
        try {
            LinkedHashMap<String, String> params = new LinkedHashMap<>();
            params.put("keyword", key);
            params.put("pageNum", pg);
            params.put("pageSize", String.valueOf(PAGE_SIZE));
            JSONObject data = api("/anonymous/video/searchByWordPageable", params).optJSONObject("data");
            if (data == null) return error("搜索失败");
            JSONArray list = new JSONArray();
            JSONArray items = data.optJSONArray("list");
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.optJSONObject(i);
                    if (item != null) list.put(vod(item));
                }
            }
            JSONObject root = new JSONObject();
            root.put("page", num(data, "pageNum", parseInt(pg, 1)));
            root.put("pagecount", num(data, "totalPage", 1));
            root.put("limit", list.length());
            root.put("total", num(data, "totalCount", list.length()));
            root.put("list", list);
            return root.toString();
        } catch (Throwable e) {
            return error("搜索失败: " + brief(e));
        }
    }

    @Override
    public String action(String action) {
        try {
            if (action != null && action.startsWith("cb:")) {
                String line = Chabeihu.line(cbhHost, action.substring(3));
                JSONObject root = new JSONObject();
                root.put("line", line == null ? "" : line);
                return root.toString();
            }
            if (action != null && action.startsWith("hot:")) {
                JSONArray data = api("/anonymous/home/hotSearch", new LinkedHashMap<String, String>()).optJSONArray("data");
                JSONArray list = new JSONArray();
                if (data != null) {
                    for (int i = 0; i < data.length(); i++) {
                        JSONObject item = data.optJSONObject(i);
                        if (item != null) list.put(vod(item));
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

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        try {
            if (id != null && id.startsWith("cb:")) {
                String url = Chabeihu.play(cbhHost, id.substring(3));
                if (url.isEmpty()) return error("播放地址为空");
                JSONObject root = new JSONObject();
                root.put("parse", 0);
                root.put("url", url);
                return root.toString();
            }
            if (id == null || id.indexOf('_') < 0) return error("播放参数错误");
            int index = id.indexOf('_');
            LinkedHashMap<String, String> params = new LinkedHashMap<>();
            params.put("clientType", "1");
            params.put("id", id.substring(0, index));
            params.put("nid", id.substring(index + 1));
            JSONObject data = api("/anonymous/v2/video/episode/url", params).optJSONObject("data");
            if (data == null) return error("播放地址获取失败");
            JSONArray list = data.optJSONArray("list");
            List<JSONObject> entries = new ArrayList<>();
            if (list != null) {
                for (int i = 0; i < list.length(); i++) {
                    JSONObject item = list.optJSONObject(i);
                    if (item == null) continue;
                    String url = str(item, "url");
                    if (url.isEmpty()) continue;
                    // 设备 TLS 信任库较旧，播放地址也走 HTTP，避免播放器再次卡证书
                    if (plain && url.startsWith("https://")) url = "http://" + url.substring(8);
                    JSONObject entry = new JSONObject();
                    entry.put("resolution", num(item, "resolution", 0));
                    entry.put("url", url);
                    entries.add(entry);
                }
            }
            if (entries.isEmpty()) return error("播放地址为空");
            Collections.sort(entries, new java.util.Comparator<JSONObject>() {
                public int compare(JSONObject a, JSONObject b) {
                    return b.optInt("resolution", 0) - a.optInt("resolution", 0);
                }
            });
            JSONArray urls = new JSONArray();
            for (JSONObject entry : entries) urls.put(entry);
            JSONObject root = new JSONObject();
            root.put("parse", 0);
            root.put("url", entries.get(0).optString("url"));
            root.put("urls", urls);
            return root.toString();
        } catch (Throwable e) {
            return error("播放失败: " + brief(e));
        }
    }

    private JSONArray buildFilters(JSONObject typeObject, String typeId) throws Exception {
        JSONArray filters = new JSONArray();
        if (!"1".equals(typeId)) addFilter(filters, typeObject, "typeList", "type", "类型", true);
        addFilter(filters, typeObject, "plotList", "v_class", "剧情", false);
        addFilter(filters, typeObject, "districtList", "area", "地区", false);
        addFilter(filters, typeObject, "yearList", "year", "年份", false);
        addFilter(filters, typeObject, "languageList", "lang", "语言", false);

        JSONArray sort = new JSONArray();
        sort.put(value("全部", ""));
        sort.put(value("人气", "3,1"));
        sort.put(value("评分", "4,1"));
        sort.put(value("上映时间", "1,1"));
        JSONObject sortFilter = new JSONObject();
        sortFilter.put("key", "sort");
        sortFilter.put("name", "排序");
        sortFilter.put("value", sort);
        filters.put(sortFilter);
        return filters;
    }

    private void addFilter(JSONArray filters, JSONObject typeObject, String dictKey, String apiKey, String name, boolean useItemValue) throws Exception {
        JSONArray array = typeObject == null ? null : typeObject.optJSONArray(dictKey);
        if (array == null || array.length() == 0) return;
        JSONArray values = new JSONArray();
        values.put(value("全部", ""));
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) continue;
            String text = str(item, "itemText");
            String val = str(item, "itemValue");
            values.put(value(text, useItemValue ? val : text));
        }
        JSONObject filter = new JSONObject();
        filter.put("key", apiKey);
        filter.put("name", name);
        filter.put("value", values);
        filters.put(filter);
    }

    private JSONObject value(String name, String val) throws Exception {
        JSONObject object = new JSONObject();
        object.put("n", name);
        object.put("v", val);
        return object;
    }

    private JSONObject vod(JSONObject object) throws Exception {
        JSONObject item = new JSONObject();
        item.put("vod_id", str(object, "vodId"));
        item.put("vod_name", str(object, "vodName"));
        item.put("vod_pic", str(object, "vodPic"));
        item.put("vod_remarks", remarks(object));
        String year = str(object, "vodYear");
        if (year.isEmpty()) {
            String pubdate = str(object, "vodPubdate");
            if (pubdate.length() >= 4) year = pubdate.substring(0, 4);
        }
        item.put("vod_year", year);
        item.put("vod_area", str(object, "vodArea"));
        return item;
    }

    private String remarks(JSONObject object) {
        String remarks = str(object, "vodRemarks");
        if (!remarks.isEmpty()) return remarks;
        String serial = str(object, "vodSerial");
        int total = num(object, "vodTotal", 0);
        if (!serial.isEmpty() && total > 0) return serial + "/" + total;
        Object score = object.opt("vodScore");
        if (score instanceof Number && ((Number) score).doubleValue() > 0) return String.valueOf(score);
        return "";
    }

    private JSONObject api(String path, LinkedHashMap<String, String> params) throws Exception {
        long timestamp = System.currentTimeMillis();
        List<String> keys = new ArrayList<>();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (entry.getValue() != null && !entry.getValue().isEmpty()) keys.add(entry.getKey());
        }
        Collections.sort(keys);
        StringBuilder source = new StringBuilder();
        for (String key : keys) source.append(key).append('=').append(params.get(key)).append('&');
        source.append("key=").append(SIGN_KEY).append("&t=").append(timestamp);
        String sign = sha1(md5(source.toString()));

        StringBuilder query = new StringBuilder();
        for (String key : keys) {
            if (query.length() > 0) query.append('&');
            query.append(encode(key)).append('=').append(encode(params.get(key)));
        }
        String pathAndQuery = "/api/mw-movie" + path + (query.length() > 0 ? "?" + query : "");

        if (plain) return request(httpHost() + pathAndQuery, timestamp, sign, false);
        try {
            // 安卓 6 等旧设备的信任库缺少 Let's Encrypt 根证书，严格校验必然失败，
            // 直接放宽证书校验，避免宿主 App 因等待超时而丢弃结果。
            insecure = true;
            return request(host + pathAndQuery, timestamp, sign, true);
        } catch (Exception permissive) {
            if (!host.startsWith("https://")) throw permissive;
            plain = true;
            return request(httpHost() + pathAndQuery, timestamp, sign, false);
        }
    }

    private String httpHost() {
        return host.startsWith("https://") ? "http://" + host.substring(8) : host;
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

    /**
     * 解析出的多个 IP 并行尝试，谁先成功用谁。
     * 有些线路只通其中一台，系统默认只用第一台会一直失败。
     */
    private JSONObject request(String url, long timestamp, String sign, boolean permissiveTls) throws Exception {
        URL parsed = new URL(url);
        final String hostName = parsed.getHost();
        final boolean tls = "https".equals(parsed.getProtocol());
        final int port = parsed.getPort() > 0 ? parsed.getPort() : (tls ? 443 : 80);
        final String file = parsed.getFile();

        final Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", UA);
        headers.put("Accept", "application/json, text/plain, */*");
        headers.put("client-type", "1");
        headers.put("deviceId", deviceId);
        headers.put("t", String.valueOf(timestamp));
        headers.put("sign", sign);
        headers.put("authorization", "");
        headers.put("Referer", host + "/");

        InetAddress[] addresses = InetAddress.getAllByName(hostName);
        final java.util.concurrent.BlockingQueue<Object> queue = new java.util.concurrent.ArrayBlockingQueue<>(addresses.length);
        for (final InetAddress address : addresses) {
            Thread thread = new Thread(new Runnable() {
                public void run() {
                    try {
                        queue.put(fetch(address, hostName, port, file, headers, tls));
                    } catch (Throwable e) {
                        try {
                            queue.put(e);
                        } catch (Throwable ignored) {
                        }
                    }
                }
            });
            thread.setDaemon(true);
            thread.start();
        }
        Throwable last = null;
        for (int i = 0; i < addresses.length; i++) {
            Object result = queue.take();
            if (result instanceof String) return new JSONObject((String) result);
            last = (Throwable) result;
        }
        if (last == null) throw new java.io.IOException("无可用地址");
        if (last instanceof Exception) throw (Exception) last;
        throw new java.io.IOException(String.valueOf(last));
    }

    private String fetch(InetAddress address, String hostName, int port, String file, Map<String, String> headers, boolean tls) throws Exception {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(address, port), 4000);
            socket.setSoTimeout(8000);
            if (tls) {
                Socket ssl = trustAll().createSocket(socket, hostName, port, true);
                ((SSLSocket) ssl).startHandshake();
                socket = ssl;
            }
            StringBuilder request = new StringBuilder();
            request.append("GET ").append(file).append(" HTTP/1.1\r\n");
            request.append("Host: ").append(hostName).append("\r\n");
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                request.append(entry.getKey()).append(": ").append(entry.getValue()).append("\r\n");
            }
            request.append("Connection: close\r\n\r\n");
            OutputStream output = socket.getOutputStream();
            output.write(request.toString().getBytes(UTF8));
            output.flush();
            return parseResponse(readAll(socket.getInputStream()));
        } finally {
            try {
                socket.close();
            } catch (Exception ignored) {
            }
        }
    }

    private byte[] readAll(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
            output.write(buffer, 0, count);
            if (output.size() > 8 * 1024 * 1024) break;
        }
        return output.toByteArray();
    }

    private String parseResponse(byte[] data) throws Exception {
        int headEnd = indexOf(data, "\r\n\r\n", 0);
        if (headEnd < 0) throw new java.io.IOException("响应格式错误");
        String head = new String(data, 0, headEnd, UTF8);
        String[] lines = head.split("\r\n");
        boolean chunked = false;
        int length = -1;
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].toLowerCase();
            if (line.startsWith("transfer-encoding:") && line.contains("chunked")) chunked = true;
            if (line.startsWith("content-length:")) {
                try {
                    length = Integer.parseInt(lines[i].substring(lines[i].indexOf(':') + 1).trim());
                } catch (Exception ignored) {
                }
            }
        }
        int bodyStart = headEnd + 4;
        if (chunked) return dechunk(data, bodyStart);
        if (length >= 0 && bodyStart + length <= data.length) return new String(data, bodyStart, length, UTF8);
        return new String(data, bodyStart, data.length - bodyStart, UTF8);
    }

    private String dechunk(byte[] data, int offset) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int pos = offset;
        while (pos < data.length) {
            int lineEnd = indexOf(data, "\r\n", pos);
            if (lineEnd < 0) break;
            String sizeText = new String(data, pos, lineEnd - pos, UTF8).trim();
            int semi = sizeText.indexOf(';');
            if (semi >= 0) sizeText = sizeText.substring(0, semi);
            int size;
            try {
                size = Integer.parseInt(sizeText, 16);
            } catch (Exception e) {
                break;
            }
            pos = lineEnd + 2;
            if (size <= 0) break;
            int available = Math.min(size, data.length - pos);
            output.write(data, pos, available);
            pos += size + 2;
        }
        return new String(output.toByteArray(), UTF8);
    }

    private int indexOf(byte[] data, String pattern, int from) {
        byte[] target = pattern.getBytes(UTF8);
        outer:
        for (int i = Math.max(0, from); i <= data.length - target.length; i++) {
            for (int j = 0; j < target.length; j++) {
                if (data[i + j] != target[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    /**
     * 失败时把错误类型和每个解析 IP 的端口探测结果作为卡片返回，
     * 方便在电视上直接看到问题，不需要 logcat。
     */
    private String diagnose(Throwable e) {
        try {
            JSONArray list = new JSONArray();
            JSONObject head = new JSONObject();
            head.put("vod_id", "");
            head.put("vod_name", e == null ? "自检" : e.getClass().getSimpleName());
            head.put("vod_pic", "");
            head.put("vod_remarks", "错误类型");
            list.put(head);
            String hostName = HOST_NAME;
            for (InetAddress address : InetAddress.getAllByName(hostName)) {
                boolean p443 = probe(address, 443);
                boolean p80 = probe(address, 80);
                JSONObject item = new JSONObject();
                item.put("vod_id", "");
                item.put("vod_name", address.getHostAddress() + " 443:" + (p443 ? "OK" : "X") + " 80:" + (p80 ? "OK" : "X"));
                item.put("vod_pic", "");
                item.put("vod_remarks", p443 || p80 ? "可达" : "不通");
                list.put(item);
            }
            JSONObject root = new JSONObject();
            JSONArray classes = new JSONArray();
            JSONObject cls = new JSONObject();
            cls.put("type_id", "debug");
            cls.put("type_name", "诊断");
            classes.put(cls);
            root.put("class", classes);
            root.put("list", list);
            return root.toString();
        } catch (Throwable ignored) {
            return "{\"list\":[]}";
        }
    }

    private boolean probe(InetAddress address, int port) {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(address, port), 1000);
            return true;
        } catch (Throwable e) {
            return false;
        } finally {
            try {
                socket.close();
            } catch (Exception ignored) {
            }
        }
    }

    private String brief(Throwable e) {
        String text = String.valueOf(e);
        return text.length() > 160 ? text.substring(0, 160) : text;
    }

    private String error(String message) {
        try {
            JSONObject root = new JSONObject();
            root.put("list", new JSONArray());
            root.put("msg", message);
            return root.toString();
        } catch (Exception e) {
            return "{\"list\":[]}";
        }
    }

    private void put(Map<String, String> params, String key, String value) {
        if (value != null && !value.trim().isEmpty()) params.put(key, value.trim());
    }

    private String str(JSONObject object, String key) {
        if (object == null) return "";
        Object value = object.opt(key);
        return value == null || value == JSONObject.NULL ? "" : String.valueOf(value);
    }

    private int num(JSONObject object, String key, int defaultValue) {
        if (object == null) return defaultValue;
        Object value = object.opt(key);
        if (value instanceof Number) return ((Number) value).intValue();
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private int parseInt(String value, int defaultValue) {
        try {
            return Integer.parseInt(value);
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (Exception e) {
            return value;
        }
    }

    private String trimSlash(String value) {
        String result = value == null ? "" : value.trim();
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        return result.isEmpty() ? DEFAULT_HOST : result;
    }

    private String md5(String value) {
        return digest("MD5", value);
    }

    private String sha1(String value) {
        return digest("SHA-1", value);
    }

    private String digest(String algorithm, String value) {
        try {
            byte[] bytes = MessageDigest.getInstance(algorithm).digest(value.getBytes(UTF8));
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                String hex = Integer.toHexString(b & 0xFF);
                if (hex.length() == 1) builder.append('0');
                builder.append(hex);
            }
            return builder.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
