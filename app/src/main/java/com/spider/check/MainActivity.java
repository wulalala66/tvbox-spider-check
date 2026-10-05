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

    /** 从历史页跳回来时用：载入某个接口的结果。 */
    public static final String ACTION_LOAD_HISTORY = "com.spider.check.LOAD_HISTORY";
    /** 接口标识（配置地址 / local:文件名）。 */
    public static final String EXTRA_TAG = "tag";

    private EditText etConfig;
    private TextView tvProgress;
    private TextView tvSel;
    private Button btnSelAll;
    private Button btnFAll, btnFAlive, btnFDead, btnFUntested;
    private Spinner spDepth;
    private RecyclerView recycler;
    private SiteAdapter adapter;
    /** 本地导入的文件名，作为「接口」标识用。 */
    private String lastLocalName = "";
    /** 是否已经弹过存储权限引导（避免反复打扰）。 */
    private boolean storageAsked = false;
    private final ConfigLoader loader = new ConfigLoader();
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private volatile boolean running = false;
    private volatile boolean stopRequested = false;

    /** 本轮测活的线程池 / 任务句柄 —— 强制停止时用来立即取消。 */
    private volatile ExecutorService checkPool;
    private final List<Future<?>> inflight = new CopyOnWriteArrayList<>();
    private volatile List<Site> runSites;

    private ActivityResultLauncher<String[]> importLauncher;
    private ActivityResultLauncher<String> saveTextLauncher;
    private ActivityResultLauncher<String> saveJsonLauncher;
    /** 正在导出的内容与格式（保存对话框返回时要用来写文件）。 */
    private History.Entry pendingEntry;
    private String pendingFormat = "json";

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

        // 结果分类筛选：可用 / 失败 / 未测；长按「全部」回到分组视图
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
            updateFilterUi();
            toast("已按「可用 / 失败 / 未测」分组显示");
            return true;
        });
        findViewById(R.id.btnHistory).setOnClickListener(v ->
                startActivity(new Intent(this, HistoryActivity.class)));

        importLauncher = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
            if (uri == null) return;
            runTask(() -> doImport(uri));
        });
        saveTextLauncher = registerForActivityResult(new ActivityResultContracts.CreateDocument("text/plain"), uri -> {
            if (uri == null) return;
            runTask(() -> writeOut(uri));
        });
        saveJsonLauncher = registerForActivityResult(new ActivityResultContracts.CreateDocument("application/json"), uri -> {
            if (uri == null) return;
            runTask(() -> writeOut(uri));
        });

        if (getIntent() != null && ACTION_LOAD_HISTORY.equals(getIntent().getAction())) {
            loadHistory(getIntent().getStringExtra(EXTRA_TAG), true);
        } else {
            restoreState();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && ACTION_LOAD_HISTORY.equals(intent.getAction())) {
            loadHistory(intent.getStringExtra(EXTRA_TAG), true);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从系统设置页授权回来后刷新状态
        if (storageAsked && Perm.ok(this)) {
            storageAsked = false;
            if (tvProgress != null) tvProgress.setText("已获得存储权限，本地源现在可以读取了 · 点「开始测活」");
            toast("存储权限已就绪");
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @androidx.annotation.NonNull String[] permissions, @androidx.annotation.NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != 1001) return;
        boolean ok = grantResults.length > 0 && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED;
        toast(ok ? "存储权限已就绪" : "未授予存储权限，本地源文件将无法读取");
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
        lastLocalName = "";
        progress("拉取配置…（会自动嗅探可用 UA）");
        int n = loader.load(url);
        SiteLoader.get().clear();
        loaded(n, url);
    }

    private void doImport(Uri uri) throws Exception {
        progress("读取本地文件…");
        String base = localBase(uri);
        String text = readText(uri);
        String name = uri.getLastPathSegment();
        lastLocalName = TextUtils.isEmpty(name) ? "本地文件" : Uri.decode(name);
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
            adapter.setGrouped(false);
            adapter.setFilter(SiteAdapter.FILTER_ALL);
            adapter.setItems(loader.getSites());
            long py = loader.getSites().stream().filter(s -> s.kindLabel().equals("py")).count();
            long js = loader.getSites().stream().filter(s -> s.kindLabel().equals("js")).count();
            long jar = loader.getSites().stream().filter(s -> s.kindLabel().equals("jar")).count();
            long other = n - py - js - jar;
            updateSel();
            updateFilterUi();
            tvProgress.setText(String.format("已加载 %d 站点（py=%d js=%d jar=%d 接口=%d）· 勾选后点「开始测活」", n, py, js, jar, other));
        });
        save(false);
        maybeAskStorage();
    }

    // ---------------- 结果分类筛选 ----------------

    private void setFilter(int f) {
        adapter.setGrouped(false);
        adapter.setFilter(f);
        updateFilterUi();
    }

    /** 刷新「全部/可用/失败/未测」按钮的计数与选中态。 */
    private void updateFilterUi() {
        if (btnFAll == null) return;
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

    // ---------------- 存储权限（本地 py/js/jar 源需要） ----------------

    /** 站点列表里是否有本地文件源（file:/… 或 /storage/…）。 */
    private boolean hasLocalSource() {
        for (Site s : loader.getSites()) {
            String a = s.getApi();
            if (a == null) continue;
            if (a.startsWith("file:") || a.startsWith("/") || a.startsWith(".")) return true;
        }
        return false;
    }

    /** 本应用专属目录：放这里不需要任何存储权限。 */
    private String privateDir() {
        File f = getExternalFilesDir(null);
        return f == null ? "Android/data/" + getPackageName() + "/files/" : f.getAbsolutePath();
    }

    /** 配置里有本地源但没权限时，引导授予「所有文件访问权限」。 */
    private void maybeAskStorage() {
        if (!hasLocalSource() || Perm.ok(this) || storageAsked) return;
        runOnUiThread(this::askStoragePermission);
    }

    private void askStoragePermission() {
        if (Perm.ok(this)) return;
        storageAsked = true;
        if (Perm.needsLegacy()) {
            requestPermissions(new String[]{Perm.legacyPermission()}, 1001);
            return;
        }
        if (!Perm.canAskAllFiles(this)) {
            toast("系统没有提供「所有文件访问权限」入口，请把源文件放到：" + privateDir());
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("需要「所有文件访问权限」")
                .setMessage("配置里的本地源是共享存储下的绝对路径，例如：\nfile:/storage/emulated/0/Download/QQ/tvbox/py/萝卜影视.py\n\nAndroid 11 起用绝对路径读这类文件必须开「所有文件访问权限」，否则本地 py/js/jar 源一律会报「文件不存在 / 无法读取」。\n\n点「去授权」后，在列表里找到「源测活」，打开「允许访问所有文件」开关（以前开关是灰的，是因为应用没声明这个权限，现在已经声明了）。\n\n不想给这个权限的话，把源文件复制到这里即可，此目录免权限：\n" + privateDir())
                .setPositiveButton("去授权", (d, w) -> askStorageNow())
                .setNeutralButton("复制目录", (d, w) -> {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("dir", privateDir()));
                    toast("已复制目录路径");
                })
                .setNegativeButton("稍后", null)
                .show();
    }

    private void askStorageNow() {
        try {
            startActivity(Perm.appIntent(this));
        } catch (Throwable t) {
            try {
                startActivity(Perm.allIntent());
            } catch (Throwable t2) {
                toast("打不开设置页：" + msg(t2));
            }
        }
    }

    // ---------------- 测活历史（按接口分组，互不覆盖） ----------------

    /** 当前接口标识：配置地址，或 local:本地文件名。 */
    private String tag() {
        String url = etConfig == null ? "" : etConfig.getText().toString().trim();
        if (!TextUtils.isEmpty(url)) return url;
        String body = historyBody();
        if (!TextUtils.isEmpty(body)) return body;
        return "本地导入";
    }

    private String historyBody() {
        return TextUtils.isEmpty(lastLocalName) ? "" : "local:" + lastLocalName;
    }

    /** 展示用标题：域名或文件名。 */
    private String title() {
        String url = etConfig == null ? "" : etConfig.getText().toString().trim();
        if (!TextUtils.isEmpty(url)) {
            try {
                String host = Uri.parse(url).getHost();
                if (!TextUtils.isEmpty(host)) return host;
            } catch (Throwable ignored) {
            }
            return url;
        }
        return TextUtils.isEmpty(lastLocalName) ? "本地导入" : lastLocalName;
    }

    /** 把当前结果写进该接口的历史（同一个接口只保留最新一条）。 */
    private void saveHistory(boolean interrupted) {
        try {
            List<Site> all = new ArrayList<>(loader.getSites());
            if (all.isEmpty()) return;
            int tested = 0;
            for (Site s : all) if (s.category() != Site.CAT_UNTESTED) tested++;
            if (tested == 0 && !interrupted) return;   // 一个都没测过就别留空档
            History.save(this, tag(), title(), depth(), interrupted, all);
        } catch (Throwable ignored) {
        }
    }

    /** 载入某个接口的历史结果到主界面（可继续测活）。 */
    private void loadHistory(String tag, boolean tip) {
        History.Entry e = History.get(this, tag);
        if (e == null || e.sites.isEmpty()) {
            if (tip) toast("找不到这条历史记录");
            return;
        }
        loader.getSites().clear();
        loader.getSites().addAll(e.sites);
        if (tag != null && tag.startsWith("http")) etConfig.setText(tag);
        else if (tag != null && tag.startsWith("local:")) lastLocalName = tag.substring("local:".length());
        spDepth.setSelection(Math.max(0, Math.min(e.depth - 1, DEPTH_LABELS.length - 1)));
        // 默认勾选「失败 + 未测」的站点，方便直接续测
        for (Site s : e.sites) s.setSelected(s.category() != Site.CAT_ALIVE);
        SiteLoader.get().clear();
        adapter.setFilter(SiteAdapter.FILTER_ALL);
        adapter.setGrouped(true);
        adapter.setItems(loader.getSites());
        updateSel();
        updateFilterUi();
        tvProgress.setText(String.format("历史结果（%s · %s · 深度%s）· 共 %d 站：可用 %d · 失败 %d · 未测 %d",
                e.displayTitle(), e.savedAt, HistoryAdapter.depthName(e.depth), e.total,
                e.count(Site.CAT_ALIVE), e.count(Site.CAT_DEAD), e.count(Site.CAT_UNTESTED)));
    }

    private void saveHistory() {
        saveHistory(false);
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
        // 本地 py/js/jar 源在共享存储里：没「所有文件访问权限」读不了，先引导授权
        if (hasLocalSource() && !Perm.ok(this)) {
            runOnUiThread(this::askStoragePermission);
            throw new Exception("本地源需要「所有文件访问权限」：正在打开设置页，打开开关后再点「开始测活」");
        }
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
            // 测活进行中先平铺（顺序稳定，不会因为状态变化到处跳），跑完再自动分组
            adapter.setFilter(SiteAdapter.FILTER_ALL);
            adapter.setGrouped(false);
            updateSel();
            updateFilterUi();
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
                        adapter.refresh(site);
                        updateFilterUi();
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
            // 跑完自动按「✅ 可用 / ❌ 失败 / ⚪ 未测」分组展示
            adapter.setFilter(SiteAdapter.FILTER_ALL);
            adapter.setGrouped(true);
            updateFilterUi();
            if (!running || !stopRequested) tvProgress.setText(summary);
        });
        save(false);
        saveHistory(stopRequested);
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
        adapter.setGrouped(true);
        updateSel();
        updateFilterUi();
        tvProgress.setText(String.format("已强制停止：完成 %d · 未测 %d · 结果已存档；点「开始测活」只测勾选的站点即可续测", done, rest));
        toast("已停止");
        save(false);
        saveHistory(true);
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
                adapter.setGrouped(true);
                updateFilterUi();
                tvProgress.setText(String.format("单测完成：%s [%s] %s", site.getName(), site.getGrade(), site.display()));
            });
            save(false);
            saveHistory(false);
        });
    }

    // ---------------- 断点续测 ----------------

    /** App 启动时若有上次未完成的快照，询问是否继续。 */
    private void restoreState() {
        pool.execute(() -> {
            final JSONObject st = Store.load(this);
            if (st == null) {
                // 没有未完成的任务 → 直接把最近一次测活结果回显出来（清单会一直留着）
                final History.Entry latest = History.latest(this);
                if (latest != null) runOnUiThread(() -> loadHistory(latest.tag, false));
                return;
            }
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
        exportMenu(currentEntry());
    }

    /** 把主界面当前列表包装成一条「历史记录」结构，导出用。 */
    private History.Entry currentEntry() {
        History.Entry e = new History.Entry();
        e.tag = tag();
        e.title = title();
        e.ts = System.currentTimeMillis();
        e.savedAt = History.stamp(e.ts);
        e.depth = depth();
        e.interrupted = running && !stopRequested;
        e.sites = new ArrayList<>(loader.getSites());
        e.total = e.sites.size();
        return e;
    }

    /** 导出菜单：报告比裸 JSON 有用得多。 */
    private void exportMenu(History.Entry e) {
        String[] items = {
                "复制报告（Markdown，可直接粘贴）",
                "分享报告（Markdown 文本）",
                "保存报告（Markdown .md）",
                "保存报告（网页 .html，浏览器打开最好看）",
                "保存 JSON（已格式化，带汇总字段）"
        };
        new AlertDialog.Builder(this)
                .setTitle("导出 · " + e.displayTitle())
                .setMessage(String.format("共 %d 站：可用 %d · 失败 %d · 未测 %d",
                        e.total, e.count(Site.CAT_ALIVE), e.count(Site.CAT_DEAD), e.count(Site.CAT_UNTESTED)))
                .setItems(items, (d, w) -> {
                    switch (w) {
                        case 0:
                            copyReport(e);
                            break;
                        case 1:
                            shareReport(e);
                            break;
                        case 2:
                            saveReport(e, "md");
                            break;
                        case 3:
                            saveReport(e, "html");
                            break;
                        default:
                            saveReport(e, "json");
                            break;
                    }
                })
                .show();
    }

    private void copyReport(History.Entry e) {
        String text = Report.markdown(e);
        android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(android.content.ClipData.newPlainText("report", text));
        toast("报告已复制（" + text.length() + " 字符）");
    }

    private void shareReport(History.Entry e) {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_SUBJECT, "源测活报告 · " + e.displayTitle());
        i.putExtra(Intent.EXTRA_TEXT, Report.markdown(e));
        try {
            startActivity(Intent.createChooser(i, "分享测活报告"));
        } catch (Throwable t) {
            toast("没有可分享的应用，已改为复制到剪贴板");
            copyReport(e);
        }
    }

    private void saveReport(History.Entry e, String format) {
        pendingEntry = e;
        pendingFormat = format;
        String name = Report.fileName(e, format);
        if ("json".equals(format)) saveJsonLauncher.launch(name);
        else saveTextLauncher.launch(name);
    }

    private void writeOut(Uri uri) throws Exception {
        History.Entry e = pendingEntry != null ? pendingEntry : currentEntry();
        String format = pendingFormat;
        String text = Report.build(e, format);
        try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
        // 同时留一份到应用目录，方便文件管理器 / adb 取
        try {
            File out = new File(getExternalFilesDir(null), "check_report." + format);
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(out)) {
                fos.write(text.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {
        }
        runOnUiThread(() -> {
            toast("已导出 " + uri.getLastPathSegment());
            tvProgress.setText("已导出 " + format.toUpperCase() + "：" + uri.getLastPathSegment()
                    + "\n应用目录也留了一份：check_report." + format);
        });
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
        sb.append("存储权限：").append(Perm.status(this)).append("\n");
        sb.append("本地源：").append(hasLocalSource() ? "有（需要存储权限）" : "无").append("\n");
        sb.append("专属目录：").append(privateDir()).append("\n");
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
        adapter.setGrouped(false);
        adapter.setFilter(SiteAdapter.FILTER_ALL);
        adapter.setItems(loader.getSites());
        updateSel();
        updateFilterUi();
        tvProgress.setText("已清空当前列表（历史记录仍保留在「历史」里）");
    }

    private void showSiteDetail(Site site) {
        String text = "名称：" + site.getName()
                + "\nkey：" + site.getKey()
                + "\n类型：" + site.kindLabel()
                + "\n分类：" + site.categoryName()
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
