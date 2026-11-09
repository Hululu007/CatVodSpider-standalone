package com.github.catvod.crawler;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 通用 Spider 基类占位实现。
 * <p>
 * 打包成 JAR 后，若宿主 App 自带 com.github.catvod.crawler.Spider，
 * DexClassLoader 采用父加载器优先，会使用 App 的那一份；否则使用本类。
 * 这里不引用任何第三方或 Android 类型，确保在任何 TVBox 上都能加载。
 */
public class Spider {

    public void init() throws Exception {
    }

    public void init(String extend) throws Exception {
        init();
    }

    public String homeContent(boolean filter) throws Exception {
        return "{}";
    }

    public String homeVideoContent() throws Exception {
        return "{}";
    }

    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        return "{}";
    }

    public String detailContent(List<String> ids) throws Exception {
        return "{}";
    }

    public String searchContent(String key, boolean quick) throws Exception {
        return "{}";
    }

    public String searchContent(String key, boolean quick, String pg) throws Exception {
        return "{}";
    }

    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        return "{}";
    }

    public String liveContent(String url) throws Exception {
        return "";
    }

    public String action(String action) throws Exception {
        return "";
    }

    public boolean manualVideoCheck() throws Exception {
        return false;
    }

    public boolean isVideoFormat(String url) throws Exception {
        return false;
    }

    public Object[] proxy(Map<String, String> params) throws Exception {
        return new Object[0];
    }

    public Object[] proxyLocal(Map<String, String> params) throws Exception {
        return new Object[0];
    }

    public void destroy() {
    }
}
