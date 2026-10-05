package com.spider.check;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.text.TextUtils;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;
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

import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Path;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class MainActivity extends AppCompatActivity {

    /** 并发测活线程数：太快容易被目标站限流，6 是速度与稳定性的平衡点。 */
    private static final int CHECK_THREADS = 6;

    private static final String[] DEPTH_LABELS = {
            "① 仅首页（最快）",
            "② 首页 + 分类",
            "③ 到详情",
            "④ 全链路（含播放）"
    };

    private EditText etConfig;
    private TextView tvProgress;
    private Spinner spDepth;
    private RecyclerView recycler;
    private SiteAdapter adapter;
    private final ConfigLoader loader = new ConfigLoader();
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private volatile boolean running = false;
    private volatile boolean stopRequested = false;

    private ActivityResultLauncher<String[]> importLauncher;
    private ActivityResultLauncher<String> exportLauncher;

    @SuppressLint("SetTextI18n")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        applyWindowInsets();
        etConfig = findViewById(R.id.etConfig);
        tvProgress = findViewById(R.id.tvProgress);
        spDepth = findViewById(R.id.spDepth);
        recycler = findViewById(R.id.recycler);

        ArrayAdapter<String> depthAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, DEPTH_LABELS);
        depthAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spDepth.setAdapter(depthAdapter);

        adapter = new SiteAdapter();
        adapter.setOnLongClick(this::showSiteDetail);
        adapter.setOnItemClick(this::checkOne);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        recycler.setAdapter(adapter);
        findViewById(R.id.btnLoad).setOnClickListener(v -> runTask(this::doLoad));
        findViewById(R.id.btnImport).setOnClickListener(v -> importLauncher.launch(new String[]{"application/json", "text/plain", "application/octet-stream", "*/*"}));
        findViewById(R.id.btnRun).setOnClickListener(v -> runTask(this::doRun));
        findViewById(R.id.btnStop).setOnClickListener(v -> {
            if (!running) {
                toast("当前没有测活任务");
                return;
            }
            stopRequested = true;
            progress("正在停止…（已发出的请求超时后结束）");
        });
        findViewById(R.id.btnExport).setOnClickListener(v -> doExport());
        findViewById(R.id.btnDiag).setOnClickListener(v -> runTask(this::doDiag));
        // 长按「诊断」= UA 设置（部分接口校验 User-Agent）
        findViewById(R.id.btnDiag).setOnLongClickListener(v -> {
            showUaDialog();
            return true;
        });
        findViewById(R.id.btnClear).setOnClickListener(v -> doClear());

        importLauncher = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
            if (uri == null) return;
            runTask(() -> doImport(uri));
        });
        exportLauncher = registerForActivityResult(new ActivityResultContracts.CreateDocument("application/json"), uri -> {
            if (uri == null) return;
            runTask(() -> writeOut(uri));
        });
    }

    /**
     * targetSdk 35+ 强制 edge-to-edge：内容会画到状态栏/手势条下面。
     * 用 WindowInsets 把根布局内缩，避免顶部被状态栏盖住。
     */
    private void applyWindowInsets() {
        View root = findViewById(R.id.root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
    }

    private void runTask(Task task) {
        if (running) {
            toast("有任务进行中，请稍候");
            return;
        }
        running = true;
        pool.execute(() -> {
            try {
                task.run();
            } catch (Throwable e) {
                fail(e);
            } finally {
                running = false;
            }
        });
    }

    private interface Task {
        void run() throws Exception;
    }

    private int depth() {
        int p = spDepth.getSelectedItemPosition();
        return p < 0 ? 1 : p + 1;
    }

    // ---------------- 加载 / 导入 ----------------

    private void doLoad() throws Exception {
        String url = etConfig.getText().toString().trim();
        if (url.isEmpty()) throw new Exception("请输入配置地址，或点「导入」选本地文件");
        if (!url.startsWith("http")) throw new Exception("地址需以 http 开头");
        progress("拉取配置…");
        int n = loader.load(url);
        SiteLoader.get().clear();
        loaded(n, url);
    }

    private void doImport(Uri uri) throws Exception {
        progress("读取本地文件…");
        String base = localBase(uri);
        String text = readText(uri);
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Throwable ignored) {
        }
        int n = loader.loadText(text, base);
        SiteLoader.get().clear();
        loaded(n, TextUtils.isEmpty(base) ? "本地文件" : base);
    }

    /** 本地配置的基准路径：相对源（./py/x.py）按配置文件所在目录解析。 */
    private String localBase(Uri uri) {
        try {
            if ("file".equals(uri.getScheme())) return uri.getPath();
            String docId = DocumentsContract.getDocumentId(uri);
            if (docId != null && docId.contains(":")) {
                String[] p = docId.split(":", 2);
                String root = "primary".equals(p[0]) ? "/storage/emulated/0/" : "/storage/" + p[0] + "/";
                return root + p[1];
            }
        } catch (Throwable ignored) {
        }
        return uri.getPath();
    }

    private void loaded(int n, String base) {
        runOnUiThread(() -> {
            adapter.setItems(loader.getSites());
            long py = loader.getSites().stream().filter(s -> s.kindLabel().equals("py")).count();
            long js = loader.getSites().stream().filter(s -> s.kindLabel().equals("js")).count();
            long jar = loader.getSites().stream().filter(s -> s.kindLabel().equals("jar")).count();
            long other = n - py - js - jar;
            tvProgress.setText(String.format("已加载 %d 站点（py=%d js=%d jar=%d 接口=%d）· 选深度后点「开始测活」", n, py, js, jar, other));
        });
    }

    // ---------------- 测活 ----------------

    private void doRun() throws Exception {
        final List<Site> sites = loader.getSites();
        if (sites.isEmpty()) throw new Exception("先加载或导入配置");
        final int depth = depth();
        stopRequested = false;
        SiteLoader.get().clear();
        long t0 = System.currentTimeMillis();
        for (Site s : sites) {
            s.setGrade("?");
            s.setResult("排队中");
            s.setDepth(depth);
            s.setElapsedMs(-1);
        }
        final int total = sites.size();
        runOnUiThread(() -> adapter.notifyDataSetChanged());

        final AtomicInteger done = new AtomicInteger();
        final AtomicInteger alive = new AtomicInteger();
        final AtomicInteger failed = new AtomicInteger();
        ExecutorService checkPool = Executors.newFixedThreadPool(CHECK_THREADS);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (final Site site : sites) {
                futures.add(checkPool.submit(() -> {
                    if (stopRequested) return;
                    try {
                        Checker.check(site, depth);
                    } catch (Throwable e) {
                        site.setGrade("D");
                        site.setResult("异常：" + msg(e));
                    }
                    int d = done.incrementAndGet();
                    if ("D".equals(site.getGrade()) || "?".equals(site.getGrade())) failed.incrementAndGet();
                    else alive.incrementAndGet();
                    runOnUiThread(() -> {
                        adapter.notifyItemChanged(site);
                        tvProgress.setText(String.format("%d/%d · 可用 %d · %s [%s] %s",
                                d, total, alive.get(), site.getName(), site.getGrade(), site.display()));
                    });
                }));
            }
        } finally {
            checkPool.shutdown();
        }
        while (!checkPool.isTerminated()) {
            checkPool.awaitTermination(250, TimeUnit.MILLISECONDS);
        }
        double sec = (System.currentTimeMillis() - t0) / 1000.0;
        String summary = String.format("完成：%d 站 · 可用 %d · 失败 %d · 深度%d · 用时 %.1fs%s",
                total, alive.get(), failed.get(), depth, sec, stopRequested ? "（已停止）" : "");
        runOnUiThread(() -> tvProgress.setText(summary));
    }

    // ---------------- 单站重测 ----------------

    /** 单击列表项：只测这一个站点（用当前选中的深度），不用等整轮跑完。 */
    private void checkOne(Site site) {
        final int depth = depth();
        runTask(() -> {
            site.setGrade("?");
            site.setResult("测活中…");
            site.setDepth(depth);
            site.setElapsedMs(-1);
            runOnUiThread(() -> {
                adapter.notifyItemChanged(site);
                tvProgress.setText("单测：" + site.getName() + "（深度" + depth + "）");
            });
            try {
                Checker.check(site, depth);
            } catch (Throwable e) {
                site.setGrade("D");
                site.setResult("异常：" + msg(e));
            }
            runOnUiThread(() -> {
                adapter.notifyItemChanged(site);
                tvProgress.setText(String.format("单测完成：%s [%s] %s", site.getName(), site.getGrade(), site.display()));
            });
        });
    }

    // ---------------- 导出 ----------------

    private void doExport() {
        if (loader.getSites().isEmpty()) {
            toast("无结果可导出");
            return;
        }
        exportLauncher.launch("check_result.json");
    }

    private void writeOut(Uri uri) throws Exception {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (Site s : loader.getSites()) {
            if (!first) sb.append(",");
            sb.append(s.toJson().toString());
            first = false;
        }
        sb.append("]");
        try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        }
        // 同时留一份到应用目录，方便 adb/文件管理器取
        try {
            File out = new File(getExternalFilesDir(null), "check_result.json");
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(out)) {
                fos.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {
        }
        runOnUiThread(() -> toast("已导出 " + uri.getLastPathSegment()));
    }

    // ---------------- UA 设置 ----------------

    private void showUaDialog() {
        String[] items = new String[Ua.CANDIDATES.length + 1];
        System.arraycopy(Ua.CANDIDATES, 0, items, 0, Ua.CANDIDATES.length);
        items[Ua.CANDIDATES.length] = "自定义…";
        new AlertDialog.Builder(this)
                .setTitle("User-Agent（当前：" + Ua.current() + "）")
                .setMessage("部分站点/接口会校验 UA，选一个可用的即可。拉取配置时也会自动逐个嗅探。")
                .setItems(items, (dlg, which) -> {
                    if (which == Ua.CANDIDATES.length) showUaInput();
                    else {
                        Ua.apply(items[which]);
                        toast("已设为 " + items[which]);
                    }
                })
                .setNeutralButton("恢复默认", (dlg, w) -> {
                    Ua.apply("");
                    toast("已恢复 OkHttp 默认 UA");
                })
                .show();
    }

    private void showUaInput() {
        EditText et = new EditText(this);
        String cur = Ua.current();
        et.setText(cur.startsWith("（") ? "" : cur);
        et.setHint("例如 okhttp/3.12.13");
        new AlertDialog.Builder(this)
                .setTitle("自定义 User-Agent")
                .setView(et)
                .setPositiveButton("确定", (dlg, w) -> {
                    Ua.apply(et.getText().toString());
                    toast("已设为 " + Ua.current());
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ---------------- 诊断 ----------------

    private void doDiag() {
        progress("诊断中…（含 UA 探测，稍等几秒）");
        StringBuilder sb = new StringBuilder();
        try {
            String s = OkHttp.string("https://www.baidu.com");
            sb.append("网络：✓ 通（").append(s.length()).append(" 字节）\n");
        } catch (Throwable e) {
            sb.append("网络：✗ ").append(msg(e)).append("\n");
        }
        try {
            new com.fongmi.chaquo.Loader();
            sb.append("Python 引擎：✓ 启动成功\n");
        } catch (Throwable e) {
            sb.append("Python 引擎：✗ ").append(msg(e)).append("\n");
        }
        try {
            new com.fongmi.quickjs.crawler.Loader();
            sb.append("JS 引擎：✓ 启动成功\n");
        } catch (Throwable e) {
            sb.append("JS 引擎：✗ ").append(msg(e)).append("\n");
        }
        try {
            sb.append("缓存目录：").append(Path.root().getAbsolutePath()).append("\n");
            sb.append("jar 目录：").append(Path.jar().getAbsolutePath()).append("\n");
        } catch (Throwable e) {
            sb.append("缓存目录：✗ ").append(msg(e)).append("\n");
        }
        int n = loader.getSites().size();
        sb.append("已加载站点：").append(n).append("\n");
        sb.append("UA：").append(Ua.current()).append("\n");
        String cfgUrl = etConfig.getText().toString().trim();
        if (cfgUrl.startsWith("http")) {
            sb.append("\n【配置地址 UA 探测】\n").append(cfgUrl).append("\n");
            int limit = Math.min(4, Ua.CANDIDATES.length);
            for (int i = 0; i < limit; i++) {
                sb.append("  ").append(Ua.CANDIDATES[i]).append(" → ").append(Ua.probe(cfgUrl, Ua.CANDIDATES[i])).append("\n");
            }
            sb.append("（部分接口校验 UA：长按「诊断」换 UA，点「加载」会自动逐个嗅探）");
        }
        sb.append("\nABI：").append(android.os.Build.SUPPORTED_ABIS.length > 0 ? android.os.Build.SUPPORTED_ABIS[0] : "?").append("\n");
        sb.append("Android：").append(android.os.Build.VERSION.RELEASE).append("（API ").append(android.os.Build.VERSION.SDK_INT).append("）");
        String text = sb.toString();
        runOnUiThread(() -> new AlertDialog.Builder(this)
                .setTitle("运行环境自检")
                .setMessage(text)
                .setPositiveButton("知道了", null)
                .setNeutralButton("复制", (dlg, w) -> {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("diag", text));
                    toast("已复制");
                })
                .show());
    }

    private void doClear() {
        loader.getSites().clear();
        SiteLoader.get().clear();
        adapter.setItems(loader.getSites());
        tvProgress.setText("已清空");
    }

    private void showSiteDetail(Site site) {
        String text = "名称：" + site.getName()
                + "\nkey：" + site.getKey()
                + "\n类型：" + site.kindLabel()
                + "\napi：\n" + site.getApi()
                + "\n\njar：\n" + (TextUtils.isEmpty(site.getJar0()) ? "（无）" : site.getJar0())
                + "\n\next：\n" + (TextUtils.isEmpty(site.getExt0()) ? "（无）" : site.getExt0())
                + "\n\n深度：" + (site.getDepth() == 0 ? "未测" : String.valueOf(site.getDepth()))
                + "\n等级：" + site.getGrade()
                + "\n耗时：" + (site.getElapsedMs() < 0 ? "—" : site.getElapsedMs() + "ms")
                + "\n结果：" + site.getResult();
        new AlertDialog.Builder(this)
                .setTitle(site.getName())
                .setMessage(text)
                .setPositiveButton("重测该站", (dlg, w) -> checkOne(site))
                .setNegativeButton("知道了", null)
                .setNeutralButton("复制", (dlg, w) -> {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("site", text));
                    toast("已复制");
                })
                .show();
    }

    private String readText(Uri uri) throws Exception {
        try (InputStream in = getContentResolver().openInputStream(uri); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }

    private void progress(String text) {
        runOnUiThread(() -> tvProgress.setText(text));
    }

    private void fail(Throwable e) {
        runOnUiThread(() -> {
            tvProgress.setText("出错：" + msg(e));
            toast(msg(e));
        });
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show();
    }

    private String msg(Throwable e) {
        String m = e.getMessage();
        return m == null || m.isEmpty() ? e.getClass().getSimpleName() : m;
    }
}
