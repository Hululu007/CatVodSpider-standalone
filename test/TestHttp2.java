package com.github.catvod.spider;
import java.net.*; import java.io.*; import javax.net.ssl.*;
public class TestHttp2 {
 static String get(String url, boolean ipv4, String tls, String ciphers) throws Exception {
  URL u=new URL(url); HttpsURLConnection c=(HttpsURLConnection)u.openConnection();
  c.setRequestProperty("User-Agent","Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36");
  c.setRequestProperty("Referer","https://www.olevod.tv/");
  c.setRequestProperty("Accept","application/json, text/plain, */*");
  c.setRequestProperty("Accept-Language","zh-CN,zh;q=0.9");
  c.setRequestProperty("Connection","close");
  c.setConnectTimeout(15000);c.setReadTimeout(15000);
  if(tls!=null){try{c.setSSLSocketFactory(new TLSFactory(tls,ciphers));}catch(Throwable e){}}
  int code=c.getResponseCode();
  InputStream in=code>=400?c.getErrorStream():c.getInputStream();
  ByteArrayOutputStream o=new ByteArrayOutputStream(); byte[] b=new byte[4096]; int n; if(in!=null)while((n=in.read(b))!=-1)o.write(b,0,n);
  return code+" len="+o.size()+" "+o.toString("UTF-8").substring(0,Math.min(120,o.size()));
 }
 static class TLSFactory extends SSLSocketFactory {
  String tls,ciphers; TLSFactory(String t,String c){tls=t;ciphers=c;}
  public Socket createSocket(Socket s,String h,int p,boolean a)throws IOException{SSLSocket x=(SSLSocket)getDefault().createSocket(s,h,p,a);init(x);return x;}
  public Socket createSocket(String h,int p)throws IOException{SSLSocket x=(SSLSocket)getDefault().createSocket(h,p);init(x);return x;}
  public Socket createSocket(String h,int p,InetAddress l,int lp)throws IOException{SSLSocket x=(SSLSocket)getDefault().createSocket(h,p,l,lp);init(x);return x;}
  public Socket createSocket(InetAddress h,int p)throws IOException{SSLSocket x=(SSLSocket)getDefault().createSocket(h,p);init(x);return x;}
  public Socket createSocket(InetAddress h,int p,InetAddress l,int lp)throws IOException{SSLSocket x=(SSLSocket)getDefault().createSocket(h,p,l,lp);init(x);return x;}
  void init(SSLSocket x){if(tls!=null)x.setEnabledProtocols(new String[]{tls});if(ciphers!=null)x.setEnabledCipherSuites(ciphers.split(","));}
  public String[] getDefaultCipherSuites(){return new String[0];} public String[] getSupportedCipherSuites(){return new String[0];}
 }
 public static void main(String[] a)throws Exception{
  String u="https://api.olelive.com/v1/pub/vod/list/type?_vv=bad";
  System.out.println("default "+get(u,false,null,null));
  System.out.println("tls12 "+get(u,false,"TLSv1.2",null));
  String c="TLS_AES_128_GCM_SHA256,TLS_AES_256_GCM_SHA384,TLS_CHACHA20_POLY1305_SHA256,ECDHE-ECDSA-AES128-GCM-SHA256,ECDHE-RSA-AES128-GCM-SHA256,ECDHE-ECDSA-AES256-GCM-SHA384,ECDHE-RSA-AES256-GCM-SHA384";
  System.out.println("tls13ciphers "+get(u,false,"TLSv1.3",c));
 }
}
