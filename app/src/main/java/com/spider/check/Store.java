package com.spider.check;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 测活任务持久化：中途退出 / 被系统杀掉后，下次打开 App 可以继续，不必重新测。
 *
 * 存到应用外部私有目录（getExternalFilesDir，卸载即清理，不需要任何权限）。
 * 快照里保存站点完整字段（api/ext/jar/name/type）+ 每站测活结果，因此恢复时
 * 不需要重新联网拉配置，离线也能续测。
 */
public class Store {

    private static final String NAME = "check_state.json";

    private static File file(Context c) {
        return new File(c.getExternalFilesDir(null), NAME);
    }

    public static void clear(Context c) {
        try {
            file(c).delete();
        } catch (Throwable ignored) {
        }
    }

    /** 保存任务快照。interrupted=true 表示这轮还没跑完（被停止 / 中途退出）。 */
    public static void save(Context c, String url, int depth, boolean interrupted, List<Site> sites) {
        try {
            JSONObject o = new JSONObject();
            o.put("v", 1);
            o.put("savedAt", System.currentTimeMillis());
            o.put("url", url == null ? "" : url);
            o.put("depth", depth);
            o.put("interrupted", interrupted);
            o.put("count", sites.size());
            JSONArray arr = new JSONArray();
            for (Site s : sites) arr.put(s.toState());
            o.put("sites", arr);
            write(file(c), o.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Throwable ignored) {
        }
    }

    /** 读取快照；没有或损坏返回 null。 */
    public static JSONObject load(Context c) {
        try {
            File f = file(c);
            if (!f.exists() || f.length() == 0) return null;
            JSONObject o = new JSONObject(new String(read(f), StandardCharsets.UTF_8));
            if (o.optInt("count", 0) <= 0) return null;
            return o;
        } catch (Throwable e) {
            return null;
        }
    }

    private static void write(File f, byte[] data) throws Exception {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(data);
        }
    }

    private static byte[] read(File f) throws Exception {
        try (FileInputStream in = new FileInputStream(f); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }
}
