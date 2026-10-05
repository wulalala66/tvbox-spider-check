package com.spider.check;

import android.text.TextUtils;

import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderNull;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 四级测活链路（对齐 FongMi SiteApi 语义）：home → category → detail → play。
 * 每一级都有超时保护，死站不会挂住整个流程。
 */
public class Checker {

    private static final int T_HOME = 20;
    private static final int T_CAT = 15;
    private static final int T_DETAIL = 20;
    private static final int T_PLAY = 20;

    private static final ExecutorService TIMER = Executors.newCachedThreadPool();

    public static void check(Site site) {
        Spider spider = SiteLoader.get().getSpider(site.getKey(), site.getApi(), site.getExt0(), site.getJar0());
        if (spider instanceof SpiderNull || spider == null) {
            String err = SiteLoader.get().lastError(site.getKey());
            site.setGrade("D");
            site.setResult(TextUtils.isEmpty(err) ? "加载失败" : err);
            return;
        }
        boolean home = false, category = false, detail = false, play = false;
        String firstId = "", firstTid = "", playInfo = "—", detailErr = "";

        // ---- home ----
        try {
            JSONObject h = toJson(call(() -> spider.homeContent(true), T_HOME, "home"));
            if (h == null) throw new Exception("home 返回非 JSON");
            JSONArray cls = h.optJSONArray("class");
            if (cls == null || cls.length() == 0) throw new Exception("home 无 class");
            firstTid = cls.optJSONObject(0).optString("type_id");
            JSONArray hl = h.optJSONArray("list");
            if (hl != null && hl.length() > 0 && !TextUtils.isEmpty(hl.optJSONObject(0).optString("vod_id"))) {
                firstId = hl.optJSONObject(0).optString("vod_id");
            } else {
                JSONObject hv = toJson(call(() -> spider.homeVideoContent(), T_HOME, "homeVod"));
                JSONArray vl = hv == null ? null : hv.optJSONArray("list");
                if (vl != null && vl.length() > 0 && !TextUtils.isEmpty(vl.optJSONObject(0).optString("vod_id"))) {
                    firstId = vl.optJSONObject(0).optString("vod_id");
                }
            }
            home = true; // 直播/纯分类型 home 无 list 不算失败
        } catch (Throwable e) {
            site.setGrade("D");
            site.setResult("home 失败：" + msg(e));
            done(spider, site);
            return;
        }

        // ---- category ----
        String catErr = "";
        if (TextUtils.isEmpty(firstTid)) {
            catErr = "home 未返回 type_id";
        } else {
            final String tid = firstTid;
            try {
                JSONObject c = toJson(call(() -> spider.categoryContent(tid, "1", true, new HashMap<>()), T_CAT, "category"));
                JSONArray cl = c == null ? null : c.optJSONArray("list");
                if (cl != null && cl.length() > 0) {
                    category = true;
                    if (TextUtils.isEmpty(firstId)) {
                        for (int i = 0; i < cl.length(); i++) {
                            String id = cl.optJSONObject(i).optString("vod_id");
                            if (!TextUtils.isEmpty(id)) {
                                firstId = id;
                                break;
                            }
                        }
                    }
                } else {
                    catErr = "无 list";
                }
            } catch (Throwable e) {
                catErr = msg(e);
            }
        }

        // ---- detail ----
        if (TextUtils.isEmpty(firstId)) {
            detailErr = "无 vod_id 可进详情";
        } else {
            final String id = firstId;
            try {
                JSONObject d = toJson(call(() -> spider.detailContent(Collections.singletonList(id)), T_DETAIL, "detail"));
                JSONArray dl = d == null ? null : d.optJSONArray("list");
                if (dl == null || dl.length() == 0) {
                    detailErr = "detail 无 list";
                } else {
                    JSONObject vod = dl.optJSONObject(0);
                    String playFrom = vod.optString("vod_play_from");
                    String playUrl = vod.optString("vod_play_url");
                    if (TextUtils.isEmpty(playUrl)) {
                        detailErr = "vod_play_url 空";
                    } else {
                        String[] flags = playUrl.split("\\$\\$\\$");
                        String[] flagNames = playFrom.split("\\$\\$\\$");
                        String firstEp = "", firstFlag = "";
                        for (int i = 0; i < flags.length; i++) {
                            for (String ep : flags[i].split("#")) {
                                int p = ep.lastIndexOf("$");
                                if (p > 0 && ep.length() > p + 1) {
                                    firstEp = ep.substring(p + 1);
                                    firstFlag = i < flagNames.length ? flagNames[i] : flagNames[0];
                                    break;
                                }
                            }
                            if (!TextUtils.isEmpty(firstEp)) break;
                        }
                        if (TextUtils.isEmpty(firstEp)) {
                            detailErr = "无剧集 url";
                        } else {
                            detail = true;
                            final String fFlag = TextUtils.isEmpty(firstFlag) ? flagNames[0] : firstFlag;
                            final String fEp = firstEp;
                            try {
                                JSONObject p = toJson(call(() -> spider.playerContent(fFlag, fEp, new ArrayList<>()), T_PLAY, "play"));
                                if (p == null) {
                                    playInfo = "play 返回非 JSON";
                                } else {
                                    String url = p.optString("url");
                                    int parse = p.optInt("parse", 0);
                                    int jx = p.optInt("jx", 0);
                                    if (!TextUtils.isEmpty(url)) {
                                        play = true;
                                        playInfo = "直链";
                                    } else if (parse == 1 || jx == 1) {
                                        play = true;
                                        playInfo = "待嗅探";
                                    } else {
                                        playInfo = "url 空且非嗅探";
                                    }
                                }
                            } catch (Throwable e) {
                                playInfo = msg(e);
                            }
                        }
                    }
                }
            } catch (Throwable e) {
                detailErr = msg(e);
            }
        }

        int score = (home ? 1 : 0) + (category ? 1 : 0) + (detail ? 1 : 0) + (play ? 1 : 0);
        site.setGrade(score >= 4 ? "A" : score == 3 ? "B" : score >= 1 ? "C" : "D");
        StringBuilder sb = new StringBuilder();
        sb.append("home ").append(home ? "✓" : "✗");
        sb.append(" · 分类 ").append(category ? "✓" : "✗");
        if (!category && !TextUtils.isEmpty(catErr)) sb.append("(").append(catErr).append(")");
        sb.append(" · 详情 ").append(detail ? "✓" : "✗");
        if (!detail && !TextUtils.isEmpty(detailErr)) sb.append("(").append(detailErr).append(")");
        sb.append(" · 播放 ").append(play ? "✓ " + playInfo : "✗ " + playInfo);
        site.setResult(sb.toString());
        done(spider, site);
    }

    /** 带超时的调用：返回 null 表示超时/失败原因已抛出。 */
    private static <T> T call(Callable<T> task, int seconds, String stage) throws Exception {
        Future<T> future = TIMER.submit(task);
        try {
            return future.get(seconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new Exception(stage + " 超时(" + seconds + "s)");
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new Exception(stage + " 异常: " + msg(cause));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Exception("被中断");
        }
    }

    private static JSONObject toJson(String raw) {
        try {
            return raw == null ? null : new JSONObject(raw);
        } catch (Throwable e) {
            return null;
        }
    }

    private static String msg(Throwable e) {
        String m = e.getMessage();
        if (m == null || m.isEmpty()) return e.getClass().getSimpleName();
        return m.length() > 120 ? m.substring(0, 120) : m;
    }

    private static void destroy(Spider spider) {
        try {
            spider.destroy();
        } catch (Throwable ignored) {
        }
    }

    private static void done(Spider spider, Site site) {
        destroy(spider);
        SiteLoader.get().evict(site.getKey());
    }
}
