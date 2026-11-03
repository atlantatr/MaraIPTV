package com.atlanta.maraplayer.ui;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;

import com.atlanta.maraplayer.R;
import com.atlanta.maraplayer.data.BroadcastApi;
import com.atlanta.maraplayer.data.Models;
import com.atlanta.maraplayer.data.Models.Item;
import com.atlanta.maraplayer.data.Repo;
import com.atlanta.maraplayer.util.Async;
import com.atlanta.maraplayer.util.Ui;

import java.util.ArrayList;
import java.util.List;

/**
 * Spor rehberinde bir yayinciyi IPTV kanalina baglamak icin kanal secici.
 * Sonuc: {@link #EXTRA_RESULT_ID} + {@link #EXTRA_RESULT_NAME}.
 */
public class ChannelPickActivity extends BaseActivity {

    public static final String EXTRA_TITLE = "pick_title";
    public static final String EXTRA_RESULT_ID = "pick_id";
    public static final String EXTRA_RESULT_NAME = "pick_name";

    private static final int MAX_ROWS = 500;

    private EditText search;
    private ListView list;
    private TextView hint, msg;
    private Adapters.RowAdapter adapter;
    private List<Item> all = new ArrayList<>();
    private boolean loaded;

    private final Runnable doFilter = new Runnable() {
        @Override
        public void run() {
            filter();
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (sessionLost) return;
        setContentView(R.layout.activity_list);

        String title = getIntent().getStringExtra(EXTRA_TITLE);
        ((TextView) v(R.id.title)).setText(title == null || title.isEmpty()
                ? getString(R.string.pick_channel_title) : title);
        hint = v(R.id.hint);
        msg = v(R.id.msg);
        search = v(R.id.search);
        v(R.id.searchRow).setVisibility(View.VISIBLE);
        search.setHint(R.string.pick_channel_search);

        list = v(R.id.list);
        adapter = new Adapters.RowAdapter(this);
        list.setAdapter(adapter);

        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b2, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b2, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                Async.MAIN.removeCallbacks(doFilter);
                Async.MAIN.postDelayed(doFilter, 300);
            }
        });
        search.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView tv, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE
                        || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                        && event.getAction() == KeyEvent.ACTION_DOWN)) {
                    hideKeyboard();
                    filter();
                    if (adapter.getCount() > 0) list.requestFocus();
                    return true;
                }
                return false;
            }
        });

        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> p, View view, int pos, long id) {
                Item it = (Item) adapter.getItem(pos).tag;
                Intent out = new Intent();
                out.putExtra(EXTRA_RESULT_ID, it.id);
                out.putExtra(EXTRA_RESULT_NAME, BroadcastApi.displayChannel(it.name));
                setResult(RESULT_OK, out);
                finish();
            }
        });

        load();
        search.clearFocus();
        list.requestFocus();
    }

    private void load() {
        v(R.id.loading).setVisibility(View.VISIBLE);
        Async.run(this, new Async.Task<List<Item>>() {
            @Override
            public List<Item> run() throws Exception {
                return Repo.allUnlocked(Models.LIVE);
            }
        }, new Async.Callback<List<Item>>() {
            @Override
            public void on(List<Item> l) {
                loaded = true;
                v(R.id.loading).setVisibility(View.GONE);
                all = l == null ? new ArrayList<Item>() : l;
                filter();
            }
        }, new Async.Callback<Exception>() {
            @Override
            public void on(Exception e) {
                loaded = true;
                v(R.id.loading).setVisibility(View.GONE);
                msg.setText(getString(R.string.channels_failed) + "\n" + Ui.err(e));
                msg.setVisibility(View.VISIBLE);
            }
        });
    }

    private void filter() {
        if (!loaded) return;
        String q = Ui.norm(search.getText().toString().trim());
        List<Adapters.Row> rows = new ArrayList<>();
        for (Item it : all) {
            if (rows.size() >= MAX_ROWS) break;
            String name = BroadcastApi.displayChannel(it.name);
            if (!q.isEmpty() && !Ui.norm(name).contains(q)) continue;
            Adapters.Row r = new Adapters.Row();
            r.title = name;
            String cat = Repo.categoryName(Models.LIVE, it.categoryId);
            r.sub = cat == null ? "" : cat;
            r.icon = it.icon;
            r.showIcon = true;
            r.tag = it;
            rows.add(r);
        }
        adapter.set(rows);
        hint.setText(getString(R.string.pick_channel_count, rows.size()));
        if (rows.isEmpty()) {
            msg.setText(R.string.pick_channel_none);
            msg.setVisibility(View.VISIBLE);
        } else {
            msg.setVisibility(View.GONE);
        }
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(search.getWindowToken(), 0);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Async.MAIN.removeCallbacks(doFilter);
    }
}
