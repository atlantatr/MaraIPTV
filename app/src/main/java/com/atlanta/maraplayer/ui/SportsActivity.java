package com.atlanta.maraplayer.ui;

import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import com.atlanta.maraplayer.R;
import com.atlanta.maraplayer.data.BroadcastApi;
import com.atlanta.maraplayer.data.Models;
import com.atlanta.maraplayer.data.Prefs;
import com.atlanta.maraplayer.data.Repo;
import com.atlanta.maraplayer.data.SportsApi;
import com.atlanta.maraplayer.data.SportsApi.Match;
import com.atlanta.maraplayer.sport.MatchAlarm;
import com.atlanta.maraplayer.sport.MatchReminder;
import com.atlanta.maraplayer.util.Async;
import com.atlanta.maraplayer.util.ImageLoader;
import com.atlanta.maraplayer.util.Ui;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Spor Rehberi: yaklasan futbol maclari (ESPN ucretsiz arayuzu).
 *
 * Lig filtresi seridi, takim takibi, "Takimlarim" karti ve gun ayiricili mac
 * listesi icerir. Yayinci adlari FootOnSat mantigiyla gosterilir; bir IPTV
 * kanali yalnizca adi yayinci adiyla birebir ayniysa onerilir.
 */
public class SportsActivity extends BaseActivity {

    /** Kac gun ileriye bakilir */
    private static final int DAYS = 5;
    private static final int REQ_PICK_CHANNEL = 71;
    /** Otomatik kurulacak hatirlatma ust siniri */
    private static final int AUTO_REMIND_MAX = 12;

    private ListView list;
    private MatchAdapter adapter;
    private TextView msg, hint, followHint;
    private View loading;
    private Button[] tabs;
    private Button autoTab;
    private HorizontalScrollView leagueScroll, teamScroll;
    private LinearLayout leagueRow, teamRow;
    private boolean loadingNow;
    private List<Match> all = new ArrayList<>();
    /** Elle kanal secimi bekleyen yayinci / mac */
    private String pendingBroadcast, pendingMatchKey;
    /** Mac -> gunluk yayin bilgisi (LiveOnSat + birebir IPTV eslesmesi) */
    private Map<String, BroadcastApi.MatchTv> tvInfo = new HashMap<>();
    private boolean tvLoading;
    private boolean tvDone;
    /** Secili lig; null = tum ligler */
    private String leagueFilter;
    /** 0 = tumu, 1 = takimlarim */
    private int tab;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (sessionLost) return;
        setContentView(R.layout.activity_sports);
        list = v(R.id.list);
        msg = v(R.id.msg);
        hint = v(R.id.hint);
        followHint = v(R.id.followHint);
        loading = v(R.id.loading);
        leagueScroll = v(R.id.leagueScroll);
        teamScroll = v(R.id.teamScroll);
        leagueRow = v(R.id.leagueRow);
        teamRow = v(R.id.teamRow);
        adapter = new MatchAdapter();
        list.setAdapter(adapter);
        ((TextView) v(R.id.title)).setText(R.string.sports_guide);

