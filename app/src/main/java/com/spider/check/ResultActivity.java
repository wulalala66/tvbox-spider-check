package com.spider.check;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 某个接口的测活结果详情：按「✅ 正常可用 / ❌ 测活失败 / ⚪ 未测」分组展示，
 * 支持只看某一类、导出 JSON、载入回主界面继续测。
 */
public class ResultActivity extends AppCompatActivity {

    private History.Entry entry;
    private SiteAdapter adapter;
    private Button btnFAll, btnFAlive, btnFDead, btnFUntested;
    private ActivityResultLauncher<String> saveTextLauncher;
    private ActivityResultLauncher<String> saveJsonLauncher;
    private String pendingFormat = "json";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_result);
        View root = findViewById(R.id.root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        String tag = getIntent() == null ? null : getIntent().getStringExtra(MainActivity.EXTRA_TAG);
        entry = History.get(this, tag);
        if (entry == null || entry.sites.isEmpty()) {
            Toast.makeText(this, "找不到这条历史记录", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        ((TextView) findViewById(R.id.tvTitle)).setText(entry.displayTitle());
        ((TextView) findViewById(R.id.tvInfo)).setText(String.format(
                "%s\n%s · 深度 %s%s\n共 %d 站：可用 %d · 失败 %d · 未测 %d",
                entry.tag,
                entry.savedAt,
                HistoryAdapter.depthName(entry.depth),
                entry.interrupted ? " · 未跑完" : "",
                entry.total,
                entry.count(Site.CAT_ALIVE),
                entry.count(Site.CAT_DEAD),
                entry.count(Site.CAT_UNTESTED)));

        RecyclerView recycler = findViewById(R.id.recycler);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        adapter = new SiteAdapter();
        adapter.setShowCheckbox(false);
        adapter.setOnItemClick(this::showDetail);
        adapter.setOnLongClick(this::showDetail);
        recycler.setAdapter(adapter);
        adapter.setItems(entry.sites);
        adapter.setGrouped(true);

        btnFAll = findViewById(R.id.btnFAll);
        btnFAlive = findViewById(R.id.btnFAlive);
        btnFDead = findViewById(R.id.btnFDead);
        btnFUntested = findViewById(R.id.btnFUntested);
        btnFAll.setOnClickListener(v -> setFilter(SiteAdapter.FILTER_ALL));
        btnFAlive.setOnClickListener(v -> setFilter(SiteAdapter.FILTER_ALIVE));
        btnFDead.setOnClickListener(v -> setFilter(SiteAdapter.FILTER_DEAD));
        btnFUntested.setOnClickListener(v -> setFilter(SiteAdapter.FILTER_UNTESTED));
        btnFAll.setOnLongClickListener(v -> {
            adapter.setFilter(SiteAdapter.FILTER_ALL);
            adapter.setGrouped(true);
            updateChips();
            toast("已按「可用 / 失败 / 未测」分组显示");
            return true;
        });
        updateChips();

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnLoad2Main).setOnClickListener(v -> loadToMain());
        saveTextLauncher = registerForActivityResult(new ActivityResultContracts.CreateDocument("text/plain"),
                this::writeOut);
        saveJsonLauncher = registerForActivityResult(new ActivityResultContracts.CreateDocument("application/json"),
                this::writeOut);
        findViewById(R.id.btnExport).setOnClickListener(v -> exportMenu());
    }

    private void setFilter(int f) {
        adapter.setGrouped(false);
        adapter.setFilter(f);
        updateChips();
    }

    private void updateChips() {
        int[] c = adapter.counts();
        btnFAll.setText("全部 " + adapter.total());
        btnFAlive.setText("可用 " + c[Site.CAT_ALIVE]);
        btnFDead.setText("失败 " + c[Site.CAT_DEAD]);
        btnFUntested.setText("未测 " + c[Site.CAT_UNTESTED]);
        int f = adapter.getFilter();
        btnFAll.setSelected(f == SiteAdapter.FILTER_ALL);
        btnFAlive.setSelected(f == SiteAdapter.FILTER_ALIVE);
        btnFDead.setSelected(f == SiteAdapter.FILTER_DEAD);
        btnFUntested.setSelected(f == SiteAdapter.FILTER_UNTESTED);
    }

    private void loadToMain() {
        Intent i = new Intent(this, MainActivity.class);
        i.setAction(MainActivity.ACTION_LOAD_HISTORY);
        i.putExtra(MainActivity.EXTRA_TAG, entry.tag);
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(i);
        finish();
    }

    // ---------------- 导出 ----------------

    private void exportMenu() {
        String[] items = {
                "复制报告（Markdown，可直接粘贴）",
                "分享报告（Markdown 文本）",
                "保存报告（Markdown .md）",
                "保存报告（网页 .html，浏览器打开最好看）",
                "保存 JSON（已格式化，带汇总字段）"
        };
        new AlertDialog.Builder(this)
                .setTitle("导出 · " + entry.displayTitle())
                .setItems(items, (d, w) -> {
                    switch (w) {
                        case 0:
                            copyReport();
                            break;
                        case 1:
                            shareReport();
                            break;
                        case 2:
                            saveReport("md");
                            break;
                        case 3:
                            saveReport("html");
                            break;
                        default:
                            saveReport("json");
                            break;
                    }
                })
                .show();
    }

    private void copyReport() {
        String text = Report.markdown(entry);
        android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(android.content.ClipData.newPlainText("report", text));
        toast("报告已复制（" + text.length() + " 字符）");
    }

    private void shareReport() {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_SUBJECT, "源测活报告 · " + entry.displayTitle());
        i.putExtra(Intent.EXTRA_TEXT, Report.markdown(entry));
        try {
            startActivity(Intent.createChooser(i, "分享测活报告"));
        } catch (Throwable t) {
            toast("没有可分享的应用，已改为复制到剪贴板");
            copyReport();
        }
    }

    private void saveReport(String format) {
        pendingFormat = format;
        String name = Report.fileName(entry, format);
        if ("json".equals(format)) saveJsonLauncher.launch(name);
        else saveTextLauncher.launch(name);
    }

    private void writeOut(Uri uri) {
        if (uri == null) return;
        try {
            String text = Report.build(entry, pendingFormat);
            try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
                out.write(text.getBytes(StandardCharsets.UTF_8));
            }
            try {
                File f = new File(getExternalFilesDir(null), "check_report." + pendingFormat);
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(f)) {
                    fos.write(text.getBytes(StandardCharsets.UTF_8));
                }
            } catch (Throwable ignored) {
            }
            toast("已导出 " + uri.getLastPathSegment());
        } catch (Throwable e) {
            toast("导出失败：" + e.getMessage());
        }
    }

    private void showDetail(Site site) {
        String text = "名称：" + site.getName()
                + "\nkey：" + site.getKey()
                + "\n类型：" + site.kindLabel()
                + "\n分类：" + site.categoryName()
                + "\napi：\n" + site.getApi()
                + "\n\njar：\n" + (site.getJar0() == null || site.getJar0().isEmpty() ? "（无）" : site.getJar0())
                + "\n\next：\n" + (site.getExt0() == null || site.getExt0().isEmpty() ? "（无）" : site.getExt0())
                + "\n\n深度：" + (site.getDepth() == 0 ? "未测" : String.valueOf(site.getDepth()))
                + "\n等级：" + site.getGrade()
                + "\n耗时：" + (site.getElapsedMs() < 0 ? "—" : site.getElapsedMs() + "ms")
                + "\n结果：" + site.getResult();
        new AlertDialog.Builder(this)
                .setTitle(site.getName() + "  [" + site.getGrade() + "]")
                .setMessage(text)
                .setPositiveButton("复制", (d, w) -> {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("site", text));
                    toast("已复制");
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
