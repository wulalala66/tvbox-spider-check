package com.spider.check;

import android.text.TextUtils;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderNull;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;

/**
 * 四级测活链路（对齐 FongMi SiteApi 语义）：
 * home → category → detail → play，另附 search。
 */
public class Checker {

    public static void check(Site site) {
        Spider spider = SiteLoader.get().getSpider(site.getKey(), site.getApi(), site.getExt0(), site.getJar0());
        if (spider instanceof SpiderNull || spider == null) {
            site.setGrade("D");
            site.setResult("加载失败");
            return;
        }
        boolean home = false, category = false, detail = false, play = false;
        String firstId = "", firstTid = "", detail2 = "—";
        try {
            JSONObject h = toJson(spider.homeContent(true));
            if (h == null) throw new Exception("home 返回非法 JSON");
            JSONArray cls = h.optJSONArray("class");
            if (cls == null || cls.length() == 0) throw new Exception("home 无 class");
            firstTid = cls.optJSONObject(0).optString("type_id");
            JSONArray hl = h.optJSONArray("list");
            if (hl != null && hl.length() > 0) {
                firstId = hl.optJSONObject(0).optString("vod_id");
                home = true;
            } else {
                JSONObject hv = toJson(spider.homeVideoContent());
                JSONArray vl = hv == null ? null : hv.optJSONArray("list");
                if (vl != null && vl.length() > 0) {
                    firstId = vl.optJSONObject(0).optString("vod_id");
                    home = true;
                } else {
                    home = true; // 直播/纯分类型 home 无 list 不算失败
                }
            }
        } catch (Throwable e) {
            site.setGrade("D");
            site.setResult("home: " + msg(e));
            destroy(spider);
            return;
        }
        try {
            JSONObject c = toJson(spider.categoryContent(firstTid, "1", true, new HashMap<>()));
            JSONArray cl = c == null ? null : c.optJSONArray("list");
            if (cl != null && cl.length() > 0) {
                firstId = cl.optJSONObject(0).optString("vod_id");
                category = true;
            }
        } catch (Throwable ignored) {
        }
        // vod_tag=folder 的项走下一级 category，不当 detail
        try {
            if (TextUtils.isEmpty(firstId)) throw new Exception("无 vod_id 可进详情");
            JSONObject d = toJson(spider.detailContent(Collections.singletonList(firstId)));
            JSONArray dl = d == null ? null : d.optJSONArray("list");
            if (dl != null && dl.length() > 0) {
                JSONObject vod = dl.optJSONObject(0);
                String playFrom = vod.optString("vod_play_from");
                String playUrl = vod.optString("vod_play_url");
                String[] flags = playUrl.split("\\$\\$\\$");
                String[] flagNames = playFrom.split("\\$\\$\\$");
                String firstEp = "";
                for (String seg : flags) {
                    for (String ep : seg.split("#")) {
                        int i = ep.lastIndexOf("$");
                        if (i > 0 && ep.length() > i + 1) {
                            firstEp = ep.substring(i + 1);
                            break;
                        }
                    }
                    if (!TextUtils.isEmpty(firstEp)) break;
                }
                if (!TextUtils.isEmpty(firstEp) && flagNames.length > 0 && flagNames[0].length() > 0) {
                    detail = true;
                    String flagName = flagNames[0];
                    try {
                        JSONObject p = toJson(spider.playerContent(flagName, firstEp, new java.util.ArrayList<>()));
                        if (p != null) {
                            String url = p.optString("url");
                            int parse = p.optInt("parse", 0);
                            int jx = p.optInt("jx", 0);
                            if (!TextUtils.isEmpty(url) || parse == 1 || jx == 1) {
                                play = true;
                                detail2 = parse == 1 || jx == 1 ? "待嗅探" : "直链";
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable e) {
            detail2 = msg(e);
        }
        int score = (home ? 1 : 0) + (category ? 1 : 0) + (detail ? 1 : 0) + (play ? 1 : 0);
        site.setGrade(score >= 4 ? "A" : score == 3 ? "B" : score >= 1 ? "C" : "D");
        site.setResult(String.format("home%s cat%s det%s(%s)%s play%s%s", home ? "✓" : "✗", category ? "✓" : "✗",
                detail ? "✓" : "✗", firstId.length() > 18 ? firstId.substring(0, 18) + "…" : firstId,
                detail ? "" : "(" + detail2 + ")", play ? "✓ " + detail2 : "✗"));
        destroy(spider);
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
        return m == null || m.isEmpty() ? e.getClass().getSimpleName() : m;
    }

    private static void destroy(Spider spider) {
        try {
            spider.destroy();
        } catch (Throwable ignored) {
        }
    }
}