        tabs = new Button[]{v(R.id.tab0), v(R.id.tab1)};
        tabs[0].setText(R.string.sports_tab_all);
        tabs[1].setText(R.string.sports_tab_mine);
        autoTab = v(R.id.tabAuto);
        autoTab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                boolean on = !Prefs.autoTeamReminders();
                Prefs.setAutoTeamReminders(on);
                render();
                if (on) {
                    applyAutoReminders();
                    Ui.toast(SportsActivity.this, getString(R.string.sports_auto_remind_on));
                } else {
                    Ui.toast(SportsActivity.this, getString(R.string.sports_auto_remind_off));
                }
            }
        });
        for (int i = 0; i < tabs.length; i++) {
            final int idx = i;
            tabs[i].setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    selectTab(idx);
                }
            });
        }
        tabs[0].setSelected(true);
        tabs[0].requestFocus();

        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> p, View view, int pos, long id) {
                Object o = adapter.getItem(pos);
                if (o instanceof Match) details((Match) o);
            }
        });

        List<Match> cached = SportsApi.cached();
        if (cached != null && !cached.isEmpty()) {
            all = cached;
            buildLeagueStrip();
            render();
            loadBroadcasts();
        } else {
            loading.setVisibility(View.VISIBLE);
            load();
        }
    }

    private void selectTab(int idx) {
        tab = idx;
        leagueScroll.setVisibility(tab == 0 ? View.VISIBLE : View.GONE);
        teamScroll.setVisibility(tab == 1 ? View.VISIBLE : View.GONE);
        buildTeamStrip();
        render();
        if (adapter.getCount() > 0) list.requestFocus();
    }

    private void load() {
        if (loadingNow) return;
        loadingNow = true;
        loading.setVisibility(View.VISIBLE);
        msg.setVisibility(View.GONE);
        Async.run(this, new Async.Task<List<Match>>() {
            @Override
            public List<Match> run() throws Exception {
                return SportsApi.fetch(DAYS);
            }
        }, new Async.Callback<List<Match>>() {
            @Override
            public void on(List<Match> matches) {
                loadingNow = false;
                loading.setVisibility(View.GONE);
                all = matches == null ? new ArrayList<Match>() : matches;
                tvInfo.clear();
                tvDone = false;
                leagueFilter = null;
                buildLeagueStrip();
                buildTeamStrip();
                render();
                loadBroadcasts();
            }
        }, new Async.Callback<Exception>() {
            @Override
            public void on(Exception e) {
                loadingNow = false;
                loading.setVisibility(View.GONE);
                if (adapter.getCount() == 0) {
                    msg.setText(getString(R.string.sports_failed_x, Ui.err(e)));
                    msg.setVisibility(View.VISIBLE);
                }
            }
        });
    }

    /**
     * Gunluk yayin listesini (LiveOnSat) indirip yayinci adlarini okur.
     * Ag istegi arka planda; ekran hemen kullanilabilir kalir.
     */
    private void loadBroadcasts() {
        if (tvLoading || tvDone || all.isEmpty()) return;
        tvLoading = true;
        Async.run(this, new Async.Task<Map<String, BroadcastApi.MatchTv>>() {
            @Override
            public Map<String, BroadcastApi.MatchTv> run() throws Exception {
                return BroadcastApi.resolve(all, Repo.allUnlocked(Models.LIVE));
            }
        }, new Async.Callback<Map<String, BroadcastApi.MatchTv>>() {
            @Override
            public void on(Map<String, BroadcastApi.MatchTv> map) {
                tvLoading = false;
                tvDone = true;
                tvInfo = map == null ? new HashMap<String, BroadcastApi.MatchTv>() : map;
                if (adapter != null) adapter.notifyDataSetChanged();
                applyAutoReminders();
            }
        }, new Async.Callback<Exception>() {
            @Override
            public void on(Exception e) {
                tvLoading = false;
                tvDone = true;
                android.util.Log.w("MaraBC", "yayin bilgisi alinamadi", e);
                applyAutoReminders();
            }
        });
    }

    // ------------------------------------------------------------ Otomatik hatirlatma

    /**
     * Takip edilen takimlarin yaklasan maclarina "10 dakika once" hatirlatmasi
     * kurar. Mac basina tek kayit; zaten kurulu olanlara dokunulmaz.
     * Ust sinir: ayni anda en fazla {@link #AUTO_REMIND_MAX} mac.
     */
    private void applyAutoReminders() {
        if (!Prefs.autoTeamReminders()) return;
        Set<String> teams = Prefs.favoriteTeams();
        if (teams.isEmpty()) return;
        long now = System.currentTimeMillis();
        int added = 0;
        for (Match m : all) {
            boolean mine = teams.contains(m.home) || teams.contains(m.away);
            if (!mine || m.start <= now + 60000L) continue;
            String key = BroadcastApi.key(m);
            if (MatchReminder.isFor(key)) continue;
            BroadcastApi.MatchTv tv = tvInfo.get(key);
            String cid = "", cname = "";
            if (tv != null && !tv.channels.isEmpty()) {
                cid = tv.channels.get(0).channelId;
                cname = tv.channels.get(0).channel;
            }
            MatchReminder.schedule(this, key, matchTitle(m), m.start,
                    MatchReminder.LEAD_TEN, cid, cname);
            if (++added >= AUTO_REMIND_MAX) break;
        }
        if (added > 0 && adapter != null) adapter.notifyDataSetChanged();
    }

    // ------------------------------------------------------------ Seritler

    /** Lig filtresi: "Tum ligler" + veri gelen ligler (logolu). */
    private void buildLeagueStrip() {
        if (leagueRow == null) return;
        leagueRow.removeAllViews();
        Map<String, String> leagues = new LinkedHashMap<>();
        for (Match m : all) {
            if (m.league == null || m.league.isEmpty() || leagues.containsKey(m.league)) continue;
            leagues.put(m.league, m.leagueLogo == null ? "" : m.leagueLogo);
        }
        addLeagueChip(null, getString(R.string.sports_all_leagues), "");
        for (Map.Entry<String, String> e : leagues.entrySet()) {
            addLeagueChip(e.getKey(), e.getKey(), e.getValue());
        }
    }

    private void addLeagueChip(final String league, String label, String logo) {
        View chip = LayoutInflater.from(this).inflate(R.layout.chip_league, leagueRow, false);
        ((TextView) chip.findViewById(R.id.chipName)).setText(label);
        ImageView iv = chip.findViewById(R.id.chipLogo);
        if (logo == null || logo.isEmpty()) {
            iv.setVisibility(View.GONE);
        } else {
            ImageLoader.get().load(iv, logo, R.drawable.ph_team, dp(26), dp(26));
        }
        chip.setSelected(league == null ? leagueFilter == null : league.equals(leagueFilter));
        chip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                leagueFilter = league;
                buildLeagueStrip();
                render();
                if (adapter.getCount() > 0) list.requestFocus();
            }
        });
        leagueRow.addView(chip);
    }

    /** Takimlarim: takip edilen takimlar logo + lig bilgisiyle. OK takipten cikarir. */
    private void buildTeamStrip() {
        if (teamRow == null) return;
        teamRow.removeAllViews();
        Set<String> teams = Prefs.favoriteTeams();
        if (tab != 1 || teams.isEmpty()) {
            teamScroll.setVisibility(View.GONE);
            return;
        }
        teamScroll.setVisibility(View.VISIBLE);

        Map<String, String[]> meta = new HashMap<>();
        for (Match m : all) {
            putTeamMeta(meta, m.home, m.homeLogo, m.league);
            putTeamMeta(meta, m.away, m.awayLogo, m.league);
        }
        List<String> sorted = new ArrayList<>(teams);
        Collections.sort(sorted, String.CASE_INSENSITIVE_ORDER);
        for (int i = 0; i < sorted.size(); i++) {
            final String team = sorted.get(i);
            final int index = i;
            String[] info = meta.get(team);
            View chip = LayoutInflater.from(this).inflate(R.layout.chip_team, teamRow, false);
            ((TextView) chip.findViewById(R.id.chipName)).setText(team);
            TextView sub = chip.findViewById(R.id.chipSub);
            sub.setText(info == null ? getString(R.string.sports_no_match) : info[1]);
            ImageView iv = chip.findViewById(R.id.chipLogo);
            ImageLoader.get().load(iv, info == null ? "" : info[0],
                    R.drawable.ph_team, dp(36), dp(36));
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    unfollowFromStrip(team, index);
                }
            });
            teamRow.addView(chip);
        }
    }

    private static void putTeamMeta(Map<String, String[]> meta, String team, String logo, String league) {
        if (team == null || team.isEmpty() || meta.containsKey(team)) return;
        meta.put(team, new String[]{logo == null ? "" : logo, league == null ? "" : league});
    }

    private void unfollowFromStrip(String team, int index) {
        Prefs.toggleFavoriteTeam(team);
        Ui.toast(this, getString(R.string.sports_unfollowed_x, team));
        buildTeamStrip();
        render();
        if (!Prefs.favoriteTeams().isEmpty() && teamRow.getChildCount() > 0) {
            int next = Math.min(index, teamRow.getChildCount() - 1);
            teamRow.getChildAt(next).requestFocus();
        } else {
            tabs[1].requestFocus();
        }
    }

    // ------------------------------------------------------------ Liste

    /** Takip edilen takimlarin maclari (yoksa bos liste) */
    private List<Match> teamMatches() {
        Set<String> teams = Prefs.favoriteTeams();
        List<Match> out = new ArrayList<>();
        if (teams.isEmpty()) return out;
        for (Match m : all) {
            if (teams.contains(m.home) || teams.contains(m.away)) out.add(m);
        }
        return out;
    }

    private void render() {
        List<Match> matches;
        if (tab == 1) {
            matches = teamMatches();
        } else if (leagueFilter == null) {
            matches = all;
        } else {
            matches = new ArrayList<>();
            for (Match m : all) if (leagueFilter.equals(m.league)) matches.add(m);
        }
        adapter.set(grouped(matches));

        for (int i = 0; i < tabs.length; i++) tabs[i].setSelected(i == tab);
        boolean auto = Prefs.autoTeamReminders();
        followHint.setText(tab == 1
                ? getString(R.string.sports_mine_hint_auto, getString(auto ? R.string.sports_on : R.string.sports_off))
                : getString(R.string.sports_follow_hint));
        if (autoTab != null) {
            autoTab.setText(getString(R.string.sports_auto_remind) + ": "
                    + getString(auto ? R.string.sports_on : R.string.sports_off));
            autoTab.setSelected(auto);
        }

        boolean empty = matches.isEmpty();
        msg.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (tab == 1) {
            msg.setText(Prefs.favoriteTeams().isEmpty()
                    ? R.string.sports_teams_empty : R.string.sports_mine_empty);
        } else {
            msg.setText(R.string.sports_empty);
        }

        if (!all.isEmpty()) {
            hint.setText(getString(R.string.sports_count_x, all.size()) + "  ·  "
                    + day(all.get(0).start) + " – " + day(all.get(all.size() - 1).start));
        } else {
            hint.setText("");
        }
        if (!empty && !list.hasFocus()) list.requestFocus();
    }

    /** Gun ayiricilariyla birlikte liste satirlari (String = baslik, Match = mac). */
    private List<Object> grouped(List<Match> matches) {
        List<Object> out = new ArrayList<>();
        int i = 0;
        while (i < matches.size()) {
            String key = dayKey(matches.get(i).start);
            int n = 0;
            while (i + n < matches.size() && key.equals(dayKey(matches.get(i + n).start))) n++;
            out.add(getString(R.string.sports_day_count, day(matches.get(i).start), n));
            for (int k = 0; k < n; k++) out.add(matches.get(i + k));
            i += n;
        }
        return out;
    }

    private void details(final Match m) {
        final BroadcastApi.MatchTv tv = tvInfo.get(BroadcastApi.key(m));
        final long now = System.currentTimeMillis();
        final String key = BroadcastApi.key(m);

        StringBuilder sb = new StringBuilder();
        sb.append(m.league).append("  ·  ").append(day(m.start)).append(' ').append(time(m.start));
        if (m.live() && !m.score.isEmpty()) sb.append("  ·  ").append(getString(R.string.sports_live)).append(' ').append(m.score);
        sb.append('\n');
        if (tv != null && !tv.channels.isEmpty()) {
            // IPTV listesindeki karsiliklari alt alta yaz (birebir eslesenler).
            sb.append(getString(R.string.sports_tv_iptv)).append(":\n");
            int n = Math.min(5, tv.channels.size());
            for (int i = 0; i < n; i++) {
                sb.append("▶ ").append(tv.channels.get(i).channel);
                if (i < n - 1) sb.append('\n');
            }
        } else if (tv != null && !tv.broadcast.isEmpty()) {
            // IPTV'de karsiligi yok: yayinci adlarini alt alta goster.
            sb.append(getString(R.string.sports_tv_broadcasters))
                    .append(" (").append(getString(R.string.sports_tv_not_iptv)).append("):\n");
            String[] names = tv.broadcast.split(" · ");
            int n = Math.min(5, names.length);
            for (int i = 0; i < n; i++) {
                sb.append("• ").append(names[i]);
                if (i < n - 1) sb.append('\n');
            }
        } else if (!tvDone) {
            sb.append(getString(R.string.sports_tv_searching));
        } else {
            sb.append(getString(R.string.sports_tv_unknown));
        }

        final List<String> actions = new ArrayList<>();
        final List<Runnable> acts = new ArrayList<>();

        // Hatirlatma: mactan 10 dk once veya mac saatinde bildirim.
        // Kanal eslesmesi olmasa da kurulabilir (FootOnSat mantigi).
        if (m.start > now + 60000L) {
            final int lead = MatchReminder.leadFor(key);
            if (lead >= 0) {
                actions.add(getString(R.string.sports_remind_cancel));
                acts.add(new Runnable() {
                    @Override
                    public void run() {
                        MatchReminder.cancel(SportsActivity.this, key);
                        Ui.toast(SportsActivity.this, getString(R.string.sports_remind_off));
                        adapter.notifyDataSetChanged();
                    }
                });
            } else {
                actions.add(getString(R.string.sports_remind_10));
                acts.add(new Runnable() {
                    @Override
                    public void run() {
                        setReminder(m, key, MatchReminder.LEAD_TEN, R.string.sports_remind_on_10);
                    }
                });
                actions.add(getString(R.string.sports_remind_start));
                acts.add(new Runnable() {
                    @Override
                    public void run() {
                        setReminder(m, key, MatchReminder.LEAD_START, R.string.sports_remind_on_start);
                    }
                });
            }
        }

        if (tv != null && !tv.channels.isEmpty()) {
            int n = Math.min(3, tv.channels.size());
            for (int i = 0; i < n; i++) {
                final BroadcastApi.Candidate c = tv.channels.get(i);
                actions.add(getString(R.string.sports_tv_open_x, c.channel));
                acts.add(new Runnable() {
                    @Override
                    public void run() {
                        Play.playLiveById(SportsActivity.this, c.channelId, c.channel);
                    }
                });
            }
            if (m.start > now + 60000L) {
                if (MatchAlarm.isFor(key)) {
                    actions.add(getString(R.string.sports_timer_cancel));
                    acts.add(new Runnable() {
                        @Override
                        public void run() {
                            MatchAlarm.cancel(SportsActivity.this);
                            Ui.toast(SportsActivity.this, getString(R.string.sports_timer_off));
                            adapter.notifyDataSetChanged();
                        }
                    });
                } else {
                    final BroadcastApi.Candidate c = tv.channels.get(0);
                    actions.add(getString(R.string.sports_timer_x, c.channel));
                    acts.add(new Runnable() {
                        @Override
                        public void run() {
                            MatchAlarm.schedule(SportsActivity.this, m.start, Repo.pid(), key,
                                    matchTitle(m), c.channelId, c.channel);
                            Ui.toast(SportsActivity.this, getString(R.string.sports_timer_set_x, matchTitle(m)));
                            adapter.notifyDataSetChanged();
                        }
                    });
                }
            }
        }

        if (tv != null && !tv.broadcast.isEmpty()) {
            final String linkedBc = linkedBroadcaster(tv);
            if (linkedBc == null) {
                actions.add(getString(R.string.sports_link_channel));
                acts.add(new Runnable() {
                    @Override
                    public void run() {
                        chooseBroadcaster(m, tv);
                    }
                });
            } else {
                actions.add(getString(R.string.sports_link_remove_x, linkedBc));
                acts.add(new Runnable() {
                    @Override
                    public void run() {
                        BroadcastApi.unlink(linkedBc);
                        Ui.toast(SportsActivity.this, getString(R.string.sports_link_removed));
                        refreshBroadcasts();
                    }
                });
                actions.add(getString(R.string.sports_link_channel));
                acts.add(new Runnable() {
                    @Override
                    public void run() {
                        chooseBroadcaster(m, tv);
                    }
                });
            }
        }

        if (!m.home.isEmpty()) {
            actions.add(getString(Prefs.isFavoriteTeam(m.home) ? R.string.sports_unfollow_x : R.string.sports_follow_x, m.home));
            acts.add(new Runnable() {
                @Override
                public void run() {
                    toggleTeam(m.home);
                }
            });
        }
        if (!m.away.isEmpty()) {
            actions.add(getString(Prefs.isFavoriteTeam(m.away) ? R.string.sports_unfollow_x : R.string.sports_follow_x, m.away));
            acts.add(new Runnable() {
                @Override
                public void run() {
                    toggleTeam(m.away);
                }
            });
        }

        android.app.AlertDialog.Builder d = Ui.dialog(this);
        View head = getLayoutInflater().inflate(R.layout.dialog_match_title, null);
        ((TextView) head.findViewById(R.id.dlgTitle)).setText(matchTitle(m));
        ((TextView) head.findViewById(R.id.dlgSub)).setText(sb.toString());
        d.setCustomTitle(head);
        if (!actions.isEmpty()) {
            d.setItems(actions.toArray(new String[0]), new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    if (which >= 0 && which < acts.size()) acts.get(which).run();
                }
            });
        }
        d.show();
    }

    private void setReminder(Match m, String key, int lead, int msg) {
        MatchReminder.schedule(this, key, matchTitle(m), m.start, lead,
                channelId(key), channelName(key));
        Ui.toast(this, getString(msg));
        adapter.notifyDataSetChanged();
    }

    private String channelId(String matchKey) {
        BroadcastApi.MatchTv tv = tvInfo.get(matchKey);
        return tv == null || tv.channels.isEmpty() ? "" : tv.channels.get(0).channelId;
    }

    private String channelName(String matchKey) {
        BroadcastApi.MatchTv tv = tvInfo.get(matchKey);
        return tv == null || tv.channels.isEmpty() ? "" : tv.channels.get(0).channel;
    }

    /** Yayinci listesinde elle baglanmis ilk ad (yoksa null). */
    private static String linkedBroadcaster(BroadcastApi.MatchTv tv) {
        for (String bc : tv.broadcast.split(" · ")) {
            if (BroadcastApi.linkFor(bc) != null) return bc.trim();
        }
        return null;
    }

    /** Hangi yayinciyi kanala baglayacagini sorar. */
    private void chooseBroadcaster(final Match m, final BroadcastApi.MatchTv tv) {
        final String[] names = tv.broadcast.split(" · ");
        if (names.length == 0 || names[0].trim().isEmpty()) return;
        if (names.length == 1) {
            pickChannel(m, names[0].trim());
            return;
        }
        Ui.choose(this, getString(R.string.sports_link_choose_bc), names, -1, new Ui.Choice() {
            @Override
            public void on(int which) {
                if (which >= 0 && which < names.length) pickChannel(m, names[which].trim());
            }
        });
    }

    private void pickChannel(Match m, String broadcast) {
        pendingBroadcast = broadcast;
        pendingMatchKey = BroadcastApi.key(m);
        startActivityForResult(new Intent(this, ChannelPickActivity.class)
                .putExtra(ChannelPickActivity.EXTRA_TITLE, broadcast), REQ_PICK_CHANNEL);
    }

    private void refreshBroadcasts() {
        tvDone = false;
        tvLoading = false;
        loadBroadcasts();
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_PICK_CHANNEL) return;
        if (res != RESULT_OK || data == null || pendingBroadcast == null) {
            pendingBroadcast = null;
            pendingMatchKey = null;
            return;
        }
        String id = data.getStringExtra(ChannelPickActivity.EXTRA_RESULT_ID);
        String name = data.getStringExtra(ChannelPickActivity.EXTRA_RESULT_NAME);
        if (id == null || id.isEmpty()) return;
        BroadcastApi.link(pendingBroadcast, id, name);
        Ui.toast(this, getString(R.string.sports_link_done_x, pendingBroadcast, name));
        // Ayni mac icin kurulu hatirlatma varsa kanal bilgisini tazele.
        if (pendingMatchKey != null) {
            int lead = MatchReminder.leadFor(pendingMatchKey);
            for (Match m : all) {
                if (!BroadcastApi.key(m).equals(pendingMatchKey)) continue;
                if (lead >= 0) {
                    MatchReminder.schedule(this, pendingMatchKey, matchTitle(m), m.start, lead, id, name);
                }
                break;
            }
        }
        pendingBroadcast = null;
        pendingMatchKey = null;
        refreshBroadcasts();
    }

    private void toggleTeam(String team) {
        boolean on = Prefs.toggleFavoriteTeam(team);
        Ui.toast(SportsActivity.this, getString(on ? R.string.sports_followed_x : R.string.sports_unfollowed_x, team));
        if (tab == 1) buildTeamStrip();
        render();
    }

    private static String matchTitle(Match m) {
        return (m.home.isEmpty() ? "" : m.home + " - ") + m.away;
    }

    private static String time(long ms) {
        return new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(ms));
    }

    private static String dayKey(long ms) {
        return new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date(ms));
    }

    /** Bugun / Yarin / 3 Kas */
    private String day(long ms) {
        Date d = new Date(ms);
        String today = dayKey(System.currentTimeMillis());
        String that = dayKey(ms);
        if (that.equals(today)) return getString(R.string.sports_today);
        Calendar c = Calendar.getInstance();
        c.add(Calendar.DAY_OF_MONTH, 1);
        if (that.equals(dayKey(c.getTimeInMillis()))) return getString(R.string.sports_tomorrow);
        return new SimpleDateFormat("d MMM", Locale.getDefault()).format(d);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (adapter == null || all.isEmpty()) return;
        buildTeamStrip();
        render();
        applyAutoReminders();
    }

    // ------------------------------------------------------------ Mac satiri

    private class MatchAdapter extends BaseAdapter {
        private final LayoutInflater inf = LayoutInflater.from(SportsActivity.this);
        private final List<Object> data = new ArrayList<>();

        void set(List<Object> l) {
            data.clear();
            data.addAll(l);
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return data.size();
        }

        @Override
        public Object getItem(int p) {
            return data.get(p);
        }

        @Override
        public long getItemId(int p) {
            return p;
        }

        @Override
        public int getViewTypeCount() {
            return 2;
        }

        @Override
        public int getItemViewType(int p) {
            return data.get(p) instanceof Match ? 1 : 0;
        }

        @Override
        public boolean isEnabled(int p) {
            return data.get(p) instanceof Match;
        }

        @Override
        public View getView(int p, View v, ViewGroup parent) {
            Object row = data.get(p);
            if (!(row instanceof Match)) {
                if (v == null) v = inf.inflate(R.layout.row_day, parent, false);
                ((TextView) v.findViewById(R.id.dayTitle)).setText((String) row);
                return v;
            }
            if (v == null) v = inf.inflate(R.layout.row_match, parent, false);
            Match m = (Match) row;

            ((TextView) v.findViewById(R.id.league)).setText(m.league);
            ImageLoader.get().load((ImageView) v.findViewById(R.id.leagueLogo),
                    m.leagueLogo, R.drawable.ph_team, dp(30), dp(30));

            ImageView homeLogo = v.findViewById(R.id.homeLogo);
            ImageView awayLogo = v.findViewById(R.id.awayLogo);
            ImageLoader.get().load(homeLogo, m.homeLogo, R.drawable.ph_team, dp(42), dp(42));
            ImageLoader.get().load(awayLogo, m.awayLogo, R.drawable.ph_team, dp(42), dp(42));
            homeLogo.setVisibility(m.home.isEmpty() ? View.GONE : View.VISIBLE);
            awayLogo.setVisibility(m.away.isEmpty() ? View.GONE : View.VISIBLE);

            String starred = (Prefs.isFavoriteTeam(m.home) || Prefs.isFavoriteTeam(m.away)) ? "★ " : "";
            ((TextView) v.findViewById(R.id.homeName)).setText(starred + m.home);
            ((TextView) v.findViewById(R.id.awayName)).setText(m.away);

            TextView timeView = v.findViewById(R.id.time);
            if (m.live()) {
                timeView.setText("● " + getString(R.string.sports_live));
                timeView.setTextColor(getResources().getColor(R.color.bad));
            } else {
                timeView.setText(time(m.start));
                timeView.setTextColor(getResources().getColor(R.color.accent));
            }

            TextView score = v.findViewById(R.id.score);
            score.setText(m.live() && !m.score.isEmpty() ? m.score : "–");

            TextView bc = v.findViewById(R.id.broadcast);
            BroadcastApi.MatchTv tv = tvInfo.get(BroadcastApi.key(m));
            String label = tv == null ? "" : tv.rowLabel();
            if (label.isEmpty()) {
                bc.setVisibility(View.GONE);
            } else {
                bc.setText(label);
                bc.setVisibility(View.VISIBLE);
            }
            return v;
        }
    }
}
