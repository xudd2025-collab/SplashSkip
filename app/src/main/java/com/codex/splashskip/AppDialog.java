package com.codex.splashskip;

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.LinkedHashMap;
import java.util.Map;

/** App-owned dialog surface shared by help, forms, rule options and update prompts. */
final class AppDialog extends Dialog {
    static final int BUTTON_POSITIVE = -1, BUTTON_NEGATIVE = -2, BUTTON_NEUTRAL = -3;
    private static final int ACCENT = Color.rgb(116, 91, 211);
    private static final int INK = Color.rgb(40, 43, 60);
    private static final int MUTED = Color.rgb(108, 113, 136);
    private static final int SOFT = Color.rgb(241, 237, 255);
    private final Map<Integer, TextView> buttons = new LinkedHashMap<>();

    private AppDialog(Builder builder) {
        super(builder.context);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout surface = column();
        surface.setPadding(dp(22), dp(20), dp(22), dp(20));
        surface.setBackground(shape(Color.WHITE, 26));
        LinearLayout header = row();
        ImageView avatar = new ImageView(getContext());
        avatar.setImageResource(R.drawable.anime_avatar);
        avatar.setBackground(shape(SOFT, 14)); avatar.setClipToOutline(true);
        avatar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        header.addView(avatar, new LinearLayout.LayoutParams(dp(42), dp(42)));
        TextView title = text(builder.title, 18, INK);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setPadding(dp(12), 0, dp(6), 0);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        TextView close = button("×", false);
        close.setTextSize(23); close.setTextColor(MUTED);
        close.setContentDescription("关闭" + builder.title);
        close.setOnClickListener(v -> dismiss());
        header.addView(close, new LinearLayout.LayoutParams(dp(44), dp(44)));
        surface.addView(header, new LinearLayout.LayoutParams(-1, -2));

        ScrollView bodyScroll = new ScrollView(getContext()) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                int cap = (int)(getContext().getResources().getDisplayMetrics().heightPixels * .5f);
                super.onMeasure(widthSpec, View.MeasureSpec.makeMeasureSpec(cap, View.MeasureSpec.AT_MOST));
            }
        };
        bodyScroll.setFillViewport(false);
        bodyScroll.setVerticalScrollBarEnabled(false);
        View body = builder.view;
        if (body == null) {
            TextView message = text(builder.message == null ? "" : builder.message, 14, MUTED);
            message.setLineSpacing(dp(5), 1); body = message;
        }
        bodyScroll.addView(body, new ScrollView.LayoutParams(-1, -2));
        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(-1, -2);
        bodyParams.topMargin = dp(18); bodyParams.bottomMargin = dp(20);
        surface.addView(bodyScroll, bodyParams);

        LinearLayout actions = row();
        for (Map.Entry<Integer, Action> entry : builder.actions.entrySet()) {
            int id = entry.getKey(); Action action = entry.getValue();
            TextView target = button(action.label, id == BUTTON_POSITIVE);
            target.setOnClickListener(v -> {
                dismiss();
                if (action.listener != null) action.listener.onClick(this, id);
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(46), 1);
            if (actions.getChildCount() > 0) params.leftMargin = dp(8);
            actions.addView(target, params); buttons.put(id, target);
        }
        if (!builder.actions.isEmpty()) surface.addView(actions, new LinearLayout.LayoutParams(-1, -2));
        setContentView(surface);
        setCanceledOnTouchOutside(true);
        Window window = getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(.28f);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
    }
    @Override protected void onStart() {
        super.onStart();
        Window window = getWindow();
        if (window != null) {
            int width = Math.min(getContext().getResources().getDisplayMetrics().widthPixels - dp(40), dp(420));
            window.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT);
            window.setGravity(Gravity.CENTER);
        }
    }
    TextView getButton(int id) { return buttons.get(id); }
    private int dp(int value) { return (int)(value * getContext().getResources().getDisplayMetrics().density + .5f); }
    private LinearLayout column() { LinearLayout v = new LinearLayout(getContext()); v.setOrientation(LinearLayout.VERTICAL); return v; }
    private LinearLayout row() { LinearLayout v = new LinearLayout(getContext()); v.setGravity(Gravity.CENTER_VERTICAL); return v; }
    private GradientDrawable shape(int color, int radius) {
        GradientDrawable result = new GradientDrawable(); result.setColor(color); result.setCornerRadius(dp(radius)); return result;
    }
    private TextView text(String value, int size, int color) {
        TextView result = new TextView(getContext()); result.setText(value); result.setTextSize(size);
        result.setTextColor(color); result.setIncludeFontPadding(false); return result;
    }
    private TextView button(String value, boolean primary) {
        TextView result = text(value, 13, primary ? Color.WHITE : ACCENT);
        result.setGravity(Gravity.CENTER); result.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        result.setFocusable(true); result.setPadding(dp(5), dp(4), dp(5), dp(4));
        result.setBackground(new RippleDrawable(ColorStateList.valueOf(Color.argb(28, 116, 91, 211)),
                shape(primary ? ACCENT : SOFT, 14), shape(Color.WHITE, 14)));
        return result;
    }
    private static final class Action {
        final String label; final DialogInterface.OnClickListener listener;
        Action(String label, DialogInterface.OnClickListener listener) { this.label = label; this.listener = listener; }
    }
    static final class Builder {
        final Context context;
        String title = "", message;
        View view;
        final Map<Integer, Action> actions = new LinkedHashMap<>();
        Builder(Context context) { this.context = context; }
        Builder setTitle(String value) { title = value; return this; }
        Builder setMessage(String value) { message = value; return this; }
        Builder setView(View value) { view = value; return this; }
        Builder setNegativeButton(String label, DialogInterface.OnClickListener listener) { actions.put(BUTTON_NEGATIVE, new Action(label, listener)); return this; }
        Builder setNeutralButton(String label, DialogInterface.OnClickListener listener) { actions.put(BUTTON_NEUTRAL, new Action(label, listener)); return this; }
        Builder setPositiveButton(String label, DialogInterface.OnClickListener listener) { actions.put(BUTTON_POSITIVE, new Action(label, listener)); return this; }
        Builder setItems(String[] labels, DialogInterface.OnClickListener listener) {
            LinearLayout list = new LinearLayout(context); list.setOrientation(LinearLayout.VERTICAL);
            for (int i = 0; i < labels.length; i++) {
                final int index = i;
                TextView item = new TextView(context); item.setText(labels[i]); item.setTextColor(INK);
                item.setTextSize(14); int inset = (int)(14 * context.getResources().getDisplayMetrics().density + .5f);
                item.setPadding(inset, inset, inset, inset); item.setFocusable(true);
                item.setOnClickListener(v -> { Dialog dialog = (Dialog) list.getTag(); dialog.dismiss(); listener.onClick(dialog, index); });
                list.addView(item, new LinearLayout.LayoutParams(-1, -2));
            }
            view = list; return this;
        }
        AppDialog create() {
            AppDialog result = new AppDialog(this);
            if (view != null) view.setTag(result);
            return result;
        }
        AppDialog show() { AppDialog result = create(); result.show(); return result; }
    }
}
