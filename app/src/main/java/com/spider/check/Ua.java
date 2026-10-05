package com.spider.check;

import com.github.catvod.net.OkHttp;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

import okhttp3.Request;
import okhttp3.Response;

/**
 * User-Agent 适配：部分站点/接口会校验 UA（例如只放行 okhttp 系客户端），
 * 这里做候选 UA 自动嗅探、按主机记忆，并把命中的 UA 提升为全局 UA
 * （全局 UA 由 catvod OkHttp 注入，因此 js/py 源内部的 http 请求也会带上）。
 */
public class Ua {

    /** 候选 UA：TVBox/OK影视 生态常见值在前，浏览器 UA 兜底。 */
    public static final String[] CANDIDATES = {
            "okhttp/3.12.13",
            "okhttp/3.12.0",
            "okhttp/3.14.9",
            "okhttp/4.12.0",
            "okhttp/5.5.0",
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
            "TVBox",
    };

    private static final Map<String, String> hostUa = new LinkedHashMap<>();
    private static volatile String current = "";
    private static volatile String lastReason = "";
    private static volatile okhttp3.OkHttpClient probeClient;

    /** 当前全局 UA 的可读描述。 */
    public static String current() {
        String ua = current;
        return ua.isEmpty() ? "（OkHttp 默认）" : ua;
    }

    /** 最近一次失败的说明（HTTP 码/异常），用于错误提示。 */
    public static String lastReason() {
        return lastReason;
    }

    /** 手动指定全局 UA；传空串恢复默认。 */
    public static void apply(String ua) {
        current = ua == null ? "" : ua.trim();
        OkHttp.setUA(current.isEmpty() ? null : current);
    }

    /** 该 URL 已记忆的 UA（无则 null），用于诊断展示。 */
    public static String remembered(String url) {
        return hostUa.get(host(url));
    }

    /**
     * 拉文本：先用该主机已记忆的 UA，再逐候选 UA 试；成功即记忆并提升为全局 UA。
     * 全部失败返回空串，失败原因见 {@link #lastReason()}。
     * budgetMs 限制总尝试时间（每试一个 UA 单次超时 6s）。
     */
    public static String text(String url) {
        return text(url, 18000);
    }

    public static String text(String url, long budgetMs) {
        if (url == null || !url.startsWith("http")) return "";
        long deadline = System.currentTimeMillis() + budgetMs;
        String host = host(url);
        String known = hostUa.get(host);
        if (known != null) {
            String s = raw(url, known);
            if (ok(s)) return s;
            hostUa.remove(host);
        }
        StringBuilder reasons = new StringBuilder();
        int tried = 0;
        for (String cand : CANDIDATES) {
            tried++;
            String s = raw(url, cand);
            if (ok(s)) {
                hostUa.put(host, cand);
                if (!cand.equals(current)) {
                    current = cand;
                    OkHttp.setUA(cand);
                }
                lastReason = "";
                return s;
            }
            if (!reasons.isEmpty()) reasons.append(" / ");
            reasons.append(cand).append(" → ").append(lastReason);
            if (System.currentTimeMillis() > deadline) {
                reasons.append("（UA 预算用尽，已试 ").append(tried).append(" 个）");
                break;
            }
        }
        lastReason = reasons.toString();
        return "";
    }

    /** 指定 UA 试拉一次，返回可读结果（不抛异常，供诊断界面展示）。 */
    public static String probe(String url, String ua) {
        Response res = null;
        try {
            res = call(url, ua).execute();
            byte[] bytes = res.body() == null ? new byte[0] : res.body().bytes();
            return "HTTP " + res.code() + " " + bytes.length + "B";
        } catch (Throwable e) {
            return msg(e);
        } finally {
            if (res != null) res.close();
        }
    }

    private static boolean ok(String s) {
        return s != null && !s.trim().isEmpty();
    }

    private static String raw(String url, String ua) {
        Response res = null;
        try {
            res = call(url, ua).execute();
            if (res.code() / 100 != 2) {
                lastReason = "HTTP " + res.code() + "（UA: " + ua + "）";
                return "";
            }
            return res.body() == null ? "" : res.body().string();
        } catch (Throwable e) {
            lastReason = msg(e) + "（UA: " + ua + "）";
            return "";
        } finally {
            if (res != null) res.close();
        }
    }

    /** 显式带 UA 的请求；catvod OkHttp 的全局 UA 拦截器会让显式 header 优先。 */
    private static okhttp3.Call call(String url, String ua) {
        Request req = new Request.Builder().url(url).header("User-Agent", ua).build();
        return client().newCall(req);
    }

    private static okhttp3.OkHttpClient client() {
        if (probeClient == null) probeClient = OkHttp.client(6000);
        return probeClient;
    }

    private static String host(String url) {
        try {
            String h = URI.create(url).getHost();
            return h == null ? url : h;
        } catch (Throwable e) {
            return url;
        }
    }

    private static String msg(Throwable e) {
        String m = e.getMessage();
        return m == null || m.isEmpty() ? e.getClass().getSimpleName() : m;
    }
}
