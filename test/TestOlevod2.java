package com.github.catvod.spider;
public class TestOlevod2 {
 public static void main(String[] a) throws Exception {
  Olevod o=new Olevod(); o.init("");
  String h=o.homeContent(true);
  System.out.println(h.length()>1000?h.substring(0,1000):h);
 }
}
