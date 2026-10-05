package com.spider.check;

import android.content.Context;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 测活历史。
 *
 * <p>按【接口】分组持久化：同一个接口（配置地址 / 本地文件）再次测活时，
 * 只覆盖它自己那一条记录；不同接口各存一份，互不影响。清单会一直留到
 * 下次对同一接口测活为止，打开 App 也能直接看到上次的结果。
 *
 * <p>文件位置：getExternalFilesDir/check_history.json（应用私有，卸载即清，无需权限）。
 */
public class History {

    private static final String NAME = "check_history.json";
    /** 最多保留多少个接口的历史，防止文件无限增长。 */
    private static final int MAX_ENTRIES = 40;

    /** 一条接口的历史记录。 */
    public static class Entry {
        /** 接口标识（配置地址；本地导入为 local:文件名）。 */
        public String tag = "";
        /** 展示用标题（域名 / 文件名）。 */
        public String title = "";
        /** 保存时间，可读文本。 */
        public String savedAt = "";
        /** 保存时间戳。 */
        public long ts = 0;
        /** 测活深度 1-4。 */
        public int depth = 1;
        /** 这轮是否被中途停止（未跑完）。 */
        public boolean interrupted = false;
        public int total = 0;
        public int alive = 0;
        public int failed = 0;
        public int untested = 0;
        /** 完整站点列表（含 api/ext/jar，离线可重建）。 */
        public List<Site> sites = new ArrayList<>();

        /** 实时统计某个分类的站点数（比缓存字段更可靠）。 */
        public int count(int cat) {
            int n = 0;
            for (Site s : sites) if (s.category() == cat) n++;
            return n;
        }

        /** 标题为空时兜底。 */
        public String displayTitle() {
            if (!TextUtils.isEmpty(title)) return title;
            return TextUtils.isEmpty(tag) ? "未命名接口" : tag;
        }
    }

    private static File file(Context c) {
        return new File(c.getExternalFilesDir(null), NAME);
    }

    /** 时间戳 → "yyyy-MM-dd HH:mm"。 */
    public static String stamp(long ts) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(new Date(ts));
    }

    /** 全部历史，按时间倒序（最新在前）。 */
    public static List<Entry> all(Context c) {
        List<Entry> list = new ArrayList<>();
        try {
            File f = file(c);
            if (!f.exists() || f.length() == 0) return list;
            JSONObject root = new JSONObject(new String(read(f), StandardCharsets.UTF_8));
            JSONArray arr = root.optJSONArray("entries");
            if (arr == null) return list;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                Entry e = parse(o);
                if (!e.sites.isEmpty()) list.add(e);
            }
        } catch (Throwable ignored) {
        }
        Collections.sort(list, (a, b) -> Long.compare(b.ts, a.ts));
        return list;
    }

    /** 取某个接口的历史，没有则 null。 */
    public static Entry get(Context c, String tag) {
        if (TextUtils.isEmpty(tag)) return null;
        for (Entry e : all(c)) if (tag.equals(e.tag)) return e;
        return null;
    }

    /** 最近一条历史（打开 App 时用来直接回显上次结果）。 */
    public static Entry latest(Context c) {
        List<Entry> list = all(c);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 保存一次测活结果。同一个 tag 只保留一条 —— 再次测活会覆盖它自己的历史，
     * 其它接口的记录原样保留。
     */
    public static void save(Context c, String tag, String title, int depth,
                            boolean interrupted, List<Site> sites) {
        if (TextUtils.isEmpty(tag) || sites == null || sites.isEmpty()) return;
        try {
            List<Entry> list = all(c);
            Entry e = new Entry();
            e.tag = tag;
            e.title = TextUtils.isEmpty(title) ? tag : title;
            e.ts = System.currentTimeMillis();
            e.savedAt = stamp(e.ts);
            e.depth = Math.max(1, Math.min(depth, 4));
            e.interrupted = interrupted;
            e.sites = new ArrayList<>(sites);
            e.total = e.sites.size();
            for (Site s : e.sites) {
                if (s.category() == Site.CAT_ALIVE) e.alive++;
                else if (s.category() == Site.CAT_DEAD) e.failed++;
                else e.untested++;
            }
            for (int i = list.size() - 1; i >= 0; i--) {
                if (tag.equals(list.get(i).tag)) list.remove(i);
            }
            list.add(0, e);
            while (list.size() > MAX_ENTRIES) list.remove(list.size() - 1);
            write(c, list);
        } catch (Throwable ignored) {
        }
    }

    /** 删除某个接口的历史。 */
    public static void remove(Context c, String tag) {
        try {
            List<Entry> list = all(c);
            for (int i = list.size() - 1; i >= 0; i--) {
                if (tag.equals(list.get(i).tag)) list.remove(i);
            }
            write(c, list);
        } catch (Throwable ignored) {
        }
    }

    /** 清空全部历史。 */
    public static void clear(Context c) {
        try {
            File f = file(c);
            if (f.exists()) f.delete();
        } catch (Throwable ignored) {
        }
    }

    private static void write(Context c, List<Entry> list) {
        try {
            JSONObject root = new JSONObject();
            root.put("v", 1);
            JSONArray arr = new JSONArray();
            for (Entry e : list) {
                JSONObject o = new JSONObject();
                o.put("tag", e.tag);
                o.put("title", e.title);
                o.put("ts", e.ts);
                o.put("savedAt", e.savedAt);
                o.put("depth", e.depth);
                o.put("interrupted", e.interrupted);
                o.put("total", e.total);
                o.put("alive", e.alive);
                o.put("failed", e.failed);
                o.put("untested", e.untested);
                JSONArray sa = new JSONArray();
                for (Site s : e.sites) sa.put(s.toState());
                o.put("sites", sa);
                arr.put(o);
            }
            root.put("entries", arr);
            try (FileOutputStream out = new FileOutputStream(file(c))) {
                out.write(root.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {
        }
    }

    private static Entry parse(JSONObject o) {
        Entry e = new Entry();
        e.tag = o.optString("tag", "");
        e.title = o.optString("title", e.tag);
        e.ts = o.optLong("ts", 0);
        e.savedAt = o.optString("savedAt", e.ts > 0 ? stamp(e.ts) : "");
        e.depth = o.optInt("depth", 1);
        e.interrupted = o.optBoolean("interrupted", false);
        e.total = o.optInt("total", 0);
        e.alive = o.optInt("alive", 0);
        e.failed = o.optInt("failed", 0);
        e.untested = o.optInt("untested", 0);
        JSONArray sa = o.optJSONArray("sites");
        if (sa != null) {
            for (int i = 0; i < sa.length(); i++) {
                JSONObject so = sa.optJSONObject(i);
                if (so != null) e.sites.add(Site.fromState(so));
            }
        }
        if (e.total <= 0) e.total = e.sites.size();
        return e;
    }

    private static byte[] read(File f) throws Exception {
        try (FileInputStream in = new FileInputStream(f);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }
}
