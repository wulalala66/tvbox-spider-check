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

/**
 * 站点结果列表。支持两种展示方式：
 * <ul>
 *   <li>分组：按「✅ 正常可用 / ❌ 测活失败 / ⚪ 未测」分段，每段带标题与数量；</li>
 *   <li>平铺 + 筛选：只看某一类，或看全部（测活进行中用，避免条目跳动）。</li>
 * </ul>
 */
public class SiteAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    public static final int FILTER_ALL = 0;
    public static final int FILTER_ALIVE = 1;
    public static final int FILTER_DEAD = 2;
    public static final int FILTER_UNTESTED = 3;

    private static final int VIEW_SITE = 0;
    private static final int VIEW_HEAD = 1;

    private final List<Site> items = new ArrayList<>();
    /** 实际渲染的行：Site 或 Head。 */
    private final List<Object> rows = new ArrayList<>();

    private int filter = FILTER_ALL;
    private boolean grouped = true;
    private boolean showCheckbox = true;

    private OnLongClick onLongClick;
    private OnLongClick onItemClick;
    private Runnable onSelectChange;

    public interface OnLongClick {
        void onLong(Site site);
    }

    public void setOnLongClick(OnLongClick l) {
        this.onLongClick = l;
    }

    /** 单击列表项：单独测活该站点（历史结果页里则是查看详情）。 */
    public void setOnItemClick(OnLongClick l) {
        this.onItemClick = l;
    }

    /** 勾选状态变化（用于刷新「已选 n/m」）。 */
    public void setOnSelectChange(Runnable r) {
        this.onSelectChange = r;
    }

    /** 历史结果页不显示勾选框。 */
    public void setShowCheckbox(boolean b) {
        this.showCheckbox = b;
    }

    public int getFilter() {
        return filter;
    }

    public boolean isGrouped() {
        return grouped;
    }

    public void setItems(List<Site> list) {
        items.clear();
        items.addAll(list);
        rebuild();
    }

    public void setFilter(int f) {
        this.filter = f;
        rebuild();
    }

    public void setGrouped(boolean g) {
        this.grouped = g;
        rebuild();
    }

    /** 各分类数量：0 可用 / 1 失败 / 2 未测（与筛选无关，始终是全部站点）。 */
    public int[] counts() {
        int[] c = new int[3];
        for (Site s : items) {
            int k = s.category();
            if (k < 0 || k > 2) k = 2;
            c[k]++;
        }
        return c;
    }

    public int total() {
        return items.size();
    }

    /** 某个站点测完后刷新它（平铺模式下只刷新一行；分组模式位置会变，整体重排）。 */
    public void refresh(Site site) {
        if (grouped) {
            rebuild();
            return;
        }
        int idx = rows.indexOf(site);
        if (idx >= 0) notifyItemChanged(idx);
        else rebuild();
    }

    /** 全量重建（结果大变动时用）。 */
    public void rebuild() {
        rows.clear();
        if (grouped) {
            addGroup(Site.CAT_ALIVE);
            addGroup(Site.CAT_DEAD);
            addGroup(Site.CAT_UNTESTED);
        } else {
            for (Site s : items) if (match(s)) rows.add(s);
        }
        notifyDataSetChanged();
    }

    private void addGroup(int cat) {
        List<Site> g = new ArrayList<>();
        for (Site s : items) if (s.category() == cat) g.add(s);
        if (g.isEmpty()) return;
        rows.add(new Head(cat, g.size()));
        rows.addAll(g);
    }

    private boolean match(Site s) {
        switch (filter) {
            case FILTER_ALIVE:
                return s.category() == Site.CAT_ALIVE;
            case FILTER_DEAD:
                return s.category() == Site.CAT_DEAD;
            case FILTER_UNTESTED:
                return s.category() == Site.CAT_UNTESTED;
            default:
                return true;
        }
    }

    /** 分组标题行。 */
    static class Head {
        final int cat;
        final int count;

        Head(int cat, int count) {
            this.cat = cat;
            this.count = count;
        }

        String title() {
            switch (cat) {
                case Site.CAT_ALIVE:
                    return "✅ 正常可用";
                case Site.CAT_DEAD:
                    return "❌ 测活失败";
                default:
                    return "⚪ 未测 / 中断";
            }
        }

        int color() {
            switch (cat) {
                case Site.CAT_ALIVE:
                    return 0xFF2E7D32;
                case Site.CAT_DEAD:
                    return 0xFFB71C1C;
                default:
                    return 0xFF8A9099;
            }
        }
    }

    @Override
    public int getItemViewType(int position) {
        return rows.get(position) instanceof Site ? VIEW_SITE : VIEW_HEAD;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inf = LayoutInflater.from(parent.getContext());
        if (viewType == VIEW_HEAD) {
            return new HeadHolder(inf.inflate(R.layout.item_group, parent, false));
        }
        return new Holder(inf.inflate(R.layout.item_site, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder vh, int position) {
        Object row = rows.get(position);
        if (vh instanceof HeadHolder) {
            Head h = (Head) row;
            HeadHolder hh = (HeadHolder) vh;
            hh.title.setText(h.title());
            hh.title.setTextColor(h.color());
            hh.count.setText(h.count + " 个");
            hh.count.setTextColor(h.color());
            android.graphics.drawable.Drawable bar = hh.bar.getBackground();
            if (bar != null) {
                bar = bar.mutate();
                bar.setTint(h.color());
            }
            return;
        }
        bindSite((Holder) vh, (Site) row);
    }

    private void bindSite(@NonNull Holder holder, Site site) {
        holder.grade.setText(site.getGrade());
        int color = switch (site.getGrade()) {
            case "A" -> 0xFF2E7D32;
            case "B" -> 0xFF1565C0;
            case "C" -> 0xFFEF6C00;
            case "D" -> 0xFFB71C1C;
            default -> 0xFF9AA0A6;
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
        holder.cb.setVisibility(showCheckbox ? View.VISIBLE : View.GONE);
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
        return rows.size();
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

    static class HeadHolder extends RecyclerView.ViewHolder {
        final View bar;
        final TextView title, count;

        HeadHolder(@NonNull View v) {
            super(v);
            bar = v.findViewById(R.id.gBar);
            title = v.findViewById(R.id.tvGroupTitle);
            count = v.findViewById(R.id.tvGroupCount);
        }
    }
}
