package com.github.catvod.spider;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.Charset;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * 零依赖 HTTP 客户端：自己建 socket、放宽证书校验、多 IP 并行尝试，
 * 并且流式解析 chunked 响应（Cloudflare 这类服务不会主动断开连接）。
 */
class Http {

    static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static SSLSocketFactory factory;

    static synchronized SSLSocketFactory trustAll() throws Exception {
        if (factory != null) return factory;
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
        factory = context.getSocketFactory();
        return factory;
    }

    static String get(String url) throws Exception {
        return get(url, null);
    }

    static String get(String url, Map<String, String> extra) throws Exception {
        String current = url;
        for (int hop = 0; hop < 5; hop++) {
            Resp response = once(current, extra);
            if (response.status >= 300 && response.status < 400 && !response.location.isEmpty()) {
                current = new URL(new URL(current), response.location).toString();
                continue;
            }
            return response.body;
        }
        throw new IOException("重定向过多");
    }

    private static Resp once(String url, Map<String, String> extra) throws Exception {
        URL parsed = new URL(url);
        final String hostName = parsed.getHost();
        final boolean tls = "https".equals(parsed.getProtocol());
        final int port = parsed.getPort() > 0 ? parsed.getPort() : (tls ? 443 : 80);
        final String file = parsed.getFile();

        final Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", UA);
        headers.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        headers.put("Accept-Language", "zh-CN,zh;q=0.9");
        headers.put("Accept-Encoding", "identity");
        headers.put("Referer", parsed.getProtocol() + "://" + hostName + "/");
        if (extra != null) headers.putAll(extra);

        InetAddress[] addresses = InetAddress.getAllByName(hostName);
        final BlockingQueue<Object> queue = new ArrayBlockingQueue<>(addresses.length);
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
            if (result instanceof Resp) return (Resp) result;
            last = (Throwable) result;
        }
        if (last instanceof Exception) throw (Exception) last;
        throw new IOException("无可用地址");
    }

    private static Resp fetch(InetAddress address, String hostName, int port, String file, Map<String, String> headers, boolean tls) throws Exception {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(address, port), 6000);
            socket.setSoTimeout(20000);
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

            InputStream input = socket.getInputStream();
            String statusLine = readLine(input);
            if (statusLine == null) throw new IOException("空响应");
            Resp response = new Resp();
            try {
                response.status = Integer.parseInt(statusLine.split(" ")[1]);
            } catch (Exception ignored) {
            }
            Map<String, String> responseHeaders = new LinkedHashMap<>();
            String line;
            while ((line = readLine(input)) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon > 0) responseHeaders.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
            }
            response.location = responseHeaders.containsKey("location") ? responseHeaders.get("location") : "";
            String transfer = responseHeaders.get("transfer-encoding");
            String length = responseHeaders.get("content-length");
            byte[] raw;
            if (transfer != null && transfer.toLowerCase(Locale.ROOT).contains("chunked")) {
                raw = readChunked(input);
            } else if (length != null) {
                raw = readFixed(input, parseInt(length));
            } else {
                raw = readAll(input);
            }
            String encoding = responseHeaders.get("content-encoding");
            if (encoding != null && encoding.toLowerCase(Locale.ROOT).contains("gzip")) {
                raw = gunzip(raw);
            }
            response.body = new String(raw, UTF8);
            return response;
        } finally {
            try {
                socket.close();
            } catch (Exception ignored) {
            }
        }
    }

    /** 读一行（去掉行尾 CRLF），EOF 返回 null */
    private static String readLine(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int b;
        boolean any = false;
        while ((b = input.read()) != -1) {
            any = true;
            if (b == '\n') break;
            if (b != '\r') output.write(b);
        }
        if (!any && b == -1) return null;
        return new String(output.toByteArray(), UTF8);
    }

    private static byte[] readChunked(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        while (true) {
            String sizeLine = readLine(input);
            if (sizeLine == null || sizeLine.isEmpty()) break;
            int semi = sizeLine.indexOf(';');
            if (semi >= 0) sizeLine = sizeLine.substring(0, semi);
            int size;
            try {
                size = Integer.parseInt(sizeLine.trim(), 16);
            } catch (Exception e) {
                break;
            }
            if (size == 0) break;
            byte[] buffer = new byte[size];
            int read = 0;
            while (read < size) {
                int count = input.read(buffer, read, size - read);
                if (count < 0) break;
                read += count;
            }
            output.write(buffer, 0, read);
            readLine(input);
            if (output.size() > 8 * 1024 * 1024) break;
        }
        return output.toByteArray();
    }

    private static byte[] readFixed(InputStream input, int length) throws Exception {
        if (length <= 0) return new byte[0];
        byte[] buffer = new byte[length];
        int read = 0;
        while (read < length) {
            int count = input.read(buffer, read, length - read);
            if (count < 0) break;
            read += count;
        }
        byte[] result = new byte[read];
        System.arraycopy(buffer, 0, result, 0, read);
        return result;
    }

    private static byte[] readAll(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        try {
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
                if (output.size() > 8 * 1024 * 1024) break;
            }
        } catch (Exception ignored) {
            // 部分服务端不主动断开，读到超时就返回已收到的内容
        }
        return output.toByteArray();
    }

    private static byte[] gunzip(byte[] data) throws Exception {
        java.io.ByteArrayInputStream input = new java.io.ByteArrayInputStream(data);
        java.util.zip.GZIPInputStream gzip = new java.util.zip.GZIPInputStream(input);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = gzip.read(buffer)) != -1) output.write(buffer, 0, count);
        gzip.close();
        return output.toByteArray();
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception e) {
            return -1;
        }
    }

    static class Resp {
        int status;
        String location = "";
        String body = "";
    }
}
