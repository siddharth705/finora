package com.finora.util;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The finite icon/color token vocabulary a Category's icon/color columns are validated against.
 * Deliberately closed rather than accepting arbitrary lucide-react names or hex codes -- every
 * category (system and user) renders from the same small, curated palette.
 */
public final class CategoryPalette {

    private CategoryPalette() {}

    public static final Map<String, String> ICONS = new LinkedHashMap<>();
    static {
        ICONS.put("tag", "Tag");
        ICONS.put("home", "Home");
        ICONS.put("shopping-cart", "Groceries");
        ICONS.put("utensils", "Dining");
        ICONS.put("car", "Transport");
        ICONS.put("zap", "Utilities");
        ICONS.put("shopping-bag", "Shopping");
        ICONS.put("heart-pulse", "Health");
        ICONS.put("film", "Entertainment");
        ICONS.put("trending-up", "Investing");
        ICONS.put("percent", "Fees");
        ICONS.put("repeat", "Transfer");
        ICONS.put("users", "People");
        ICONS.put("landmark", "Loan");
        ICONS.put("shield", "Insurance");
        ICONS.put("graduation-cap", "Education");
        ICONS.put("refresh-cw", "Subscription");
        ICONS.put("plane", "Travel");
        ICONS.put("gift", "Gifts");
        ICONS.put("paw-print", "Pets");
        ICONS.put("sofa", "Home & Furnishing");
        ICONS.put("receipt", "Taxes");
        ICONS.put("banknote", "Cash");
        ICONS.put("briefcase", "Business");
        ICONS.put("arrow-down-circle", "Income");
    }

    public static final Map<String, String> COLORS = new LinkedHashMap<>();
    static {
        COLORS.put("gray", "#6b7280");
        COLORS.put("blue", "#2563eb");
        COLORS.put("green", "#16a34a");
        COLORS.put("red", "#dc2626");
        COLORS.put("orange", "#ea580c");
        COLORS.put("yellow", "#d97706");
        COLORS.put("purple", "#7c3aed");
        COLORS.put("pink", "#db2777");
        COLORS.put("teal", "#0d9488");
    }

    public static boolean isValidIcon(String token) {
        return token != null && ICONS.containsKey(token);
    }

    public static boolean isValidColor(String token) {
        return token != null && COLORS.containsKey(token);
    }
}
