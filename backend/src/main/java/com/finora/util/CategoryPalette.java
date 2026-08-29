package com.finora.util;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The finite icon/color token vocabulary a Category's icon/color columns are validated against.
 * Deliberately closed rather than accepting arbitrary lucide-react names or hex codes -- every
 * category (system and user) renders from the same small, curated palette.
 */
public final class CategoryPalette {

    private CategoryPalette() {}

    public static final Map<String, String> ICONS;
    static {
        Map<String, String> temp = new LinkedHashMap<>();
        temp.put("tag", "Tag");
        temp.put("home", "Home");
        temp.put("shopping-cart", "Groceries");
        temp.put("utensils", "Dining");
        temp.put("car", "Transport");
        temp.put("zap", "Utilities");
        temp.put("shopping-bag", "Shopping");
        temp.put("heart-pulse", "Health");
        temp.put("film", "Entertainment");
        temp.put("trending-up", "Investing");
        temp.put("percent", "Fees");
        temp.put("repeat", "Transfer");
        temp.put("users", "People");
        temp.put("landmark", "Loan");
        temp.put("shield", "Insurance");
        temp.put("graduation-cap", "Education");
        temp.put("refresh-cw", "Subscription");
        temp.put("plane", "Travel");
        temp.put("gift", "Gifts");
        temp.put("paw-print", "Pets");
        temp.put("sofa", "Home & Furnishing");
        temp.put("receipt", "Taxes");
        temp.put("banknote", "Cash");
        temp.put("briefcase", "Business");
        temp.put("arrow-down-circle", "Income");
        ICONS = Collections.unmodifiableMap(temp);
    }

    public static final Map<String, String> COLORS;
    static {
        Map<String, String> temp = new LinkedHashMap<>();
        temp.put("gray", "#6b7280");
        temp.put("blue", "#2563eb");
        temp.put("green", "#16a34a");
        temp.put("red", "#dc2626");
        temp.put("orange", "#ea580c");
        temp.put("yellow", "#d97706");
        temp.put("purple", "#7c3aed");
        temp.put("pink", "#db2777");
        temp.put("teal", "#0d9488");
        COLORS = Collections.unmodifiableMap(temp);
    }

    public static boolean isValidIcon(String token) {
        return token != null && ICONS.containsKey(token);
    }

    public static boolean isValidColor(String token) {
        return token != null && COLORS.containsKey(token);
    }
}
