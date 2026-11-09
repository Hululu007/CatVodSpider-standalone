package com.github.catvod.spider;

import android.content.Context;

/**
 * takagen99/Box、TVBoxOS 等分支加载 jar 时会查找本类并调用
 * {@code Init.init(Context)}。这里除了空实现，还弹一个 Toast，
 * 用于确认 jar 是否真的被宿主加载成功（排查完可删掉 Toast）。
 */
public class Init {

    public static void init(final Context context) {
        try {
            new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
                public void run() {
                    try {
                        android.widget.Toast.makeText(context, "By共创已加载", android.widget.Toast.LENGTH_LONG).show();
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (Throwable ignored) {
        }
    }
}
