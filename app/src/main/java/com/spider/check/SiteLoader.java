package com.spider.check;

import android.text.TextUtils;

import com.github.catvod.Init;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderNull;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Crypto;
import com.github.catvod.utils.Path;

import java.io.File;
import java.io.InputStream;
import java.util.concurrent.ConcurrentHashMap;

import dalvik.system.DexClassLoader;

/**
 * 站点分发器（FongMi BaseLoader 精简版）：.py→chaquopy / .js→quickjs / csp_→DexClassLoader。
 * 失败原因写入 errors，供测活结果展示（不吞异常）。
 */
public class SiteLoader {

    private static volatile SiteLoader instance;

    private final ConcurrentHashMap<String, Spider> spiders = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> errors = new ConcurrentHashMap<>();
    private com.fongmi.chaquo.Loader pyLoader;
    private com.fongmi.quickjs.crawler.Loader jsLoader;
    private final ConcurrentHashMap<String, DexClassLoader> dexCache = new ConcurrentHashMap<>();

    public static SiteLoader get() {
        if (instance == null) synchronized (SiteLoader.class) {
            if (instance == null) instance = new SiteLoader();
        }
        return instance;
    }

    /** 上一次 getSpider 失败原因（无则空串）。 */
    public String lastError(String key) {
        String e = errors.get(key);
        return e == null ? "" : e;
    }

    /** 单站点淘汰：测活完成后把实例移出缓存，避免复用已 destroy 的对象。 */
    public void evict(String key) {
        spiders.remove(key);
        errors.remove(key);
    }

    public void clear() {
        spiders.values().forEach(s -> {
            try {
                s.destroy();
            } catch (Throwable ignored) {
            }
        });
        spiders.clear();
        errors.clear();
        dexCache.clear();
        try {
            com.fongmi.quickjs.utils.Module.get().clear();
        } catch (Throwable ignored) {
        }
    }

    public Spider getSpider(String key, String api, String ext, String jar) {
        errors.remove(key);
        Spider spider;
        try {
            spider = create(key, api, ext, jar);
        } catch (Throwable e) {
            errors.put(key, "加载失败: " + msg(e));
            return new SpiderNull();
        }
        if (spider == null) {
            errors.put(key, errors.getOrDefault(key, "加载失败: 未知源类型"));
            return new SpiderNull();
        }
        try {
            spider.siteKey = key;
            spider.init(Init.context(), ext);
            return spider;
        } catch (Throwable e) {
            errors.put(key, "init 失败: " + msg(e));
            try {
                spider.destroy();
            } catch (Throwable ignored) {
            }
            return new SpiderNull();
        }
    }

    private Spider create(String key, String api, String ext, String jar) throws Exception {
        Spider cached = spiders.get(key);
        if (cached != null) return cached;
        Spider spider;
        if (api.startsWith("csp_")) spider = jar(api, jar);
        else if (api.contains(".py")) spider = py(api);
        else if (api.contains(".js")) spider = js(api, jar);
        else throw new Exception("未知源类型: " + api);
        if (spider == null) return null;
        spiders.put(key, spider);
        return spider;
    }

    private synchronized Spider py(String api) {
        if (pyLoader == null) pyLoader = new com.fongmi.chaquo.Loader();
        return pyLoader.spider(api);
    }

    private synchronized Spider js(String api, String jar) {
        if (jsLoader == null) jsLoader = new com.fongmi.quickjs.crawler.Loader();
        return jsLoader.spider(api, dex(jar));
    }

    private Spider jar(String api, String jar) throws Exception {
        String[] arr = api.split("csp_");
        if (arr.length < 2 || TextUtils.isEmpty(arr[1])) throw new Exception("非法 csp_ 类名: " + api);
        DexClassLoader loader = dex(jar);
        if (loader == null) throw new Exception("jar 不可用: " + (TextUtils.isEmpty(jar) ? "未配置 spider/jar" : jar));
        return (Spider) loader.loadClass("com.github.catvod.spider." + arr[1]).newInstance();
    }

    /**
     * jar 下载/缓存 → DexClassLoader。支持 ;md5; 校验、http 与本地路径。
     */
    private DexClassLoader dex(String jar) {
        try {
            if (TextUtils.isEmpty(jar)) return null;
            String cached = dexCache.get(jar) != null ? jar : null;
            if (cached != null) return dexCache.get(jar);
            String[] texts = jar.split(";md5;");
            String md5 = texts.length > 1 ? texts[1].trim() : "";
            if (md5.startsWith("http")) md5 = OkHttp.string(md5).trim();
            String url = texts[0].trim();
            File file;
            if (url.startsWith("file://") || url.startsWith("/")) {
                file = new File(url.replace("file://", ""));
            } else if (url.startsWith("http")) {
                file = Path.jar(Crypto.md5(url) + ".jar");
                if (!Path.exists(file)) {
                    try (InputStream in = OkHttp.newCall(url).execute().body().byteStream()) {
                        Path.write(file, in);
                    }
                }
            } else {
                return null;
            }
            if (!Path.exists(file)) return null;
            if (!md5.isEmpty() && !Crypto.equals(file, md5)) return null;
            String cachePath = Path.jar().getAbsolutePath();
            DexClassLoader loader = new DexClassLoader(file.getAbsolutePath(), cachePath, cachePath, Init.context().getClassLoader());
            dexCache.put(jar, loader);
            return loader;
        } catch (Throwable e) {
            e.printStackTrace();
            return null;
        }
    }

    private static String msg(Throwable e) {
        String m = e.getMessage();
        if (m == null || m.isEmpty()) return e.getClass().getSimpleName();
        return m.length() > 160 ? m.substring(0, 160) : m;
    }
}
