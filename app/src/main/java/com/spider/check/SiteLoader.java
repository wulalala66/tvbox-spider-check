package com.spider.check;

import android.text.TextUtils;

import com.github.catvod.Init;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderNull;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Crypto;
import com.github.catvod.utils.Path;
import com.fongmi.chaquo.Loader;
import com.fongmi.quickjs.crawler.Loader;

import java.io.File;
import java.util.concurrent.ConcurrentHashMap;

import dalvik.system.DexClassLoader;

/**
 * 站点分发器（FongMi BaseLoader 精简版）：.py→chaquopy / .js→quickjs / csp_→DexClassLoader
 */
public class SiteLoader {

    private static volatile SiteLoader instance;

    private final ConcurrentHashMap<String, Spider> spiders;
    private Loader pyLoader;
    private Loader jsLoader;
    private DexClassLoader jarLoader;
    private String jarKey;

    public static SiteLoader get() {
        if (instance == null) synchronized (SiteLoader.class) {
            if (instance == null) instance = new SiteLoader();
        }
        return instance;
    }

    private SiteLoader() {
        spiders = new ConcurrentHashMap<>();
    }

    public void clear() {
        spiders.values().forEach(Spider::destroy);
        spiders.clear();
        jarLoader = null;
        jarKey = null;
    }

    public Spider getSpider(String key, String api, String ext, String jar) {
        Spider spider = create(key, api, ext, jar);
        try {
            spider.siteKey = key;
            spider.init(Init.context(), ext);
            return spider;
        } catch (Throwable e) {
            e.printStackTrace();
            try { spider.destroy(); } catch (Throwable ignored) {}
            return new SpiderNull();
        }
    }

    private Spider create(String key, String api, String ext, String jar) {
        try {
            if (api.contains(".py")) return py(key, api);
            if (api.contains(".js")) return js(key, api, jar);
            if (api.startsWith("csp_")) return jar(api, jar);
        } catch (Throwable e) {
            e.printStackTrace();
        }
        return new SpiderNull();
    }

    private synchronized Spider py(String key, String api) {
        Spider cached = spiders.get(key);
        if (cached != null) return cached;
        if (pyLoader == null) pyLoader = new Loader();
        Spider spider = pyLoader.spider(api);
        spiders.put(key, spider);
        return spider;
    }

    private synchronized Spider js(String key, String api, String jar) {
        Spider cached = spiders.get(key);
        if (cached != null) return cached;
        if (jsLoader == null) jsLoader = new QjsLoader();
        Spider spider = jsLoader.spider(api, dex(jar));
        spiders.put(key, spider);
        return spider;
    }

    private synchronized Spider jar(String api, String jar) throws Exception {
        DexClassLoader loader = dex(jar);
        if (loader == null) return new SpiderNull();
        String cls = "com.github.catvod.spider." + api.split("csp_")[1];
        return (Spider) loader.loadClass(cls).newInstance();
    }

    /**
     * jar 下载/缓存 → DexClassLoader。支持 ;md5; 校验与 file:// 本地路径。
     */
    private DexClassLoader dex(String jar) {
        try {
            if (TextUtils.isEmpty(jar)) return null;
            if (jarKey != null && jarKey.equals(Crypto.md5(jar)) && jarLoader != null) return jarLoader;
            String[] texts = jar.split(";md5;");
            String md5 = texts.length > 1 ? texts[1].trim() : "";
            if (md5.startsWith("http")) md5 = OkHttp.string(md5).trim();
            String url = texts[0];
            File file;
            if (url.startsWith("file://")) {
                file = new File(url.replace("file://", ""));
            } else if (url.startsWith("http")) {
                file = Path.jar(Crypto.md5(url) + ".jar");
                if (!Path.exists(file)) Path.write(file, OkHttp.newCall(url).execute().body().byteStream());
            } else {
                return null;
            }
            if (!Path.exists(file)) return null;
            if (!md5.isEmpty() && !Crypto.equals(file, md5)) return null;
            String cachePath = Path.jar().getAbsolutePath();
            jarLoader = new DexClassLoader(file.getAbsolutePath(), cachePath, cachePath, Init.context().getClassLoader());
            jarKey = Crypto.md5(jar);
            return jarLoader;
        } catch (Throwable e) {
            e.printStackTrace();
            return null;
        }
    }
}
