package com.spider.check;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

/**
 * 测活历史：每个接口一条记录，互不覆盖。
 * 点卡片看完整结果；长按可「载入到主界面」（接着续测/重测）或删除。
 */
public class HistoryActivity extends AppCompatActivity {

    private RecyclerView recycler;
    private TextView empty;
    private HistoryAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_history);
        View root = findViewById(R.id.root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        recycler = findViewById(R.id.recycler);
        empty = findViewById(R.id.tvEmpty);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        adapter = new HistoryAdapter();
        adapter.setListener(new HistoryAdapter.Listener() {
            @Override
            public void onOpen(History.Entry e) {
                Intent i = new Intent(HistoryActivity.this, ResultActivity.class);
                i.putExtra(MainActivity.EXTRA_TAG, e.tag);
                startActivity(i);
            }

            @Override
            public void onMenu(History.Entry e) {
                menu(e);
            }
        });
        recycler.setAdapter(adapter);
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnClearAll).setOnClickListener(v -> confirmClearAll());
        load();
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    private void load() {
        List<History.Entry> list = History.all(this);
        adapter.setItems(list);
        boolean none = list.isEmpty();
        empty.setVisibility(none ? View.VISIBLE : View.GONE);
        recycler.setVisibility(none ? View.GONE : View.VISIBLE);
    }

    /** 长按卡片：载入 / 删除。 */
    private void menu(History.Entry e) {
        new AlertDialog.Builder(this)
                .setTitle(e.displayTitle())
                .setMessage(e.savedAt + " · 共 " + e.total + " 站（可用 " + e.count(Site.CAT_ALIVE)
                        + " / 失败 " + e.count(Site.CAT_DEAD) + " / 未测 " + e.count(Site.CAT_UNTESTED) + "）")
                .setItems(new String[]{"载入到主界面（可续测 / 重测）", "删除这条记录"}, (d, w) -> {
                    if (w == 0) loadToMain(e);
                    else confirmDelete(e);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void loadToMain(History.Entry e) {
        Intent i = new Intent(this, MainActivity.class);
        i.setAction(MainActivity.ACTION_LOAD_HISTORY);
        i.putExtra(MainActivity.EXTRA_TAG, e.tag);
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(i);
        finish();
    }

    private void confirmDelete(History.Entry e) {
        new AlertDialog.Builder(this)
                .setTitle("删除历史")
                .setMessage("确定删除「" + e.displayTitle() + "」这条测活记录？\n（其它接口的记录不受影响）")
                .setPositiveButton("删除", (d, w) -> {
                    History.remove(this, e.tag);
                    load();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmClearAll() {
        if (History.all(this).isEmpty()) {
            toast("还没有历史记录");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("清空全部历史")
                .setMessage("所有接口的测活记录都会被删除，确定？")
                .setPositiveButton("清空", (d, w) -> {
                    History.clear(this);
                    load();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
