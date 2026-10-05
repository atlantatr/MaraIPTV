package com.atlanta.maraplayer.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.atlanta.maraplayer.R;
import com.atlanta.maraplayer.data.Db;
import com.atlanta.maraplayer.data.Models.Category;
import com.atlanta.maraplayer.data.Models.Item;
import com.atlanta.maraplayer.data.Prefs;
import com.atlanta.maraplayer.data.Repo;
import com.atlanta.maraplayer.util.ImageLoader;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Liste / izgara adaptorleri. */
public final class Adapters {

    private Adapters() {
    }

    public static int dp(Context c, int v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    // ------------------------------------------------------------ Kategoriler

    public static class CategoryAdapter extends BaseAdapter {
        private final LayoutInflater inf;
        private final List<Category> data = new ArrayList<>();

        public CategoryAdapter(Context c) {
            inf = LayoutInflater.from(c);
        }

        public void set(List<Category> l) {
            data.clear();
            data.addAll(l);
            notifyDataSetChanged();
        }

        public List<Category> data() {
            return data;
        }

        @Override
        public int getCount() {
            return data.size();
        }

        @Override
        public Category getItem(int p) {
            return data.get(p);
        }

        @Override
        public long getItemId(int p) {
            return p;
        }

        @Override
        public View getView(int p, View v, ViewGroup parent) {
            if (v == null) v = inf.inflate(R.layout.row_category, parent, false);
            Category c = data.get(p);
            ((TextView) v.findViewById(R.id.name)).setText(c.name);
            ((TextView) v.findViewById(R.id.count)).setText("");
            boolean locked = !c.isVirtual() && Prefs.isLocked(Repo.pid(), c.type, c.id, c.name);
            v.findViewById(R.id.lock).setVisibility(locked ? View.VISIBLE : View.GONE);
            return v;
        }
    }

    // ------------------------------------------------------------ Kanallar

    public static class ChannelAdapter extends BaseAdapter {
        private final Context ctx;
        private final LayoutInflater inf;
        private final List<Item> data = new ArrayList<>();
        private final Set<String> favs = new HashSet<>();
        private final int w, h;

        public ChannelAdapter(Context c) {
            ctx = c;
            inf = LayoutInflater.from(c);
            w = dp(c, 60);
            h = dp(c, 42);
        }

        public void set(List<Item> l) {
            data.clear();
            data.addAll(l);
            reloadFavs();
        }

        public void reloadFavs() {
            favs.clear();
            for (Item f : Db.get().favorites(Repo.pid(), com.atlanta.maraplayer.data.Models.LIVE)) favs.add(f.id);
            notifyDataSetChanged();
        }

        public List<Item> data() {
            return data;
        }

        @Override
        public int getCount() {
            return data.size();
        }

        @Override
        public Item getItem(int p) {
            return data.get(p);
        }

        @Override
        public long getItemId(int p) {
            return p;
        }

        @Override
        public View getView(int p, View v, ViewGroup parent) {
            if (v == null) v = inf.inflate(R.layout.row_channel, parent, false);
            Item it = data.get(p);
            ((TextView) v.findViewById(R.id.num)).setText(String.valueOf(it.num > 0 ? it.num : p + 1));
            ((TextView) v.findViewById(R.id.name)).setText(it.name);
            v.findViewById(R.id.archive).setVisibility(it.archive ? View.VISIBLE : View.GONE);
            v.findViewById(R.id.fav).setVisibility(favs.contains(it.id) ? View.VISIBLE : View.GONE);
            ImageLoader.get().load((ImageView) v.findViewById(R.id.logo), it.icon, R.drawable.ph_channel, w, h);
            return v;
        }
    }

    // ------------------------------------------------------------ Posterler

    public static class PosterAdapter extends BaseAdapter {
        private final LayoutInflater inf;
        private final List<Item> data = new ArrayList<>();
        private final Set<String> favs = new HashSet<>();
        private final int w, h;
        private final int type;

        public PosterAdapter(Context c, int type) {
            inf = LayoutInflater.from(c);
            this.type = type;
            w = dp(c, 120);
            h = dp(c, 170);
        }

        public void set(List<Item> l) {
            data.clear();
            data.addAll(l);
            reloadFavs();
        }

        public void reloadFavs() {
            favs.clear();
            for (Item f : Db.get().favorites(Repo.pid(), type)) favs.add(f.id);
            notifyDataSetChanged();
        }

        public List<Item> data() {
            return data;
        }

        @Override
        public int getCount() {
            return data.size();
        }

        @Override
        public Item getItem(int p) {
            return data.get(p);
        }

        @Override
        public long getItemId(int p) {
            return p;
        }

        @Override
        public View getView(int p, View v, ViewGroup parent) {
            if (v == null) v = inf.inflate(R.layout.item_poster, parent, false);
            Item it = data.get(p);
            ((TextView) v.findViewById(R.id.name)).setText(it.name);
            TextView r = v.findViewById(R.id.rating);
            String rating = cleanRating(it.rating);
            if (rating != null) {
                r.setText("★ " + rating);
                r.setVisibility(View.VISIBLE);
            } else {
                r.setVisibility(View.GONE);
            }
            v.findViewById(R.id.fav).setVisibility(favs.contains(it.id) ? View.VISIBLE : View.GONE);
            ImageLoader.get().load((ImageView) v.findViewById(R.id.poster), it.icon, R.drawable.ph_poster, w, h);
            return v;
        }
    }

    public static String cleanRating(String s) {
        if (s == null) return null;
        try {
            double d = Double.parseDouble(s.trim());
            if (d <= 0) return null;
            return String.format(java.util.Locale.US, "%.1f", d);
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------ Genel satir

    public static class Row {
        public String title = "";
        public String sub;
        public String right;
        public String icon;
        public int placeholder = R.drawable.ph_channel;
        public boolean showIcon;
        public int progress = -1;
        public Object tag;

        public Row() {
        }

        public Row(String title, String sub, Object tag) {
            this.title = title;
            this.sub = sub;
            this.tag = tag;
        }
    }

    public static class RowAdapter extends BaseAdapter {
        private final Context ctx;
        private final LayoutInflater inf;
        private final List<Row> data = new ArrayList<>();
        private final int w, h;

        public RowAdapter(Context c) {
            ctx = c;
            inf = LayoutInflater.from(c);
            w = dp(c, 64);
            h = dp(c, 48);
        }

        public void set(List<Row> l) {
            data.clear();
            data.addAll(l);
            notifyDataSetChanged();
        }

        public List<Row> data() {
            return data;
        }

        @Override
        public int getCount() {
            return data.size();
        }

        @Override
        public Row getItem(int p) {
            return data.get(p);
        }

        @Override
        public long getItemId(int p) {
            return p;
        }

        @Override
        public View getView(int p, View v, ViewGroup parent) {
            if (v == null) v = inf.inflate(R.layout.row_simple, parent, false);
            Row r = data.get(p);
            ((TextView) v.findViewById(R.id.title)).setText(r.title);
            TextView sub = v.findViewById(R.id.sub);
            if (r.sub != null && !r.sub.isEmpty()) {
                sub.setText(r.sub);
                sub.setVisibility(View.VISIBLE);
            } else {
                sub.setVisibility(View.GONE);
            }
            ((TextView) v.findViewById(R.id.right)).setText(r.right == null ? "" : r.right);
            ImageView icon = v.findViewById(R.id.icon);
            if (r.showIcon) {
                icon.setVisibility(View.VISIBLE);
                ImageLoader.get().load(icon, r.icon, r.placeholder, w, h);
            } else {
                icon.setVisibility(View.GONE);
            }
            ProgressBar pb = v.findViewById(R.id.progress);
            if (r.progress >= 0) {
                pb.setVisibility(View.VISIBLE);
                pb.setProgress(r.progress);
            } else {
                pb.setVisibility(View.GONE);
            }
            return v;
        }
    }
}
