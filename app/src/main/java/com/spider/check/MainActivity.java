package com.spider.check;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.text.TextUtils;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
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

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class MainActivity extends AppCompatActivity {

    /** 并发测活线程数：太快容易被目标站限流，6 是速度与稳定性的平衡点。 */
    private static final int CHECK_THREADS = 6;
    /** 跑动中保存快照的最小间隔（毫秒），避免频繁写盘。 */
    private static final long SAVE_INTERVAL_MS = 3000;

    private static final String[] DEPTH_LABELS = {
            "① 仅首页（最快）",
            "② 首页 + 分类",
            "③ 到详情",
            "④ 全链路（含播放）"
    };

    private EditText etConfig;
    private TextView tvProgress;
    private TextView tvSel;
    private Button btnSelAll;
    private Spinner spDepth;
    private RecyclerView recycler;
    private SiteAdapter adapter;
    private final ConfigLoader loader = new ConfigLoader();
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private volatile boolean running = false;
    private volatile boolean stopRequested = false;

    /** 本轮测活的线程池 / 任务句柄 —— 强制停止时用来立即取消。 */
    private volatile ExecutorService checkPool;
    private final List<Future<?>> inflight = new CopyOnWriteArrayList<>();
    private volatile List<Site> runSites;

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
        tvSel = findViewById(R.id.tvSel);
        btnSelAll = findViewById(R.id.btnSelAll);
        spDepth = findViewById(R.id.spDepth);
        recycler = findViewById(R.id.recycler);

        ArrayAdapter<String> depthAdapter = new ArrayAdapter<>(this, R.layout.item_spinner, DEPTH_LABELS);
        depthAdapter.setDropDownViewResource(R.layout.item_spinner_dropdown);
        spDepth.setAdapter(depthAdapter);

        adapter = new SiteAdapter();
        adapter.setOnLongClick(this::showSiteDetail);
        adapter.setOnItemClick(this::checkOne);
        adapter.setOnSelectChange(this::updateSel);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        recycler.setAdapter(adapter);
        findViewById(R.id.btnLoad).setOnClickListener(v -> runTask(this::doLoad));
        findViewById(R.id.btnImport).setOnClickListener(v -> importLauncher.launch(new String[]{"application/json", "text/plain", "application/octet-stream", "*/*"}));
        findViewById(R.id.btnRun).setOnClickListener(v -> runTask(this::doRun));
        findViewById(R.id.btnStop).setOnClickListener(v -> doStop());
        findViewById(R.id.btnExport).setOnClickListener(v -> doExport());
        findViewById(R.id.btnDiag).setOnClickListener(v -> runTask(this::doDiag));
        // 长按「诊断」= UA 设置（部分接口校验 User-Agent）
        findViewById(R.id.btnDiag).setOnLongClickListener(v -> {
            showUaDialog();
            return true;
        });
        findViewById(R.id.btnClear).setOnClickListener(v -> doClear());
        btnSelAll.setOnClickListener(v -> toggleSelectAll());
        findViewById(R.id.btnSelUntested).setOnClickListener(v -> selectUntested());

        importLauncher = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
            if (uri == null) return;
            runTask(() -> doImport(uri));
        });
        exportLauncher = registerForActivityResult(new ActivityResultContracts.CreateDocument("application/json"), uri -> {
            if (uri == null) return;
            runTask(() -> writeOut(uri));
        });

        restoreState();
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
        progress("拉取配置…（会自动嗅探可用 UA）");
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
            updateSel();
            tvProgress.setText(String.format("已加载 %d 站点（py=%d js=%d jar=%d 接口=%d）· 勾选后点「开始测活」", n, py, js, jar, other));
        });
        save(false);
    }

    // ---------------- 勾选 ----------------

    /** 刷新「已选 n/m」与全选按钮文案。 */
    private void updateSel() {
        int total = loader.getSites().size();
        int sel = 0;
        for (Site s : loader.getSites()) if (s.isSelected()) sel++;
        tvSel.setText("已选 " + sel + "/" + total);
        btnSelAll.setText(total > 0 && sel == total ? "全不选" : "全选");
    }

    private void toggleSelectAll() {
        List<Site> sites = loader.getSites();
        if (sites.isEmpty()) return;
        int sel = 0;
        for (Site s : sites) if (s.isSelected()) sel++;
        boolean target = sel != sites.size();
        for (Site s : sites) s.setSelected(target);
        adapter.notifyDataSetChanged();
        updateSel();
        save(false);
    }

    /** 只勾选「还没测过」或「测出失败（D）」的站点——续测/重测失败站点用。 */
    private void selectUntested() {
        List<Site> sites = loader.getSites();
        if (sites.isEmpty()) return;
        int n = 0;
        for (Site s : sites) {
            boolean want = !s.tested() || "D".equals(s.getGrade());
            s.setSelected(want);
            if (want) n++;
        }
        adapter.notifyDataSetChanged();
        updateSel();
        toast("已选 " + n + " 个未测/失败的站点");
    }

    // ---------------- 测活 ----------------

    private void doRun() throws Exception {
        final List<Site> all = loader.getSites();
        if (all.isEmpty()) throw new Exception("先加载或导入配置");
        final List<Site> sites = new ArrayList<>();
        for (Site s : all) if (s.isSelected()) sites.add(s);
        if (sites.isEmpty()) throw new Exception("没有勾选任何站点：点站点左侧复选框，或点「全选」");
        final int depth = depth();
        stopRequested = false;
        runSites = sites;
        SiteLoader.get().clear();
        long t0 = System.currentTimeMillis();
        for (Site s : sites) {
            s.setGrade("?");
            s.setResult("排队中");
            s.setDepth(depth);
            s.setElapsedMs(-1);
        }
        final int total = sites.size();
        runOnUiThread(() -> {
            adapter.notifyDataSetChanged();
            updateSel();
        });

        // jar 源共用同一个 jar：先预热下载，避免 6 个线程抢锁串行等待
        String warm = null;
        for (Site s : sites) {
            if (s.kindLabel().equals("jar") && !TextUtils.isEmpty(s.getJar0())) {
                warm = s.getJar0();
                break;
            }
        }
        if (warm != null && warm.startsWith("http")) {
            progress("预取 jar（首次约 1-2MB，稍等）…");
            SiteLoader.get().warmUp(warm);
        }

        final AtomicInteger done = new AtomicInteger();
        final AtomicInteger alive = new AtomicInteger();
        final AtomicInteger failed = new AtomicInteger();
        final long[] lastSave = {System.currentTimeMillis()};
        checkPool = Executors.newFixedThreadPool(CHECK_THREADS);
        inflight.clear();
        try {
            for (final Site site : sites) {
                Future<?> f = checkPool.submit(() -> {
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
                        if (running && !stopRequested) {
                            tvProgress.setText(String.format("%d/%d · 可用 %d · %s [%s] %s",
                                    d, total, alive.get(), site.getName(), site.getGrade(), site.display()));
                        }
                    });
                    if (System.currentTimeMillis() - lastSave[0] > SAVE_INTERVAL_MS) {
                        lastSave[0] = System.currentTimeMillis();
                        save(false);
                    }
                });
                inflight.add(f);
            }
        } finally {
            checkPool.shutdown();
        }
        while (!checkPool.isTerminated()) {
            if (stopRequested) break;   // 强制停止：不再等剩余超时
            checkPool.awaitTermination(200, TimeUnit.MILLISECONDS);
        }
        double sec = (System.currentTimeMillis() - t0) / 1000.0;
        int testedN = 0, untestedN = 0;
        for (Site s : sites) {
            if ("?".equals(s.getGrade())) untestedN++;
            else testedN++;
        }
        final int fTested = testedN, fUntested = untestedN;
        String summary = String.format("%s：勾选 %d 站 · 完成 %d · 未测 %d · 可用 %d · 失败 %d · 深度%d · 用时 %.1fs",
                stopRequested ? "已强制停止" : "完成", total, fTested, fUntested, alive.get(), failed.get(), depth, sec);
        runOnUiThread(() -> {
            if (!running || !stopRequested) tvProgress.setText(summary);
        });
        save(false);
    }

    /** 强制停止：取消在飞任务 + 取消所有 HTTP 请求，不等超时。 */
    private void doStop() {
        if (!running) {
            toast("当前没有测活任务");
            return;
        }
        stopRequested = true;
        for (Future<?> f : inflight) f.cancel(true);
        inflight.clear();
        try {
            OkHttp.client().dispatcher().cancelAll();
        } catch (Throwable ignored) {
        }
        ExecutorService cp = checkPool;
        if (cp != null) cp.shutdownNow();
        List<Site> sites = runSites;
        int done = 0, rest = 0;
        if (sites != null) {
            for (Site s : sites) {
                if ("?".equals(s.getGrade())) {
                    s.setResult("已停止（未测）");
                    rest++;
                } else done++;
            }
        }
        running = false;
        adapter.notifyDataSetChanged();
        updateSel();
        tvProgress.setText(String.format("已强制停止：完成 %d · 未测 %d · 点「开始测活」只测勾选的站点即可续测", done, rest));
        toast("已停止");
        save(false);
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
            save(false);
        });
    }

    // ---------------- 断点续测 ----------------

    /** App 启动时若有上次未完成的快照，询问是否继续。 */
    private void restoreState() {
        pool.execute(() -> {
            final JSONObject st = Store.load(this);
            if (st == null) return;
            final int depth = Math.max(1, Math.min(st.optInt("depth", 1), DEPTH_LABELS.length));
            final String url = st.optString("url", "");
            final boolean interrupted = st.optBoolean("interrupted", false);
            JSONArray arr = st.optJSONArray("sites");
            if (arr == null || arr.length() == 0) return;
            final List<Site> list = new ArrayList<>();
            int tested = 0;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                Site s = Site.fromState(o);
                if (s.tested()) tested++;
                list.add(s);
            }
            if (list.isEmpty()) return;
            final int fTested = tested;
            final int rest = list.size() - tested;
            runOnUiThread(() -> new AlertDialog.Builder(this)
                    .setTitle("继续上次任务")
                    .setMessage(String.format("检测到上次的快照：共 %d 站，已完成 %d，未测 %d。\n深度：%s%s\n\n「继续测活」= 从断点接着测（离线可用，不必重新拉配置）",
                            list.size(), fTested, rest, DEPTH_LABELS[depth - 1], interrupted ? "（上次未跑完）" : ""))
                    .setPositiveButton("继续测活", (d, w) -> {
                        applyRestored(list, depth, url, true);
                        runTask(this::doRun);
                    })
                    .setNeutralButton("只看结果", (d, w) -> applyRestored(list, depth, url, false))
                    .setNegativeButton("重新开始", (d, w) -> {
                        Store.clear(this);
                        toast("已清除快照");
                    })
                    .show());
        });
    }

    private void applyRestored(List<Site> list, int depth, String url, boolean onlyUntested) {
        loader.getSites().clear();
        loader.getSites().addAll(list);
        if (!TextUtils.isEmpty(url) && !url.equals("本地文件")) etConfig.setText(url);
        spDepth.setSelection(Math.max(0, Math.min(depth - 1, DEPTH_LABELS.length - 1)));
        for (Site s : list) s.setSelected(onlyUntested ? (!s.tested() || "D".equals(s.getGrade())) : true);
        SiteLoader.get().clear();
        adapter.setItems(loader.getSites());
        updateSel();
        int sel = 0;
        for (Site s : list) if (s.isSelected()) sel++;
        tvProgress.setText(String.format("已恢复 %d 站快照（已选 %d）· 点「开始测活」继续", list.size(), sel));
    }

    /** 保存任务快照（写文件，可在后台线程调用）。 */
    private void save(boolean ignored) {
        try {
            List<Site> copy = new ArrayList<>(loader.getSites());
            if (copy.isEmpty()) return;
            String url = etConfig == null ? "" : etConfig.getText().toString().trim();
            int d = spDepth == null || spDepth.getSelectedItemPosition() < 0 ? 1 : spDepth.getSelectedItemPosition() + 1;
            Store.save(this, url, d, running && !stopRequested, copy);
        } catch (Throwable ignored2) {
        }
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
                .setMessage("部分站点/接口会校验 UA，选一个可用的即可。拉取配置与测活都会自动逐个嗅探。")
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
        Store.clear(this);
        adapter.setItems(loader.getSites());
        updateSel();
        tvProgress.setText("已清空（含本地快照）");
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

    @Override
    protected void onStop() {
        super.onStop();
        save(false);
    }
}
