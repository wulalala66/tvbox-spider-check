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
            o.put("grade", grade);
            o.put("result", result);
            return o;
        } catch (Exception e) {
            return new JSONObject();
        }
    }
}
