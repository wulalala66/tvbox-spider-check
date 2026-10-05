package com.spider.check;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public class SiteAdapter extends RecyclerView.Adapter<SiteAdapter.Holder> {

    private final List<Site> items = new ArrayList<>();
    private OnLongClick onLongClick;
    private OnLongClick onItemClick;
    private Runnable onSelectChange;

    public interface OnLongClick {
        void onLong(Site site);
    }

    public void setOnLongClick(OnLongClick l) {
        this.onLongClick = l;
    }

    /** 单击列表项：单独测活该站点。 */
    public void setOnItemClick(OnLongClick l) {
        this.onItemClick = l;
    }

    /** 勾选状态变化（用于刷新「已选 n/m」）。 */
    public void setOnSelectChange(Runnable r) {
        this.onSelectChange = r;
    }

    public void setItems(List<Site> list) {
        items.clear();
        items.addAll(list);
        notifyDataSetChanged();
    }

    public void notifyItemChanged(Site site) {
        int idx = items.indexOf(site);
        if (idx >= 0) notifyItemChanged(idx);
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_site, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        Site site = items.get(position);
        holder.grade.setText(site.getGrade());
        int color = switch (site.getGrade()) {
            case "A" -> 0xFF2E7D32;
            case "B" -> 0xFF1565C0;
            case "C" -> 0xFFEF6C00;
            case "D" -> 0xFFB71C1C;
            default -> 0xFF333333;
        };
        // 圆角色块：共享 drawable 必须 mutate 后 tint，否则会影响其它条目
        android.graphics.drawable.Drawable bg = holder.grade.getBackground();
        if (bg != null) {
            bg = bg.mutate();
            bg.setTint(color);
        }
        holder.name.setText(site.getName());
        holder.kind.setText(site.kindLabel());
        holder.detail.setText(site.display());
        // 复选框：必须先摘监听再设值，否则 RecyclerView 复用会串到别的站点上
        holder.cb.setOnCheckedChangeListener(null);
        holder.cb.setChecked(site.isSelected());
        holder.cb.setOnCheckedChangeListener((btn, checked) -> {
            site.setSelected(checked);
            if (onSelectChange != null) onSelectChange.run();
        });
        holder.itemView.setOnClickListener(v -> {
            if (onItemClick != null) onItemClick.onLong(site);
        });
        holder.itemView.setOnLongClickListener(v -> {
            if (onLongClick != null) onLongClick.onLong(site);
            return true;
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class Holder extends RecyclerView.ViewHolder {
        final CheckBox cb;
        final TextView grade, name, detail, kind;

        Holder(@NonNull View v) {
            super(v);
            cb = v.findViewById(R.id.cbSel);
            grade = v.findViewById(R.id.tvGrade);
            name = v.findViewById(R.id.tvName);
            detail = v.findViewById(R.id.tvDetail);
            kind = v.findViewById(R.id.tvKind);
        }
    }
}
