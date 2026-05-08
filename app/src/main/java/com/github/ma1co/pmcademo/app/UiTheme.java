package com.github.ma1co.pmcademo.app;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.TextView;

/**
 * Shared visual system for JPEG.CAM 2.0.
 *
 * Uses only simple colors and GradientDrawable panels so the UI stays light on
 * camera memory and does not add bitmap assets or heavier rendering paths.
 */
public final class UiTheme {
    public static final int SURFACE = Color.argb(238, 7, 11, 13);
    public static final int SURFACE_SOFT = Color.argb(188, 9, 14, 16);
    public static final int SURFACE_STRONG = Color.argb(248, 6, 9, 11);
    public static final int SURFACE_RAISED = Color.argb(214, 14, 18, 18);
    public static final int MENU_BACKDROP = Color.rgb(5, 8, 10);
    public static int TEXT = Color.rgb(239, 246, 243);
    public static int TEXT_MUTED = Color.rgb(143, 157, 154);
    public static int TEXT_DIM = Color.rgb(68, 78, 78);
    public static int TEXT_ON_ACCENT = Color.rgb(239, 246, 243);
    public static int ACCENT = Color.rgb(234, 133, 48);
    public static int ACCENT_DARK = Color.rgb(104, 57, 22);
    public static int ACCENT_RECIPES = Color.rgb(234, 133, 48);
    public static int ACCENT_SETTINGS = Color.rgb(234, 133, 48);
    public static int ACCENT_NETWORK = Color.rgb(234, 133, 48);
    public static int ACCENT_SUPPORT = Color.rgb(234, 133, 48);
    public static int WARN = Color.rgb(236, 186, 84);
    public static int SUCCESS = Color.rgb(70, 218, 142);
    public static int ERROR = Color.rgb(235, 74, 83);
    public static final int BORDER = Color.argb(130, 117, 145, 140);
    public static final int SHADOW = Color.argb(180, 0, 0, 0);

    public static final int THEME_ORANGE = 0;
    public static final int THEME_BLUE = 1;
    public static final int THEME_GREEN = 2;
    public static final int THEME_RED = 3;
    public static final int THEME_YELLOW = 4;
    public static final int THEME_PURPLE = 5;

    private static final String[] THEME_NAMES = {
            "ORANGE", "BLUE", "GREEN", "RED", "YELLOW", "PURPLE"
    };
    private static final int[] THEME_ACCENTS = {
            Color.rgb(234, 133, 48),
            Color.rgb(78, 172, 232),
            Color.rgb(70, 218, 142),
            Color.rgb(235, 74, 83),
            Color.rgb(236, 186, 84),
            Color.rgb(178, 136, 236)
    };
    private static final int[] THEME_ACCENT_TEXT = {
            Color.rgb(239, 246, 243),
            Color.rgb(239, 246, 243),
            Color.rgb(6, 24, 18),
            Color.rgb(239, 246, 243),
            Color.rgb(32, 24, 8),
            Color.rgb(239, 246, 243)
    };

    private UiTheme() {}

    public static int normalizeThemeIndex(int theme) {
        if (theme < 0) return THEME_ORANGE;
        if (theme >= THEME_NAMES.length) return THEME_ORANGE;
        return theme;
    }

    public static int themeCount() {
        return THEME_NAMES.length;
    }

    public static String themeName(int theme) {
        return THEME_NAMES[normalizeThemeIndex(theme)];
    }

    public static void setTheme(int theme) {
        int safeTheme = normalizeThemeIndex(theme);
        ACCENT = THEME_ACCENTS[safeTheme];
        TEXT_ON_ACCENT = THEME_ACCENT_TEXT[safeTheme];
        ACCENT_DARK = darken(ACCENT);
        ACCENT_RECIPES = ACCENT;
        ACCENT_SETTINGS = ACCENT;
        ACCENT_NETWORK = ACCENT;
        ACCENT_SUPPORT = ACCENT;
    }

    public static GradientDrawable rect(int color, int strokeColor, int strokeWidth, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        if (strokeWidth > 0) d.setStroke(strokeWidth, strokeColor);
        return d;
    }

    public static void panel(View view) {
        view.setBackgroundDrawable(rect(SURFACE, BORDER, 1, 8));
    }

