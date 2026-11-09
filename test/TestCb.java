package com.github.catvod.spider;
public class TestCb {
  public static void main(String[] a) throws Exception {
    for (String n : new String[]{"庆余年","凡人修仙传","流浪地球"}) {
      long t = System.currentTimeMillis();
      String s = Chabeihu.line("https://chabeihu.com.cn", n);
      System.out.println(n + " -> " + (s==null?"null":"len="+s.length()) + " in " + (System.currentTimeMillis()-t));
    }
  }
}
