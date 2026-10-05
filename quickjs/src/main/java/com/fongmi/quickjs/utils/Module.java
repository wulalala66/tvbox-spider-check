package com.fongmi.quickjs.utils;

import android.text.TextUtils;
import android.util.LruCache;

import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Asset;
import com.github.catvod.utils.Path;

import java.io.File;

public class Module {

    private static final int MAX_SIZE = 50;
    private final LruCache<String, String> cache;

    public Module() {
        cache = new LruCache<>(MAX_SIZE);
    }

    public static Module get() {
        return Loader.INSTANCE;
    }

    public String fetch(String name) {
        if (name == null) return null;
        String content = cache.get(name);
        if (!TextUtils.isEmpty(content)) return content;
        if (name.startsWith("http")) content = OkHttp.string(name);
        else if (name.startsWith("assets")) content = Asset.read(name);
        else if (name.startsWith("lib/")) content = Asset.read("js/" + name);
        else if (name.startsWith("file://")) content = read(name.substring("file://".length()));
        else if (name.startsWith("file:/")) content = read(name.substring("file:".length()));
        else if (name.startsWith("/")) content = read(name);
        // LruCache 不接受 null 值；读取失败时不写缓存，直接返回 null 交给调用方报错
        if (!TextUtils.isEmpty(content)) cache.put(name, content);
        return content;
    }

    /** 本地 js 源支持（导入本地配置时的 ./js/x.js / file:// 路径）。 */
    private static String read(String path) {
        try {
            File file = new File(path);
            if (!file.exists() || file.isDirectory()) return null;
            String text = Path.read(file);
            return TextUtils.isEmpty(text) ? null : text;
        } catch (Throwable e) {
            return null;
        }
    }

    public void clear() {
        cache.evictAll();
    }

    private static class Loader {
        static volatile Module INSTANCE = new Module();
    }
}
