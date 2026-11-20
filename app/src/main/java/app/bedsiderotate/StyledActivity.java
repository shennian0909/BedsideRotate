package app.bedsiderotate;

import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

abstract class StyledActivity extends BedsideActivity {
    Palette palette;
    boolean nightMode;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        nightMode = isNightMode();
        palette = new Palette(nightMode);
        configureWindow();
    }

    LinearLayout createPage(ScrollView scroll) {
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(palette.background);
        scroll.setVerticalScrollBarEnabled(false);

        boolean tablet = getResources().getConfiguration().smallestScreenWidthDp >= 600;
        int horizontalPadding = dp(tablet ? 32 : 18);

        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setPadding(horizontalPadding, dp(26), horizontalPadding, dp(36));
        shell.setBackgroundColor(palette.background);
        scroll.addView(shell, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return shell;
    }

    View createBrandHeader(String heading, String subtitle, View trailing) {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, dp(2), 0, dp(14));

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_rotation);
        icon.setColorFilter(nightMode ? Color.WHITE : palette.accentText);
        icon.setContentDescription(getString(R.string.app_name) + "图标");
        icon.setPadding(dp(9), dp(9), dp(9), dp(9));
        icon.setBackground(rounded(palette.accentSoft, dp(13), 0, Color.TRANSPARENT));
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(44), dp(44));
        iconParams.setMarginEnd(dp(12));
        header.addView(icon, iconParams);

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView title = text(heading, 28f, palette.ink, Typeface.BOLD);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        title.setLineSpacing(0f, 0.95f);
        titles.addView(title);
        if (subtitle != null) {
            TextView secondary = text(subtitle, 13f, palette.muted, Typeface.NORMAL);
            secondary.setPadding(0, dp(3), 0, 0);
            titles.addView(secondary);
        }
        header.addView(titles, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout.LayoutParams trailingParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        trailingParams.setMarginStart(dp(8));
        header.addView(trailing, trailingParams);
        return header;
    }

    GradientDrawable rounded(int color, float radius, int strokeWidth, int strokeColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        if (strokeWidth > 0) {
            drawable.setStroke(strokeWidth, strokeColor);
        }
        return drawable;
    }

    LinearLayout createCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(20), dp(20), dp(20), dp(20));
        card.setBackground(rounded(palette.surface, dp(18), dp(1), palette.line));
        card.setElevation(dp(nightMode ? 0 : 1));
        return card;
    }

    TextView sectionTitle(String value) {
        TextView view = text(value, 18f, palette.ink, Typeface.BOLD);
        view.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        return view;
    }

    TextView text(String value, float size, int color, int style) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(Typeface.create("sans-serif", style));
        view.setIncludeFontPadding(false);
        return view;
    }

    Button createButton(String label, ButtonStyle style) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(14f);
        button.setAllCaps(false);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(12), 0, dp(12), 0);
        button.setStateListAnimator(null);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        styleButton(button, style);
        return button;
    }

    void styleButton(Button button, ButtonStyle style) {
        int normalColor;
        int pressedColor;
        int textColor;
        int strokeColor = Color.TRANSPARENT;
        int strokeWidth = 0;

        switch (style) {
            case PRIMARY:
                normalColor = palette.accent;
                pressedColor = palette.accentPressed;
                textColor = palette.onAccent;
                break;
            case WARM:
                normalColor = palette.warm;
                pressedColor = palette.warmPressed;
                textColor = palette.onWarm;
                break;
            case SECONDARY:
                normalColor = palette.inset;
                pressedColor = palette.pressed;
                textColor = palette.ink;
                strokeColor = palette.line;
                strokeWidth = dp(1);
                break;
            case GHOST:
            default:
                normalColor = Color.TRANSPARENT;
                pressedColor = palette.accentSoft;
                textColor = palette.accentText;
                strokeColor = palette.line;
                strokeWidth = dp(1);
                break;
        }

        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed},
                rounded(pressedColor, dp(12), strokeWidth, strokeColor));
        states.addState(new int[]{android.R.attr.state_enabled},
                rounded(normalColor, dp(12), strokeWidth, strokeColor));
        states.addState(new int[]{}, rounded(palette.inset, dp(12), strokeWidth, palette.line));
        button.setBackground(states);
        button.setTextColor(textColor);
    }

    View createRule() {
        View view = new View(this);
        view.setBackgroundColor(palette.line);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        params.bottomMargin = dp(14);
        view.setLayoutParams(params);
        return view;
    }

    LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private boolean isNightMode() {
        int mode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    private void configureWindow() {
        Window window = getWindow();
        window.setStatusBarColor(palette.background);
        window.setNavigationBarColor(palette.surface);

        int visibility = window.getDecorView().getSystemUiVisibility();
        if (nightMode) {
            visibility &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            visibility &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        } else {
            visibility |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            visibility |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        window.getDecorView().setSystemUiVisibility(visibility);
    }

    enum ButtonStyle {
        PRIMARY,
        SECONDARY,
        WARM,
        GHOST
    }

    static final class Palette {
        final int background;
        final int surface;
        final int inset;
        final int pressed;
        final int ink;
        final int muted;
        final int faint;
        final int line;
        final int track;
        final int accent;
        final int accentPressed;
        final int accentSoft;
        final int accentText;
        final int onAccent;
        final int warm;
        final int warmPressed;
        final int warmSoft;
        final int onWarm;
        final int success;
        final int successSoft;

        Palette(boolean night) {
            if (night) {
                background = 0xFF07111F;
                surface = 0xFF0E1A2B;
                inset = 0xFF132238;
                pressed = 0xFF1A2C46;
                ink = 0xFFF5F8FC;
                muted = 0xFFA8B7CB;
                faint = 0xFF7E90A8;
                line = 0xFF263850;
                track = 0xFF2B405D;
                accent = 0xFF4F8DFF;
                accentPressed = 0xFF3977E8;
                accentSoft = 0xFF152B4B;
                accentText = 0xFF8CB9FF;
                onAccent = 0xFF07111F;
                warm = 0xFFF0B25F;
                warmPressed = 0xFFD99B48;
                warmSoft = 0xFF2A2114;
                onWarm = 0xFF1A1207;
                success = 0xFF66D7C6;
                successSoft = 0xFF102D2B;
            } else {
                background = 0xFFF3F6FA;
                surface = 0xFFFFFFFF;
                inset = 0xFFF7F9FC;
                pressed = 0xFFEAF0F8;
                ink = 0xFF0F172A;
                muted = 0xFF475569;
                faint = 0xFF718096;
                line = 0xFFD7E0EC;
                track = 0xFFE2E8F0;
                accent = 0xFF1E40AF;
                accentPressed = 0xFF19378F;
                accentSoft = 0xFFE8EEFF;
                accentText = 0xFF1E40AF;
                onAccent = 0xFFFFFFFF;
                warm = 0xFFB45309;
                warmPressed = 0xFF934207;
                warmSoft = 0xFFFFF2D8;
                onWarm = 0xFFFFFFFF;
                success = 0xFF0F766E;
                successSoft = 0xFFE2F5F1;
            }
        }
    }
}
