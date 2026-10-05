package com.spider.check;

import android.text.TextUtils;

import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Crypto;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * TVBox/FongMi 配置拉取与解析：明文 JSON / 2423 加密 / 方案接口（;md5; 前缀）。
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

    public int load(String url) throws Exception {
        sites.clear();
        String text = fetch(url);
        JSONObject doc = parse(text);
        if (doc == null) throw new Exception("配置解析失败（非 JSON / 非 2423 加密）");
        JSONArray arr = doc.optJSONArray("sites");
        if (arr == null) throw new Exception("配置无 sites 数组");
        String jar = TextUtils.isEmpty(doc.optString("spider")) ? "" : doc.optString("spider");
        for (int i = 0; i < arr.length(); i++) {
            JSONObject s = arr.optJSONObject(i);
            if (s == null) continue;
            String key = s.optString("key").trim();
            String api = s.optString("api").trim();
            if (TextUtils.isEmpty(key) || TextUtils.isEmpty(api)) continue;
            if (api.contains("｜")) api = api.split("｜")[0].trim();
            sites.add(new Site(key, s.optString("name", key), s.optInt("type", 0), api, s.optString("ext", ""), jar));
        }
        return sites.size();
    }

    private String fetch(String url) throws Exception {
        // FongMi 语义：直接 URL 拉文本；VodConfig 里 ;md5; 前缀剥掉
        if (url.contains(";md5;")) url = url.split(";md5;")[0];
        return OkHttp.string(url);
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
        int end = dec.indexOf("#$") + 2;
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
