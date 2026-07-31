-- Global rule seed data (docs/rule-engine-relationship-engine-eds.md §3.1): a deliberately
-- reviewed SUBSET of CategoryRules.RULES (util package), not a wholesale automatic conversion.
--
-- Left out on purpose: any keyword short/ambiguous enough to be a substring of an unrelated
-- word -- e.g. "ola" (Transport) also matches inside "cola", "rent" (Rent) matches inside
-- "current", "emi"/"ngo" match inside "premium"/"academic"/"mongo"/"flamingo" -- see
-- CategoryRules.java's own comments on exactly these false positives, which it avoids via
-- word-boundary regex matching. category_rules' CONTAINS operator (RuleEngineService.matches)
-- is a plain case-insensitive substring check, not word-boundary-aware, so only keywords with no
-- realistic substring-collision risk are seeded here. Widening CONTAINS to word-boundary
-- matching, or seeding the riskier keywords once it is, is a fast-follow -- not silently done
-- with a weaker check now.
INSERT INTO category_rules (user_id, scope, field, operator, comparison_value, action_type, action_value, priority, enabled)
VALUES
    (NULL, 'GLOBAL', 'DESCRIPTION', 'CONTAINS', 'salary',       'ASSIGN_CATEGORY', 'Salary',        100, true),
    (NULL, 'GLOBAL', 'DESCRIPTION', 'CONTAINS', 'payroll',      'ASSIGN_CATEGORY', 'Salary',        100, true),
    (NULL, 'GLOBAL', 'DESCRIPTION', 'CONTAINS', 'stipend',      'ASSIGN_CATEGORY', 'Salary',        100, true),

    (NULL, 'GLOBAL', 'DESCRIPTION', 'CONTAINS', 'bigbasket',    'ASSIGN_CATEGORY', 'Groceries',     100, true),
    (NULL, 'GLOBAL', 'DESCRIPTION', 'CONTAINS', 'blinkit',      'ASSIGN_CATEGORY', 'Groceries',     100, true),
    (NULL, 'GLOBAL', 'DESCRIPTION', 'CONTAINS', 'zepto',        'ASSIGN_CATEGORY', 'Groceries',     100, true),
    (NULL, 'GLOBAL', 'DESCRIPTION', 'CONTAINS', 'dmart',        'ASSIGN_CATEGORY', 'Groceries',     100, true),

    (NULL, 'GLOBAL', 'DESCRIPTION', 'CONTAINS', 'swiggy',       'ASSIGN_CATEGORY', 'Dining',        100, true),
    (NULL, 'GLOBAL', 'DESCRIPTION', 'CONTAINS', 'zomato',       'ASSIGN_CATEGORY', 'Dining',        100, true),
    (NULL, 'GLOBAL', 'DESCRIPTION', 'CONTAINS', 'starbucks',    'ASSIGN_CATEGORY', 'Dining',        100, true),
    (NULL, 'GLOBAL', 'DESCRIPTION', 'CONTAINS', 'dominos',      'ASSIGN_CATEGORY', 'Dining',        100, true),
    (NULL, 'GLOBAL', 'DESCRIPTION', 'CONTAINS', 'mcdonald',     'ASSIGN_CATEGORY', 'Dining',        100, true),

    (NULL, 'GLOBAL', 'DESCRIPTION', 'CONTAINS', 'uber',         'ASSIGN_CATEGORY', 'Transport',     100, true),
    (NULL, 'GLOBAL', 'DESCRIPTION', 'CONTAINS', 'rapido',       'ASSIGN_CATEGORY', 'Transport',     100, true),
    (NULL, 'GLOBAL', 'DESCRIPTION', 'CONTAINS', 'irctc',        'ASSIGN_CATEGORY', 'Transport',     100, true),

    (NULL, 'GLOBAL', 'DESCRIPTION', 'CONTAINS', 'airtel',       'ASSIGN_CATEGORY', 'Utilities',     100, true),
    (NULL, 'GLOBAL', 'DESCRIPTION', 'CONTAINS', 'jio',          'ASSIGN_CATEGORY', 'Utilities',     100, true),

    (NULL, 'GLOBAL', 'DESCRIPTION', 'CONTAINS', 'amazon',       'ASSIGN_CATEGORY', 'Shopping',      100, true),
    (NULL, 'GLOBAL', 'DESCRIPTION', 'CONTAINS', 'flipkart',     'ASSIGN_CATEGORY', 'Shopping',      100, true),
    (NULL, 'GLOBAL', 'DESCRIPTION', 'CONTAINS', 'myntra',       'ASSIGN_CATEGORY', 'Shopping',      100, true),
    (NULL, 'GLOBAL', 'DESCRIPTION', 'CONTAINS', 'ajio',         'ASSIGN_CATEGORY', 'Shopping',      100, true),
    (NULL, 'GLOBAL', 'DESCRIPTION', 'CONTAINS', 'nykaa',        'ASSIGN_CATEGORY', 'Shopping',      100, true),
    (NULL, 'GLOBAL', 'DESCRIPTION', 'CONTAINS', 'decathlon',    'ASSIGN_CATEGORY', 'Shopping',      100, true),

    (NULL, 'GLOBAL', 'DESCRIPTION', 'CONTAINS', 'apollo',       'ASSIGN_CATEGORY', 'Health',        100, true),
    (NULL, 'GLOBAL', 'DESCRIPTION', 'CONTAINS', 'medplus',      'ASSIGN_CATEGORY', 'Health',        100, true),
    (NULL, 'GLOBAL', 'DESCRIPTION', 'CONTAINS', 'netmeds',      'ASSIGN_CATEGORY', 'Health',        100, true),

    (NULL, 'GLOBAL', 'DESCRIPTION', 'CONTAINS', 'netflix',      'ASSIGN_CATEGORY', 'Entertainment', 100, true),
    (NULL, 'GLOBAL', 'DESCRIPTION', 'CONTAINS', 'spotify',      'ASSIGN_CATEGORY', 'Entertainment', 100, true),
    (NULL, 'GLOBAL', 'DESCRIPTION', 'CONTAINS', 'bookmyshow',   'ASSIGN_CATEGORY', 'Entertainment', 100, true),
    (NULL, 'GLOBAL', 'DESCRIPTION', 'CONTAINS', 'hotstar',      'ASSIGN_CATEGORY', 'Entertainment', 100, true),

    (NULL, 'GLOBAL', 'DESCRIPTION', 'CONTAINS', 'zerodha',      'ASSIGN_CATEGORY', 'Investments',   100, true),
    (NULL, 'GLOBAL', 'DESCRIPTION', 'CONTAINS', 'groww',        'ASSIGN_CATEGORY', 'Investments',   100, true),
    (NULL, 'GLOBAL', 'DESCRIPTION', 'CONTAINS', 'upstox',       'ASSIGN_CATEGORY', 'Investments',   100, true),

    (NULL, 'GLOBAL', 'DESCRIPTION', 'CONTAINS', 'makemytrip',   'ASSIGN_CATEGORY', 'Travel',        100, true),
    (NULL, 'GLOBAL', 'DESCRIPTION', 'CONTAINS', 'goibibo',      'ASSIGN_CATEGORY', 'Travel',        100, true),
    (NULL, 'GLOBAL', 'DESCRIPTION', 'CONTAINS', 'yatra',        'ASSIGN_CATEGORY', 'Travel',        100, true),
    (NULL, 'GLOBAL', 'DESCRIPTION', 'CONTAINS', 'airbnb',       'ASSIGN_CATEGORY', 'Travel',        100, true),
    (NULL, 'GLOBAL', 'DESCRIPTION', 'CONTAINS', 'indigo',       'ASSIGN_CATEGORY', 'Travel',        100, true),
    (NULL, 'GLOBAL', 'DESCRIPTION', 'CONTAINS', 'spicejet',     'ASSIGN_CATEGORY', 'Travel',        100, true),
    (NULL, 'GLOBAL', 'DESCRIPTION', 'CONTAINS', 'vistara',      'ASSIGN_CATEGORY', 'Travel',        100, true),

    (NULL, 'GLOBAL', 'DESCRIPTION', 'CONTAINS', 'icloud',       'ASSIGN_CATEGORY', 'Subscriptions', 100, true),
    (NULL, 'GLOBAL', 'DESCRIPTION', 'CONTAINS', 'adobe',        'ASSIGN_CATEGORY', 'Subscriptions', 100, true),

    (NULL, 'GLOBAL', 'DESCRIPTION', 'CONTAINS', 'udemy',        'ASSIGN_CATEGORY', 'Education',     100, true),
    (NULL, 'GLOBAL', 'DESCRIPTION', 'CONTAINS', 'coursera',     'ASSIGN_CATEGORY', 'Education',     100, true),
    (NULL, 'GLOBAL', 'DESCRIPTION', 'CONTAINS', 'byjus',        'ASSIGN_CATEGORY', 'Education',     100, true),

    (NULL, 'GLOBAL', 'DESCRIPTION', 'CONTAINS', 'policybazaar', 'ASSIGN_CATEGORY', 'Insurance',     100, true);
