package com.spider.check;

import android.text.TextUtils;

import com.github.catvod.Init;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderNull;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Crypto;
import com.github.catvod.utils.Path;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.util.concurrent.ConcurrentHashMap;

import dalvik.system.DexClassLoader;
import okhttp3.Response;

/**
 * 站点分发器（FongMi BaseLoader 精简版）：.py→chaquopy / .js→quickjs / csp_→DexClassLoader。
 * 失败原因写入 errors，供测活结果展示（不吞异常、给出可定位的中文原因）。
 */
public class SiteLoader {

    private static volatile SiteLoader instance;

    private final ConcurrentHashMap<String, Spider> spiders = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> errors = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, DexClassLoader> dexCache = new ConcurrentHashMap<>();
    private com.fongmi.chaquo.Loader pyLoader;
    private com.fongmi.quickjs.crawler.Loader jsLoader;

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

    /** 清空实例缓存（保留 dexCache 与已下载 jar 文件，多轮测活更快）。 */
    public void clear() {
        spiders.values().forEach(s -> {
            try {
                s.destroy();
            } catch (Throwable ignored) {
            }
        });
        spiders.clear();
        errors.clear();
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
            errors.put(key, "加载失败：" + msg(e));
            return new SpiderNull();
        }
        if (spider == null) {
            errors.put(key, "加载失败：源返回空实例");
            return new SpiderNull();
        }
        try {
            spider.siteKey = key;
            spider.init(Init.context(), ext);
            return spider;
        } catch (Throwable e) {
            errors.put(key, "init 失败：" + msg(e));
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
        else if (api.contains(".jar")) spider = jarfile(api, jar);
        else throw new Exception("未知源类型：" + api);
        if (spider == null) throw new Exception("源加载返回空（" + api + "）");
        spiders.put(key, spider);
        return spider;
    }

    private synchronized Spider py(String api) {
        if (pyLoader == null) pyLoader = new com.fongmi.chaquo.Loader();
        return pyLoader.spider(api);
    }

    private synchronized Spider js(String api, String jar) {
        if (jsLoader == null) jsLoader = new com.fongmi.quickjs.crawler.Loader();
        return jsLoader.spider(api, dexOrNull(jar));
    }

    private Spider jar(String api, String jar) throws Exception {
        String[] arr = api.split("csp_");
        if (arr.length < 2 || TextUtils.isEmpty(arr[1])) throw new Exception("非法 csp_ 类名：" + api);
        if (TextUtils.isEmpty(jar)) throw new Exception("该源需要 jar，但配置里没有 spider/jar 字段（csp_" + arr[1] + "）");
        DexClassLoader loader = dex(jar);
        String cls = "com.github.catvod.spider." + arr[1];
        try {
            return (Spider) loader.loadClass(cls).newInstance();
        } catch (ClassNotFoundException e) {
            throw new Exception("jar 里没有类 " + arr[1] + "（" + cls + "）");
        } catch (Throwable e) {
            throw new Exception("实例化 " + arr[1] + " 失败：" + msg(e));
        }
    }

    /** api 直接指向 .jar 文件（少见写法，FongMi 不支持直接实例化）。 */
    private Spider jarfile(String api, String jar) throws Exception {
        throw new Exception("api 直接指向 jar 文件（" + api + "），不支持；请用 csp_ 类名写法");
    }

    private DexClassLoader dexOrNull(String jar) {
        try {
            return TextUtils.isEmpty(jar) ? null : dex(jar);
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * jar 下载/缓存 → DexClassLoader。支持 ;md5; 校验、http 与本地路径。
     * 失败时抛出可定位原因（HTTP 状态码 / 文件大小 / md5 / 只读 / DexClassLoader 异常）。
     *
     * 关键：Android 14+ 禁止加载可写 DEX（W^X 策略），必须 setReadOnly()，否则抛
     * "Writable dex file ... is not allowed"——这是 jar 源在真机全部加载失败的主因。
     */
    private synchronized DexClassLoader dex(String jar) throws Exception {
        DexClassLoader cached = dexCache.get(jar);
        if (cached != null) return cached;
        String[] texts = jar.split(";md5;");
        String md5 = texts.length > 1 ? texts[1].trim() : "";
        if (md5.startsWith("http")) md5 = OkHttp.string(md5).trim();
        String url = texts[0].trim();
        File file;
        if (url.startsWith("file://") || url.startsWith("/")) {
            file = new File(url.startsWith("file://") ? url.substring("file://".length()) : url);
            if (!file.exists()) throw new Exception("本地 jar 不存在：" + file.getAbsolutePath());
        } else if (url.startsWith("http")) {
            file = Path.jar(Crypto.md5(url) + ".jar");
            if (!Path.exists(file) || file.length() < 1024) {
                if (file.exists()) file.setWritable(true);
                Response resp = null;
                try {
                    resp = OkHttp.newCall(url).execute();
                    if (resp.code() / 100 != 2) throw new Exception("jar 下载失败 HTTP " + resp.code() + "：" + url);
                    byte[] bytes = resp.body().bytes();
                    if (bytes.length < 1024) throw new Exception("jar 下载内容异常（" + bytes.length + " 字节）：" + url);
                    Path.write(file, new ByteArrayInputStream(bytes));
                } finally {
                    if (resp != null) resp.close();
                }
            }
        } else {
            throw new Exception("jar 地址不合法：" + url);
        }
        if (!Path.exists(file) || file.length() == 0) throw new Exception("jar 文件为空：" + file.getName());
        if (!md5.isEmpty() && !Crypto.equals(file, md5)) throw new Exception("jar 校验失败（md5 不匹配）：" + file.getName());
        // Android 14+ W^X：DEX 必须以只读加载
        if (!file.setReadOnly() && file.canWrite()) {
            throw new Exception("jar 无法设为只读（Android 14+ 要求）：" + file.getAbsolutePath());
        }
        try {
            String cachePath = Path.jar().getAbsolutePath();
            DexClassLoader loader = new DexClassLoader(file.getAbsolutePath(), cachePath, cachePath, Init.context().getClassLoader());
            invokeInit(loader);
            dexCache.put(jar, loader);
            return loader;
        } catch (Throwable e) {
            throw new Exception("DexClassLoader 加载失败：" + msg(e));
        }
    }

    /** jar 内 Init.init(Context)：不调用则 jar 内的 Http/Path 等静态工具拿不到 Context。 */
    private void invokeInit(DexClassLoader loader) {
        try {
            Class<?> clz = loader.loadClass("com.github.catvod.spider.Init");
            java.lang.reflect.Method method = clz.getMethod("init", android.content.Context.class);
            method.invoke(clz, Init.context());
        } catch (Throwable e) {
            // 部分 jar 没有 Init 类，属于正常情况
        }
    }

    private static String msg(Throwable e) {
        String m = e.getMessage();
        if (m == null || m.isEmpty()) return e.getClass().getSimpleName();
        return m.length() > 200 ? m.substring(0, 200) : m;
    }
}
