package com.spider.check;

import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * 站点配置项。
 */
public class Site {

    private final String key;
    private final String name;
    private final int type;
    private final String api;
    private final String ext;
    private final String jar;
    private String grade = "?";
    private String result = "—";
    private long elapsedMs = -1;
    private int depth = 0;
    /** 是否参与本轮测活（多选：用户可只测勾选的站点）。 */
    private boolean selected = true;

    public Site(String key, String name, int type, String api, String ext, String jar) {
        this.key = key;
        this.name = name;
        this.type = type;
        this.api = api;
        this.ext = ext;
        this.jar = jar;
    }

    public String getKey() {
        return key;
    }

    public String getName() {
        return name;
    }

    public String getApi() {
        return api;
    }

    public String getGrade() {
        return grade;
    }

    public void setGrade(String grade) {
        this.grade = grade;
    }

    public String getResult() {
        return result;
    }

    public void setResult(String result) {
        this.result = result;
    }

    public long getElapsedMs() {
        return elapsedMs;
    }

    public void setElapsedMs(long elapsedMs) {
        this.elapsedMs = elapsedMs;
    }

    public int getDepth() {
        return depth;
    }

    public void setDepth(int depth) {
        this.depth = depth;
    }

    public boolean isSelected() {
        return selected;
    }

    public void setSelected(boolean selected) {
        this.selected = selected;
    }

    /** 是否已测过（含失败）——续测时用来默认不勾选。 */
    public boolean tested() {
        return depth > 0 && !TextUtils.isEmpty(grade) && !"?".equals(grade);
    }

    /** 持久化：完整字段（含 ext/jar），用于下次打开 App 离线恢复，无需重新拉配置。 */
    public JSONObject toState() {
        try {
            JSONObject o = new JSONObject();
            o.put("key", key);
            o.put("name", name);
            o.put("type", type);
            o.put("api", api);
            o.put("ext", ext == null ? "" : ext);
            o.put("jar", jar == null ? "" : jar);
            o.put("grade", grade);
            o.put("result", result);
            o.put("depth", depth);
            o.put("elapsedMs", elapsedMs);
            o.put("selected", selected);
            return o;
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    /** 从持久化内容重建站点。 */
    public static Site fromState(JSONObject o) {
        Site s = new Site(o.optString("key"), o.optString("name"), o.optInt("type", 0),
                o.optString("api"), o.optString("ext", ""), o.optString("jar", ""));
        s.grade = o.optString("grade", "?");
        s.result = o.optString("result", "—");
        s.depth = o.optInt("depth", 0);
        s.elapsedMs = o.optLong("elapsedMs", -1);
        s.selected = o.optBoolean("selected", true);
        return s;
    }

    /** 结果文本 + 耗时，界面用。 */
    public String display() {
        String r = result;
        if (elapsedMs >= 0) r = r + "  [" + elapsedMs + "ms]";
        return r;
    }

    public String getJar0() {
        return jar;
    }

    public String getExt0() {
        return ext;
    }

    public String kindLabel() {
        String a = api.toLowerCase();
        if (a.contains(".py")) return "py";
        if (a.contains(".js")) return "js";
        if (api.startsWith("csp_")) return "jar";
        return "t" + type;
    }

    public boolean isSpider() {
        String a = api.toLowerCase();
        return a.contains(".py") || a.contains(".js") || api.startsWith("csp_");
    }

    public JSONObject toJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("key", key);
            o.put("name", name);
            o.put("type", type);
            o.put("api", api);
            o.put("kind", kindLabel());
            o.put("depth", depth);
            o.put("grade", grade);
            o.put("elapsedMs", elapsedMs);
            o.put("result", result);
            if (!TextUtils.isEmpty(jar)) o.put("jar", jar);
            return o;
        } catch (Exception e) {
            return new JSONObject();
        }
    }
}
