package com.spider.check;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public class SiteAdapter extends RecyclerView.Adapter<SiteAdapter.Holder> {

    private final List<Site> items = new ArrayList<>();

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
        holder.grade.setBackgroundColor(color);
        holder.name.setText(site.getName());
        holder.kind.setText(site.kindLabel());
        holder.detail.setText(site.getResult());
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class Holder extends RecyclerView.ViewHolder {
        final TextView grade, name, detail, kind;

        Holder(@NonNull View v) {
            super(v);
            grade = v.findViewById(R.id.tvGrade);
            name = v.findViewById(R.id.tvName);
            detail = v.findViewById(R.id.tvDetail);
            kind = v.findViewById(R.id.tvKind);
        }
    }
}
