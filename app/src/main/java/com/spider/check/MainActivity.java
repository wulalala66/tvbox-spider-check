package com.spider.check;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private EditText etConfig;
    private TextView tvProgress;
    private RecyclerView recycler;
    private SiteAdapter adapter;
    private final ConfigLoader loader = new ConfigLoader();
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private volatile boolean running = false;

    private ActivityResultLauncher<String[]> importLauncher;

    @SuppressLint("SetTextI18n")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        applyWindowInsets();
        etConfig = findViewById(R.id.etConfig);
        tvProgress = findViewById(R.id.tvProgress);
        recycler = findViewById(R.id.recycler);
        adapter = new SiteAdapter();
        recycler.setLayoutManager(new LinearLayoutManager(this));
        recycler.setAdapter(adapter);
        Button btnLoad = findViewById(R.id.btnLoad);
        Button btnImport = findViewById(R.id.btnImport);
        Button btnRun = findViewById(R.id.btnRun);
        Button btnExport = findViewById(R.id.btnExport);
        btnLoad.setOnClickListener(v -> runTask(this::doLoad));
        btnRun.setOnClickListener(v -> runTask(this::doRun));
        btnExport.setOnClickListener(v -> runTask(this::doExport));
        // 本地导入：SAF 打开 .json/.txt 配置文件
        importLauncher = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
            if (uri == null) return;
            runTask(() -> doImport(uri));
        });
        btnImport.setOnClickListener(v -> importLauncher.launch(new String[]{"*/*"}));
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
        setBusy(true);
        pool.execute(() -> {
            try {
                task.run();
            } catch (Throwable e) {
                fail(e);
            } finally {
                setBusy(false);
            }
        });
    }

    private interface Task {
        void run() throws Exception;
    }

    private void doLoad() throws Exception {
        String url = etConfig.getText().toString().trim();
        if (url.isEmpty()) throw new Exception("请输入配置地址，或点「导入」选本地文件");
        progress("拉取配置…");
        int n = loader.load(url);
        loaded(n);
    }

    private void doImport(Uri uri) throws Exception {
        progress("读取本地文件…");
        String text = readText(uri);
        int n = loader.loadText(text, "");
        loaded(n);
    }

    private void loaded(int n) {
        runOnUiThread(() -> {
            adapter.setItems(loader.getSites());
            long py = loader.getSites().stream().filter(s -> s.kindLabel().equals("py")).count();
            long js = loader.getSites().stream().filter(s -> s.kindLabel().equals("js")).count();
            long jar = loader.getSites().stream().filter(s -> s.kindLabel().equals("jar")).count();
            tvProgress.setText(String.format("已加载 %d 站点（py=%d js=%d jar=%d），点「测活」开始", n, py, js, jar));
        });
    }

    private void doRun() throws Exception {
        List<Site> sites = loader.getSites();
        if (sites.isEmpty()) throw new Exception("先加载或导入配置");
        int total = sites.size();
        int[] done = {0};
        for (Site site : sites) {
            runOnUiThread(() -> tvProgress.setText(String.format("测活中 %d/%d %s", done[0] + 1, total, site.getName())));
            Checker.check(site);
            done[0]++;
            runOnUiThread(() -> {
                adapter.notifyItemChanged(site);
                tvProgress.setText(String.format("%d/%d 完成", done[0], total));
            });
        }
        long a = sites.stream().filter(s -> s.getGrade().equals("A")).count();
        long b = sites.stream().filter(s -> s.getGrade().equals("B")).count();
        long c = sites.stream().filter(s -> s.getGrade().equals("C")).count();
        long d = sites.stream().filter(s -> s.getGrade().equals("D")).count();
        String summary = String.format("完成：A=%d B=%d C=%d D=%d（存活 %d/%d）", a, b, c, d, a + b + c, total);
        runOnUiThread(() -> tvProgress.setText(summary));
    }

    private void doExport() throws Exception {
        List<Site> sites = loader.getSites();
        if (sites.isEmpty()) throw new Exception("无结果可导出");
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (Site s : sites) {
            if (!first) sb.append(",");
            sb.append(s.toJson().toString());
            first = false;
        }
        sb.append("]");
        File out = new File(getExternalFilesDir(null), "check_result.json");
        try (OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(out), StandardCharsets.UTF_8)) {
            w.write(sb.toString());
        }
        runOnUiThread(() -> toast("已导出: " + out.getAbsolutePath()));
    }

    private String readText(Uri uri) throws Exception {
        try (InputStream in = getContentResolver().openInputStream(uri); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }

    private void setBusy(boolean busy) {
        running = busy;
        runOnUiThread(() -> {
            int id = busy ? View.GONE : View.VISIBLE;
            // 忙碌时不禁用控件（简单可靠），仅靠 running 标志防重入
        });
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
