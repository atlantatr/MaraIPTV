package com.atlanta.maraplayer.ui;

import android.view.KeyEvent;
import android.view.View;
import android.widget.AbsListView;
import android.widget.GridView;

/**
 * Kumanda ile hizli liste gezinmesi (tum listelere eklenir):
 *  - En ustte YUKARI -> en alta, en altta ASAGI -> en uste sarar.
 *  - CH+/CH-, Sayfa Yukari/Asagi, ileri/geri sarma tuslari -> 10'ar oge atlar
 *    (poster izgarasinda 3'er satir).
 */
public final class ListNav {

    public static final int STEP = 10;

    private ListNav() {
    }

    public static void install(final AbsListView lv) {
        lv.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent e) {
                if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
                int n = lv.getCount();
                if (n == 0) return false;
                int pos = lv.getSelectedItemPosition();
                if (pos < 0) return false;
                int cols = 1;
                if (lv instanceof GridView) {
                    cols = ((GridView) lv).getNumColumns();
                    if (cols <= 0) cols = 1;
                }
                int step = cols > 1 ? cols * 3 : STEP;
                switch (keyCode) {
                    case KeyEvent.KEYCODE_DPAD_UP:
                        // en ust satirda yukari -> en alta (basili tutarken surekli donmesin)
                        if (pos < cols && e.getRepeatCount() == 0) {
                            int target = n - 1;
                            if (cols > 1) {
                                int col = pos % cols;
                                target = ((n - 1) / cols) * cols + col;
                                if (target >= n) target -= cols;
                                if (target < 0) target = n - 1;
                            }
                            select(lv, target);
                            return true;
                        }
                        return false;
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                        // en alt satirda asagi -> en uste
                        if (pos + cols >= n && ((pos / cols) == (n - 1) / cols) && e.getRepeatCount() == 0) {
                            select(lv, cols > 1 ? pos % cols : 0);
                            return true;
                        }
                        return false;
                    case KeyEvent.KEYCODE_CHANNEL_DOWN:
                    case KeyEvent.KEYCODE_PAGE_UP:
                    case KeyEvent.KEYCODE_MEDIA_REWIND:
                    case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                        select(lv, Math.max(0, pos - step));
                        return true;
                    case KeyEvent.KEYCODE_CHANNEL_UP:
                    case KeyEvent.KEYCODE_PAGE_DOWN:
                    case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                    case KeyEvent.KEYCODE_MEDIA_NEXT:
                        select(lv, Math.min(n - 1, pos + step));
                        return true;
                    case KeyEvent.KEYCODE_MOVE_HOME:
                        select(lv, 0);
                        return true;
                    case KeyEvent.KEYCODE_MOVE_END:
                        select(lv, n - 1);
                        return true;
                    default:
                        return false;
                }
            }
        });
    }

    private static void select(AbsListView lv, int pos) {
        lv.setSelection(pos);
        lv.requestFocus();
    }
}
