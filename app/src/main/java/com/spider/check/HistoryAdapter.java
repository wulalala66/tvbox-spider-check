package com.spider.check;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/** 历史接口列表：一个接口一张卡。 */
public class HistoryAdapter extends RecyclerView.Adapter<HistoryAdapter.Holder> {

    public interface Listener {
        void onOpen(History.Entry e);

        void onMenu(History.Entry e);
    }

    private final List<History.Entry> items = new ArrayList<>();
    private Listener listener;

    public void setListener(Listener l) {
        this.listener = l;
    }

    public void setItems(List<History.Entry> list) {
        items.clear();
        items.addAll(list);
        notifyDataSetChanged();
    }

    public static String depthName(int d) {
        switch (d) {
            case 1:
                return "① 仅首页";
            case 2:
                return "② + 分类";
            case 3:
                return "③ 到详情";
            default:
                return "④ 全链路";
        }
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_history, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        History.Entry e = items.get(position);
        h.title.setText(e.displayTitle());
        String tag = e.tag == null ? "" : e.tag;
        h.url.setText(tag.startsWith("http") ? tag : "本地配置：" + tag);
        h.meta.setText(String.format("%s · 深度%s · 共 %d 站%s",
                e.savedAt, depthName(e.depth), e.total, e.interrupted ? " · 未跑完" : ""));
        h.badge.setVisibility(e.interrupted ? View.VISIBLE : View.GONE);
        h.alive.setText("可用 " + e.count(Site.CAT_ALIVE));
        h.dead.setText("失败 " + e.count(Site.CAT_DEAD));
        h.untested.setText("未测 " + e.count(Site.CAT_UNTESTED));
        h.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onOpen(e);
        });
        h.itemView.setOnLongClickListener(v -> {
            if (listener != null) listener.onMenu(e);
            return true;
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class Holder extends RecyclerView.ViewHolder {
        final TextView title, url, meta, badge, alive, dead, untested;

        Holder(@NonNull View v) {
            super(v);
            title = v.findViewById(R.id.tvTitle);
            url = v.findViewById(R.id.tvUrl);
            meta = v.findViewById(R.id.tvMeta);
            badge = v.findViewById(R.id.tvBadge);
            alive = v.findViewById(R.id.tvAlive);
            dead = v.findViewById(R.id.tvDead);
            untested = v.findViewById(R.id.tvUntested);
        }
    }
}
