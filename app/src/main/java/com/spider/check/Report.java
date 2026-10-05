package com.spider.check;

import java.util.ArrayList;
import java.util.List;

/**
 * 测活报告生成：Markdown（可粘贴/分享）、HTML（浏览器打开好看）、JSON（格式化）。
 *
 * <p>JSON 是手写拼接的 —— org.json 的 JSONObject 不保证字段顺序、也不缩进，
 * 导出来一行糊在一起毫无观感，所以这里自己控制缩进与字段顺序。
 */
public class Report {

    /** 文件名（含扩展名）。 */
    public static String fileName(History.Entry e, String ext) {
        String t = e.displayTitle() == null ? "report" : e.displayTitle();
        t = t.replaceAll("[^0-9A-Za-z\\u4e00-\\u9fa5._-]", "_");
        if (t.length() > 24) t = t.substring(0, 24);
        if (t.isEmpty()) t = "report";
        return "check_" + t + "." + ext;
    }

    /** 按格式生成内容。 */
    public static String build(History.Entry e, String format) {
        if ("md".equals(format)) return markdown(e);
        if ("html".equals(format)) return html(e);
        return json(e);
    }

    // ---------------- Markdown ----------------

    public static String markdown(History.Entry e) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 源测活报告 · ").append(e.displayTitle()).append("\n\n");
        sb.append("| 项目 | 值 |\n| --- | --- |\n");
        sb.append("| 接口 | `").append(safe(e.tag)).append("` |\n");
        sb.append("| 测活时间 | ").append(safe(e.savedAt)).append(" |\n");
        sb.append("| 测活深度 | ").append(HistoryAdapter.depthName(e.depth)).append(" |\n");
        sb.append("| 是否跑完 | ").append(e.interrupted ? "否（中途停止）" : "是").append(" |\n");
        sb.append("| 结果统计 | 共 ").append(e.total).append(" 站 · ✅ 可用 ").append(e.count(Site.CAT_ALIVE))
                .append(" · ❌ 失败 ").append(e.count(Site.CAT_DEAD))
                .append(" · ⚪ 未测 ").append(e.count(Site.CAT_UNTESTED)).append(" |\n\n");
        mdSection(sb, e, Site.CAT_ALIVE, "## ✅ 正常可用");
        mdSection(sb, e, Site.CAT_DEAD, "## ❌ 测活失败");
        mdSection(sb, e, Site.CAT_UNTESTED, "## ⚪ 未测 / 中断");
        sb.append("\n---\n\n");
        sb.append("由「源测活」App 生成");
        return sb.toString();
    }

    private static void mdSection(StringBuilder sb, History.Entry e, int cat, String title) {
        List<Site> list = group(e, cat);
        if (list.isEmpty()) return;
        sb.append(title).append("（").append(list.size()).append("）\n\n");
        sb.append("| 站点 | 类型 | 等级 | 耗时 | 结果 |\n");
        sb.append("| --- | --- | --- | --- | --- |\n");
        for (Site s : list) {
            sb.append("| ").append(md(safe(s.getName())))
                    .append(" | ").append(s.kindLabel())
                    .append(" | ").append(s.getGrade())
                    .append(" | ").append(s.getElapsedMs() < 0 ? "—" : s.getElapsedMs() + "ms")
                    .append(" | ").append(md(safe(s.getResult())))
                    .append(" |\n");
        }
        sb.append("\n");
    }

    /** Markdown 表格里 `|` 与换行要转义。 */
    private static String md(String s) {
        return s.replace("|", "\\|").replace("\r", " ").replace("\n", " ");
    }

    // ---------------- JSON（手写缩进） ----------------

    public static String json(History.Entry e) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"tag\": ").append(js(e.tag)).append(",\n");
        sb.append("  \"title\": ").append(js(e.displayTitle())).append(",\n");
        sb.append("  \"checkedAt\": ").append(js(e.savedAt)).append(",\n");
        sb.append("  \"timestamp\": ").append(e.ts).append(",\n");
        sb.append("  \"depth\": ").append(e.depth).append(",\n");
        sb.append("  \"depthName\": ").append(js(HistoryAdapter.depthName(e.depth))).append(",\n");
        sb.append("  \"finished\": ").append(!e.interrupted).append(",\n");
        sb.append("  \"summary\": {\n");
        sb.append("    \"total\": ").append(e.total).append(",\n");
        sb.append("    \"alive\": ").append(e.count(Site.CAT_ALIVE)).append(",\n");
        sb.append("    \"failed\": ").append(e.count(Site.CAT_DEAD)).append(",\n");
        sb.append("    \"untested\": ").append(e.count(Site.CAT_UNTESTED)).append("\n");
        sb.append("  },\n");
        sb.append("  \"sites\": [\n");
        for (int i = 0; i < e.sites.size(); i++) {
            Site s = e.sites.get(i);
            sb.append("    {\n");
            sb.append("      \"key\": ").append(js(s.getKey())).append(",\n");
            sb.append("      \"name\": ").append(js(s.getName())).append(",\n");
            sb.append("      \"kind\": ").append(js(s.kindLabel())).append(",\n");
            sb.append("      \"api\": ").append(js(s.getApi())).append(",\n");
            sb.append("      \"category\": ").append(js(catName(s.category()))).append(",\n");
            sb.append("      \"grade\": ").append(js(s.getGrade())).append(",\n");
            sb.append("      \"depth\": ").append(s.getDepth()).append(",\n");
            sb.append("      \"elapsedMs\": ").append(s.getElapsedMs()).append(",\n");
            sb.append("      \"result\": ").append(js(s.getResult()));
            if (s.getJar0() != null && !s.getJar0().isEmpty()) {
                sb.append(",\n      \"jar\": ").append(js(s.getJar0()));
            }
            if (s.getExt0() != null && !s.getExt0().isEmpty()) {
                sb.append(",\n      \"ext\": ").append(js(s.getExt0()));
            }
            sb.append("\n    }").append(i == e.sites.size() - 1 ? "\n" : ",\n");
        }
        sb.append("  ]\n");
        sb.append("}\n");
        return sb.toString();
    }

    private static String catName(int cat) {
        switch (cat) {
            case Site.CAT_ALIVE:
                return "alive";
            case Site.CAT_DEAD:
                return "failed";
            default:
                return "untested";
        }
    }

    /** 最小 JSON 字符串转义。 */
    private static String js(String s) {
        if (s == null) return "\"\"";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.append("\"").toString();
    }

    // ---------------- HTML ----------------

    public static String html(History.Entry e) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html>\n<html lang=\"zh-CN\">\n<head>\n");
        sb.append("<meta charset=\"utf-8\">\n");
        sb.append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">\n");
        sb.append("<title>源测活报告 · ").append(h(e.displayTitle())).append("</title>\n");
        sb.append("<style>\n");
        sb.append("*{box-sizing:border-box}"
                + "body{margin:0;padding:14px;background:#f2f3f5;color:#1f1f1f;"
                + "font:14px/1.6 -apple-system,BlinkMacSystemFont,\"PingFang SC\",\"Microsoft YaHei\",sans-serif}"
                + "h1{font-size:19px;margin:0 0 12px}"
                + "h2{font-size:15px;margin:20px 0 8px}"
                + ".card{background:#fff;border:1px solid #eaecf0;border-radius:14px;padding:14px 16px}"
                + ".kv{display:flex;gap:14px;padding:5px 0;border-bottom:1px dashed #f0f1f3}"
                + ".kv:last-child{border-bottom:0}"
                + ".kv span{color:#5f6368;flex:0 0 auto}"
                + ".kv b{font-weight:600;margin-left:auto;text-align:right;word-break:break-all}"
                + ".pills{display:flex;gap:8px;flex-wrap:wrap;margin-top:12px}"
                + ".pill{border-radius:999px;padding:5px 14px;font-size:12px;font-weight:700}"
                + ".p-alive{background:#e8f5e9;color:#2e7d32}"
                + ".p-dead{background:#fdecea;color:#b71c1c}"
                + ".p-un{background:#eceff1;color:#607d8b}"
                + ".wrap{background:#fff;border:1px solid #eaecf0;border-radius:14px;overflow:hidden}"
                + "table{width:100%;border-collapse:collapse;font-size:13px}"
                + "th,td{padding:9px 12px;text-align:left;border-bottom:1px solid #f2f3f5;vertical-align:top}"
                + "th{background:#fafbfc;color:#5f6368;font-size:12px;font-weight:600;white-space:nowrap}"
                + "tr:last-child td{border-bottom:0}"
                + ".g{display:inline-block;min-width:22px;height:22px;line-height:22px;text-align:center;"
                + "border-radius:7px;color:#fff;font-size:12px;font-weight:700}"
                + ".A{background:#2e7d32}.B{background:#1565c0}.C{background:#ef6c00}.D{background:#b71c1c}.Q{background:#9aa0a6}"
                + ".name{font-weight:600}"
                + ".mono{font-family:ui-monospace,Menlo,Consolas,monospace;font-size:12px;color:#78909c;word-break:break-all}"
                + "footer{color:#9aa0a6;font-size:12px;text-align:center;margin:18px 0 6px}\n");
        sb.append("</style>\n</head>\n<body>\n");

        sb.append("<h1>源测活报告 · ").append(h(e.displayTitle())).append("</h1>\n");
        sb.append("<div class=\"card\">\n");
        sb.append(kv("接口", "<span class=\"mono\">" + h(e.tag) + "</span>"));
        sb.append(kv("测活时间", h(e.savedAt)));
        sb.append(kv("测活深度", h(HistoryAdapter.depthName(e.depth))));
        sb.append(kv("是否跑完", e.interrupted ? "否（中途停止）" : "是"));
        sb.append("<div class=\"pills\">");
        sb.append("<span class=\"pill p-alive\">可用 ").append(e.count(Site.CAT_ALIVE)).append("</span>");
        sb.append("<span class=\"pill p-dead\">失败 ").append(e.count(Site.CAT_DEAD)).append("</span>");
        sb.append("<span class=\"pill p-un\">未测 ").append(e.count(Site.CAT_UNTESTED)).append("</span>");
        sb.append("<span class=\"pill p-un\">共 ").append(e.total).append(" 站</span>");
        sb.append("</div>\n</div>\n");

        htmlSection(sb, e, Site.CAT_ALIVE, "✅ 正常可用");
        htmlSection(sb, e, Site.CAT_DEAD, "❌ 测活失败");
        htmlSection(sb, e, Site.CAT_UNTESTED, "⚪ 未测 / 中断");

        sb.append("<footer>由「源测活」App 生成 · ").append(h(e.savedAt)).append("</footer>\n");
        sb.append("</body>\n</html>\n");
        return sb.toString();
    }

    private static String kv(String k, String v) {
        return "<div class=\"kv\"><span>" + h(k) + "</span><b>" + v + "</b></div>\n";
    }

    private static void htmlSection(StringBuilder sb, History.Entry e, int cat, String title) {
        List<Site> list = group(e, cat);
        if (list.isEmpty()) return;
        sb.append("<h2>").append(title).append("（").append(list.size()).append("）</h2>\n");
        sb.append("<div class=\"wrap\"><table>\n");
        sb.append("<tr><th>站点</th><th>类型</th><th>等级</th><th>耗时</th><th>结果</th></tr>\n");
        for (Site s : list) {
            String g = h(s.getGrade());
            String cls = "ABC".contains(g) ? g : "D".equals(g) ? "D" : "Q";
            sb.append("<tr>");
            sb.append("<td class=\"name\">").append(h(s.getName()));
            sb.append("<div class=\"mono\">").append(h(s.getApi())).append("</div></td>");
            sb.append("<td>").append(h(s.kindLabel())).append("</td>");
            sb.append("<td><span class=\"g ").append(cls).append("\">").append(g).append("</span></td>");
            sb.append("<td>").append(s.getElapsedMs() < 0 ? "—" : s.getElapsedMs() + "ms").append("</td>");
            sb.append("<td>").append(h(s.getResult())).append("</td>");
            sb.append("</tr>\n");
        }
        sb.append("</table></div>\n");
    }

    /** HTML 转义。 */
    private static String h(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    // ---------------- 公共 ----------------

    private static List<Site> group(History.Entry e, int cat) {
        List<Site> list = new ArrayList<>();
        for (Site s : e.sites) if (s.category() == cat) list.add(s);
        return list;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
