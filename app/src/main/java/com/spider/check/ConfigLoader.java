package com.spider.check;

import android.text.TextUtils;


import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * TVBox/FongMi 配置拉取与解析：明文 JSON / 2423 加密 / 本地导入。
 * 相对 api（./py/x.py）相对配置 URL/所在目录解析——FongMi 语义。
 */
public class ConfigLoader {

    private String raw;
    private final List<Site> sites = new ArrayList<>();

    public List<Site> getSites() {
        return sites;
    }

    public String getRaw() {
        return raw;
    }

    /** 远程配置：拉文本后解析。部分站点校验 UA，这里走 Ua 自动嗅探。 */
    public int load(String url) throws Exception {
        if (url.contains(";md5;")) url = url.split(";md5;")[0];
        String text = Ua.text(url);
        if (TextUtils.isEmpty(text)) throw new Exception("配置拉取失败：" + Ua.lastReason());
        return loadText(text, url);
    }

    /** 本地/已取回文本解析。baseUrl 用于解析相对 api（http 配置传 URL，本地传 ""）。 */
    public int loadText(String text, String baseUrl) throws Exception {
        sites.clear();
        raw = text;
        JSONObject doc;
        try {
            doc = parse(text);
        } catch (Exception e) {
            throw new Exception("配置解析失败：" + msg(e));
        }
        JSONArray arr = doc.optJSONArray("sites");
        if (arr == null) throw new Exception("配置无 sites 数组（顶层键：" + keys(doc) + "）");
        String topJar = doc.optString("spider", "");
        for (int i = 0; i < arr.length(); i++) {
            JSONObject s = arr.optJSONObject(i);
            if (s == null) continue;
            String key = s.optString("key").trim();
            String api = s.optString("api").trim();
            if (TextUtils.isEmpty(key) || TextUtils.isEmpty(api)) continue;
            if (api.contains("｜")) api = api.split("｜")[0].trim();
            if (api.contains("|")) api = api.split("\\|")[0].trim();
            // 相对 api：相对配置地址解析（FongMi 对 ./py/x.js 等按配置所在目录取）
            api = resolve(api, baseUrl);
            // 站点级 jar 优先，缺省回退顶层 spider
            String jar = s.optString("jar", "");
            if (TextUtils.isEmpty(jar)) jar = topJar;
            jar = resolve(jar, baseUrl);
            String ext = s.optString("ext", "");
            sites.add(new Site(key, s.optString("name", key), s.optInt("type", 0), api, ext, jar));
        }
        return sites.size();
    }

    /** rel 相对 base 解析；无法解析时原样返回。 */
    private static String resolve(String rel, String base) {
        if (TextUtils.isEmpty(rel) || TextUtils.isEmpty(base)) return rel;
        if (!rel.startsWith("./") && !rel.startsWith("../")) return rel;
        try {
            URI baseUri = URI.create(base.contains("://") ? base : "file:///" + base);
            URI out = baseUri.resolve(rel);
            if (out.getScheme() == null) return rel;
            String s = out.toString();
            return s.startsWith("file:///") ? s.substring("file://".length()) : s;
        } catch (Throwable e) {
            return rel;
        }
    }

    private JSONObject parse(String text) throws Exception {
        String t = text == null ? "" : text.trim();
        if (t.isEmpty()) throw new Exception("配置内容为空");
        if (t.startsWith("{")) return new JSONObject(t);
        if (t.startsWith("2423")) return new JSONObject(decrypt(t));
        throw new Exception("配置内容无法识别（既不是 JSON 也不是 2423 加密）：" + head(t));
    }

    private static String head(String t) {
        String one = t.replaceAll("\\s+", " ");
        return one.length() > 40 ? one.substring(0, 40) + "…" : one;
    }

    /** 顶层键列表，便于判断配置结构问题。 */
    private static String keys(JSONObject o) {
        StringBuilder sb = new StringBuilder();
        java.util.Iterator<String> it = o.keys();
        while (it.hasNext()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(it.next());
        }
        return sb.length() == 0 ? "空" : sb.toString();
    }

    private static String msg(Throwable e) {
        String m = e.getMessage();
        return (m == null || m.isEmpty()) ? e.getClass().getSimpleName() : m;
    }

    /**
     * 2423 加密（严格镜像 FongMi Decoder.cbc / 后端 crypto.py）：
     *   hex 文本 = "2423" + hex(key) + "2324" + ct_hex + hex(iv13)
     * 关键：密文切片必须打在**原始 hex 文本**上——先把整串 hex 解码再找 "2324" 会得到 -1
     * （密文字节解码后是乱码），这正是早期版本「配置解析失败」的根因。
     */
    private static String decrypt(String input) throws Exception {
        String hex = input.replaceAll("\\s+", "").toLowerCase();
        if (!hex.startsWith("2423")) throw new Exception("不是 2423 格式");
        if (hex.length() % 2 != 0) throw new Exception("2423 内容长度异常（奇数个 hex 字符）");
        // key 文本从解码后的字符串里取（Java 侧整体 toLowerCase 后才参与 AES）
        String dec = new String(hex2Bytes(hex), StandardCharsets.UTF_8).toLowerCase();
        int start = dec.indexOf("#$");
        if (start < 2) throw new Exception("2423 格式缺少 #$ 分隔符");
        String key = dec.substring(2, start);
        while (key.length() < 16) key += "0";
        if (key.length() > 16) throw new Exception("2423 key 超过 16 字节");
        String iv = dec.substring(dec.length() - 13);
        while (iv.length() < 16) iv += "0";
        int sep = hex.indexOf("2324");
        if (sep < 0) throw new Exception("2423 格式缺少 2324 分隔符");
        if (sep + 4 > hex.length() - 26) throw new Exception("2423 密文段为空");
        String data = hex.substring(sep + 4, hex.length() - 26);
        if (data.isEmpty()) throw new Exception("2423 密文段为空");
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES"), new IvParameterSpec(iv.getBytes(StandardCharsets.UTF_8)));
        return new String(cipher.doFinal(hex2Bytes(data)), StandardCharsets.UTF_8);
    }

    private static byte[] hex2Bytes(String hex) {
        int len = hex.length() / 2;
        byte[] out = new byte[len];
        for (int i = 0; i < len; i++) {
            int hi = Character.digit(hex.charAt(i * 2), 16);
            int lo = Character.digit(hex.charAt(i * 2 + 1), 16);
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }
}
