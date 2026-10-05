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
        JSONObject doc = parse(text);
        if (doc == null) throw new Exception("配置解析失败（非 JSON / 非 2423 加密）");
        JSONArray arr = doc.optJSONArray("sites");
        if (arr == null) throw new Exception("配置无 sites 数组");
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

    private JSONObject parse(String text) {
        try {
            String t = text.trim();
            if (t.startsWith("{")) return new JSONObject(t);
            if (t.startsWith("2423")) return new JSONObject(decrypt(t));
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * 2423 加密（FongMi Decoder 同源）：hex(key)2324 hex(ct) hex(iv13)，AES-128-CBC，key/iv padEnd '0' 到 16B。
     */
    private static String decrypt(String input) throws Exception {
        String dec = new String(hex2Bytes(input.toLowerCase()), StandardCharsets.UTF_8);
        int start = dec.indexOf("#$");
        String key = dec.substring(2, start);
        while (key.length() < 16) key += "0";
        String iv = dec.substring(dec.length() - 13);
        while (iv.length() < 16) iv += "0";
        String data = dec.substring(dec.indexOf("2324") + 4, dec.length() - 26);
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
