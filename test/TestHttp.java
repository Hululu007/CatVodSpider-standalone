package com.github.catvod.spider;
import java.net.*; import java.io.*; import java.util.*; import javax.net.ssl.*;
import java.security.cert.X509Certificate;
public class TestHttp {
 static String get(String url, boolean trust, boolean http) throws Exception {
  URL u=new URL(url); HttpURLConnection c=(HttpURLConnection)u.openConnection();
  if(c instanceof HttpsURLConnection){
   HttpsURLConnection h=(HttpsURLConnection)c;
   if(trust){SSLContext sc=SSLContext.getInstance("TLS");sc.init(null,new TrustManager[]{new X509TrustManager(){public void checkClientTrusted(X509Certificate[] x,String s){}public void checkServerTrusted(X509Certificate[] x,String s){}public X509Certificate[] getAcceptedIssuers(){return new X509Certificate[0];}}},new java.security.SecureRandom());h.setSSLSocketFactory(sc.getSocketFactory());h.setHostnameVerifier((a,b)->true);}
  }
  c.setRequestProperty("User-Agent","Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36");
  c.setRequestProperty("Referer","https://www.olevod.tv/");
  c.setRequestProperty("Origin","https://www.olevod.tv");
  c.setRequestProperty("Accept","application/json, text/plain, */*");
  c.setRequestProperty("Accept-Language","zh-CN,zh;q=0.9");
  c.setConnectTimeout(15000);c.setReadTimeout(15000);
  int code=c.getResponseCode();
  InputStream in=code>=400?c.getErrorStream():c.getInputStream();
  ByteArrayOutputStream o=new ByteArrayOutputStream(); byte[] b=new byte[4096]; int n; if(in!=null)while((n=in.read(b))!=-1)o.write(b,0,n);
  return code+" "+o.toString("UTF-8").substring(0,Math.min(200,o.size()));
 }
 public static void main(String[] a)throws Exception{
  String path="https://api.olelive.com/v1/pub/vod/list/type?_vv=bad";
  System.out.println("https trust "+get(path,true,false));
  System.out.println("https default "+get(path,false,false));
  System.out.println("http "+get("http://api.olelive.com/v1/pub/vod/list/type?_vv=bad",false,true));
 }
}