    public static void softPanel(View view) {
        view.setBackgroundDrawable(rect(SURFACE_SOFT, Color.argb(90, 117, 145, 140), 1, 6));
    }

    public static void selected(View view) {
        view.setBackgroundDrawable(rect(tint(ACCENT, 96), ACCENT, 1, 6));
    }

    public static void selected(View view, int accent) {
        view.setBackgroundDrawable(rect(tint(accent, 96), accent, 1, 6));
    }

    public static void activePanel(View view, int accent) {
        view.setBackgroundDrawable(rect(tint(accent, 48), tint(accent, 150), 1, 6));
    }

    public static void activeOutlinePanel(View view, int accent) {
        view.setBackgroundDrawable(rect(SURFACE, accent, 2, 8));
    }

    public static void tabPanel(View view, int accent, boolean selected, boolean active) {
        if (selected) {
            view.setBackgroundDrawable(rect(tint(accent, 132), accent, 1, 7));
        } else if (active) {
            view.setBackgroundDrawable(rect(tint(accent, 66), tint(accent, 170), 1, 7));
        } else {
            view.setBackgroundDrawable(rect(SURFACE_SOFT, Color.argb(90, 117, 145, 140), 1, 7));
        }
    }

    public static void tilePanel(View view, int accent, boolean selected) {
        if (selected) {
            view.setBackgroundDrawable(rect(tint(accent, 142), accent, 2, 8));
        } else {
            view.setBackgroundDrawable(rect(SURFACE_RAISED, tint(accent, 170), 1, 8));
        }
    }

    public static void actionPanel(View view, int accent, boolean selected, boolean active) {
        if (selected) {
            view.setBackgroundDrawable(rect(active ? tint(accent, 116) : SURFACE_RAISED, accent, 2, 7));
        } else if (active) {
            view.setBackgroundDrawable(rect(Color.argb(170, 17, 18, 17), tint(accent, 130), 1, 7));
        } else {
            view.setBackgroundDrawable(rect(Color.argb(145, 13, 15, 15), Color.argb(70, 117, 145, 140), 1, 7));
        }
    }

    public static void pageTabPanel(View view, int accent, boolean selected, boolean active) {
        if (selected) {
            view.setBackgroundDrawable(rect(tint(accent, 132), accent, 2, 7));
        } else if (active) {
            view.setBackgroundDrawable(rect(Color.TRANSPARENT, accent, 1, 7));
        } else {
            view.setBackgroundDrawable(rect(SURFACE_SOFT, Color.argb(90, 117, 145, 140), 1, 7));
        }
    }

    public static void railPanel(View view, int accent, boolean active, boolean selected) {
        if (selected) {
            view.setBackgroundDrawable(rect(tint(accent, 142), accent, 2, 7));
        } else if (active) {
            view.setBackgroundDrawable(rect(tint(accent, 76), accent, 1, 7));
        } else {
            view.setBackgroundDrawable(rect(Color.argb(118, 13, 15, 15), Color.argb(70, 117, 145, 140), 1, 7));
        }
    }

    public static void titlePanel(View view, int accent) {
        view.setBackgroundDrawable(rect(SURFACE_SOFT, tint(accent, 130), 1, 7));
    }

    public static void clear(View view) {
        view.setBackgroundColor(Color.TRANSPARENT);
    }

    public static void selectedText(TextView tv) {
        tv.setTextColor(TEXT_ON_ACCENT);
        tv.setShadowLayer(2, 0, 0, SHADOW);
    }

    public static void mutedText(TextView tv) {
        tv.setTextColor(TEXT_MUTED);
        tv.setShadowLayer(0, 0, 0, Color.TRANSPARENT);
    }

    public static void dimText(TextView tv) {
        tv.setTextColor(TEXT_DIM);
        tv.setShadowLayer(0, 0, 0, Color.TRANSPARENT);
    }

    public static void applyStatusText(TextView tv, float size, Typeface typeface) {
        tv.setTextColor(TEXT);
        tv.setTextSize(size);
        tv.setShadowLayer(3, 0, 0, SHADOW);
        tv.setTypeface(typeface != null ? typeface : Typeface.DEFAULT_BOLD);
    }

    private static int tint(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    private static int darken(int color) {
        return Color.rgb(
                Math.max(0, Color.red(color) / 2),
                Math.max(0, Color.green(color) / 2),
                Math.max(0, Color.blue(color) / 2));
    }
}
