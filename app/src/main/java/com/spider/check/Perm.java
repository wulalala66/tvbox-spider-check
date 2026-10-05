package com.spider.check;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;

import androidx.core.content.ContextCompat;

/**
 * 存储权限。
 *
 * <p>配置里的本地源是共享存储下的绝对路径（例如
 * {@code file:/storage/emulated/0/Download/QQ/tvbox/py/萝卜影视.py}），
 * Android 11（API 30）起用 File API 读写这类路径必须拿到「所有文件访问权限」
 * （MANAGE_EXTERNAL_STORAGE）；Android 10 及以下用传统的
 * READ/WRITE_EXTERNAL_STORAGE。两条路都不通时会一直报「本地源文件不存在或无法读取」。
 */
public class Perm {

    /** 是否已经能读写共享存储里的源文件。 */
    public static boolean ok(Context c) {
        return needsLegacy() ? hasLegacy(c) : hasAllFiles();
    }

    /** Android 11 之前的传统权限路径。 */
    public static boolean needsLegacy() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.R;
    }

    /** 所有文件访问权限是否已授予（Android 11+）。 */
    public static boolean hasAllFiles() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager();
    }

    public static String legacyPermission() {
        return Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q
                ? Manifest.permission.WRITE_EXTERNAL_STORAGE
                : Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    public static boolean hasLegacy(Context c) {
        try {
            return ContextCompat.checkSelfPermission(c, legacyPermission()) == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 跳「本应用」的所有文件访问权限设置页。 */
    public static Intent appIntent(Context c) {
        Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
        i.setData(Uri.parse("package:" + c.getPackageName()));
        return i;
    }

    /** 跳「应用列表」的所有文件访问权限设置页（个别 ROM 不认带 package 的那个）。 */
    public static Intent allIntent() {
        return new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
    }

    /** 系统是否提供该设置入口（没有就不弹引导，免得白跳）。 */
    public static boolean canAskAllFiles(Context c) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false;
        return appIntent(c).resolveActivity(c.getPackageManager()) != null
                || allIntent().resolveActivity(c.getPackageManager()) != null;
    }

    /** 当前权限状态的一句话说明，诊断页用。 */
    public static String status(Context c) {
        if (needsLegacy()) {
            return hasLegacy(c) ? "✓ 已授予（" + permName(legacyPermission()) + "）"
                    : "✗ 未授予（" + permName(legacyPermission()) + "）";
        }
        return hasAllFiles() ? "✓ 已授予（所有文件访问权限）" : "✗ 未授予（所有文件访问权限）";
    }

    private static String permName(String p) {
        if (Manifest.permission.READ_EXTERNAL_STORAGE.equals(p)) return "读取存储";
        if (Manifest.permission.WRITE_EXTERNAL_STORAGE.equals(p)) return "读写存储";
        return p;
    }
}
