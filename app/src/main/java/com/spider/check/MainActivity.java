package com.spider.check;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private EditText etConfig;
    private TextView tvProgress;
    private RecyclerView recycler;
    private SiteAdapter adapter;
    private final ConfigLoader loader = new ConfigLoader();
    private final ExecutorService pool = Executors.newFixedThreadPool(4);

    @SuppressLint("SetTextI18n")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        etConfig = findViewById(R.id.etConfig);
        tvProgress = findViewById(R.id.tvProgress);
        recycler = findViewById(R.id.recycler);
        adapter = new SiteAdapter();
        recycler.setLayoutManager(new LinearLayoutManager(this));
        recycler.setAdapter(adapter);
        Button btnLoad = findViewById(R.id.btnLoad);
        Button btnRun = findViewById(R.id.btnRun);
        Button btnExport = findViewById(R.id.btnExport);
        btnLoad.setOnClickListener(v -> runTask(this::doLoad));
        btnRun.setOnClickListener(v -> runTask(this::doRun));
        btnExport.setOnClickListener(v -> runTask(this::doExport));
    }

    private void runTask(Task task) {
        pool.execute(() -> {
            try {
                task.run();
            } catch (Throwable e) {
                runOnUiThread(() -> Toast.makeText(this, msg(e), Toast.LENGTH_LONG).show());
            }
        });
    }

    private interface Task {
        void run() throws Exception;
    }

    private void doLoad() throws Exception {
        String url = etConfig.getText().toString().trim();
        if (url.isEmpty()) throw new Exception("请输入配置地址");
        progress("拉取配置…");
        int n = loader.load(url);
        runOnUiThread(() -> {
            adapter.setItems(loader.getSites());
            tvProgress.setText(String.format("已加载 %d 个站点，点击「开始测活」", n));
        });
    }

    private void doRun() throws Exception {
        if (loader.getSites().isEmpty()) throw new Exception("先加载配置");
        java.util.List<Site> sites = new java.util.ArrayList<>(loader.getSites());
        int total = sites.size();
        for (int i = 0; i < total; i++) {
            Site site = sites.get(i);
            int idx = i + 1;
            runOnUiThread(() -> tvProgress.setText(String.format("测活中 %d/%d %s", idx, total, site.getName())));
            Checker.check(site);
            runOnUiThread(() -> {
                adapter.notifyItemChanged(site);
                tvProgress.setText(String.format("%d/%d 完成", idx, total));
            });
        }
        runOnUiThread(() -> tvProgress.setText("全部完成，可点击「导出结果」"));
    }

    private void doExport() throws Exception {
        if (loader.getSites().isEmpty()) throw new Exception("无结果可导出");
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (Site s : loader.getSites()) {
            if (!first) sb.append(",");
            sb.append(s.toJson().toString());
            first = false;
        }
        sb.append("]");
        File out = new File(getExternalFilesDir(null), "check_result.json");
        try (OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(out), StandardCharsets.UTF_8)) {
            w.write(sb.toString());
        }
        runOnUiThread(() -> Toast.makeText(this, "已导出: " + out.getAbsolutePath(), Toast.LENGTH_LONG).show());
    }

    private void progress(String text) {
        runOnUiThread(() -> tvProgress.setText(text));
    }

    private String msg(Throwable e) {
        String m = e.getMessage();
        return m == null || m.isEmpty() ? e.getClass().getSimpleName() : m;
    }
}
