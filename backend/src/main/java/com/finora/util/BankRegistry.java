package com.finora.util;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Single source of truth for "what bank is this account with" -- official name, short name,
 * brand color, logo asset path, and reference metadata (website, IFSC prefix, supported
 * statement formats/account types). Every parser detects a bank identifier (see {@link #detect})
 * and every UI surface resolves that identifier back to this same metadata (see {@link #get}),
 * so a bank's display name/color/logo only ever needs to change in one place.
 *
 * Covers the Indian banking ecosystem across four categories: public sector, private, small
 * finance, and foreign banks operating in India (see {@link Category}).
 *
 * --- Logos ---
 * logoPath points at "/assets/banks/{slug}.svg" -- a file that does NOT ship in this project.
 * This sandbox has no internet access to fetch image assets and no license to redistribute
 * ~40 trademarked bank logos, so no actual SVG/PNG files exist at those paths today. The
 * frontend's BankLogo component tries to load the file at runtime and falls back to a colored
 * initials badge when it's missing (see frontend/src/components/BankLogo.tsx) -- so dropping
 * real, properly-licensed SVG files into frontend/src/assets/banks/ (matching the slugs below)
 * is a zero-code-change way to switch a bank over to its real logo.
 *
 * --- IFSC prefixes ---
 * Used as a detection signal (see {@link #detectFromIfsc}) and as reference metadata. Populated
 * only where there's high confidence (verified against multiple public sources) -- left null
 * for the handful of smaller/foreign banks where sources conflicted, rather than guess and risk
 * a wrong detection. Bank codes are RBI-assigned and effectively permanent, but this list should
 * still be re-verified against an authoritative source (e.g. RBI's IFSC master) before being
 * relied on for anything beyond this app's best-effort auto-detection.
 *
 * --- SWIFT codes / customer care numbers ---
 * Deliberately NOT populated. Unlike IFSC prefixes (stable, well-documented, low risk if this
 * were somehow slightly off), a wrong SWIFT code or support phone number is actively misleading
 * for a user who trusts it. The schema field exists (swiftCode) for a future round that sources
 * these properly; every entry below leaves it null rather than fabricate a plausible-looking one.
 */
public final class BankRegistry {

    private BankRegistry() {}

    public enum Category { PUBLIC_SECTOR, PRIVATE, SMALL_FINANCE, FOREIGN }

    public record BankInfo(
            String id,
            String officialName,
            String shortName,
            String colorHex,
            String initials,
            String logoPath,
            Category category,
            String websiteUrl,
            String ifscPrefix,
            String swiftCode,
            List<String> supportedStatementFormats,
            List<String> supportedAccountTypes
    ) {}

    public static final String UNKNOWN_ID = "OTHER";

    // Every bank here can have a CSV statement parsed today -- the parser is generic/column-
    // signature-based, not bank-specific, so this is genuinely uniform rather than padded out.
    // PDF is listed nowhere: it's explicitly out of scope (see CsvImportService's class comment),
    // so no bank claims PDF support it can't actually honor.
    private static final List<String> CSV_ONLY = List.of("CSV");
    private static final List<String> RETAIL_ACCOUNT_TYPES = List.of("SAVINGS", "CREDIT_CARD");

    private static final Map<String, BankInfo> REGISTRY = new LinkedHashMap<>();
    private static final Map<String, String> ALIAS_TO_ID = new LinkedHashMap<>();
    private static final Map<String, String> IFSC_PREFIX_TO_ID = new LinkedHashMap<>();

    static {
        // ---------------- Public Sector Banks ----------------
        register("SBI", "State Bank of India", "SBI", "#22409A", "SBI", "sbi",
                Category.PUBLIC_SECTOR, "https://sbi.co.in", "SBIN",
                new String[]{"SBI", "SBIYONO", "YONO SBI", "STATEBANKOFINDIA"});
        register("PNB", "Punjab National Bank", "PNB", "#7A1029", "PNB", "pnb",
                Category.PUBLIC_SECTOR, "https://pnbindia.in", "PUNB",
                new String[]{"PNB", "PNBONE", "PNB ONE", "PUNJABNATIONALBANK"});
        register("BOB", "Bank of Baroda", "Bank of Baroda", "#F3711C", "BOB", "bob",
                Category.PUBLIC_SECTOR, "https://www.bankofbaroda.in", "BARB",
                new String[]{"BOB", "BANKOFBARODA", "BARODA"});
        register("CANARA", "Canara Bank", "Canara Bank", "#00563F", "CB", "canara",
                Category.PUBLIC_SECTOR, "https://canarabank.com", "CNRB",
                new String[]{"CANARA", "CANARABANK"});
        register("UNION", "Union Bank of India", "Union Bank", "#8B1D41", "UBI", "union",
                Category.PUBLIC_SECTOR, "https://www.unionbankofindia.co.in", "UBIN",
                new String[]{"UNIONBANK", "UNIONBANKOFINDIA"});
        register("INDIAN", "Indian Bank", "Indian Bank", "#004A87", "IB", "indian",
                Category.PUBLIC_SECTOR, "https://www.indianbank.in", "IDIB",
                new String[]{"INDIANBANK"});
        register("CENTRAL", "Central Bank of India", "Central Bank", "#8E1537", "CBI", "central",
                Category.PUBLIC_SECTOR, "https://www.centralbankofindia.co.in", "CBIN",
                new String[]{"CENTRALBANK", "CENTRALBANKOFINDIA"});
        register("BOI", "Bank of India", "Bank of India", "#E31E24", "BOI", "boi",
                Category.PUBLIC_SECTOR, "https://bankofindia.co.in", "BKID",
                new String[]{"BANKOFINDIA"});
        register("UCO", "UCO Bank", "UCO Bank", "#003DA5", "UCO", "uco",
                Category.PUBLIC_SECTOR, "https://www.ucobank.com", "UCBA",
                new String[]{"UCOBANK"});
        register("PSB", "Punjab & Sind Bank", "Punjab & Sind Bank", "#1B5E20", "PSB", "psb",
                Category.PUBLIC_SECTOR, "https://punjabandsindbank.co.in", "PSIB",
                new String[]{"PUNJABANDSINDBANK", "PUNJABSINDBANK"});
        register("MAHABANK", "Bank of Maharashtra", "Bank of Maharashtra", "#00539B", "BOM", "mahabank",
                Category.PUBLIC_SECTOR, "https://bankofmaharashtra.in", "MAHB",
                new String[]{"BANKOFMAHARASHTRA", "MAHABANK"});

        // ---------------- Private Banks ----------------
        register("HDFC", "HDFC Bank", "HDFC Bank", "#004C8F", "HDFC", "hdfc",
                Category.PRIVATE, "https://www.hdfcbank.com", "HDFC",
                new String[]{"HDFC", "HDFCBANK", "TATA NEU"});
        register("ICICI", "ICICI Bank", "ICICI Bank", "#F37021", "ICICI", "icici",
                Category.PRIVATE, "https://www.icicibank.com", "ICIC",
                new String[]{"ICICI", "ICICIBANK"});
        register("AXIS", "Axis Bank", "Axis Bank", "#97144D", "AXIS", "axis",
                Category.PRIVATE, "https://www.axisbank.com", "UTIB",
                new String[]{"AXIS", "AXISBANK", "NEO RUPAY"});
        register("KOTAK", "Kotak Mahindra Bank", "Kotak Mahindra", "#ED232A", "K", "kotak",
                Category.PRIVATE, "https://www.kotak.com", "KKBK",
                new String[]{"KOTAK", "KOTAKMAHINDRA", "KOTAKMAHINDRABANK"});
        register("INDUSIND", "IndusInd Bank", "IndusInd", "#B02A30", "IB", "indusind",
                Category.PRIVATE, "https://www.indusind.com", "INDB",
                new String[]{"INDUSIND", "INDUSINDBANK"});
        register("YES", "Yes Bank", "Yes Bank", "#00285E", "YES", "yes",
                Category.PRIVATE, "https://www.yesbank.in", "YESB",
                new String[]{"YESBANK"});
        register("IDFC", "IDFC FIRST Bank", "IDFC FIRST", "#8A2432", "IDFC", "idfc-first",
                Category.PRIVATE, "https://www.idfcfirstbank.com", "IDFB",
                new String[]{"IDFCFIRST", "IDFCFIRSTBANK", "IDFC"});
        register("FEDERAL", "Federal Bank", "Federal Bank", "#0C6B58", "FB", "federal",
                Category.PRIVATE, "https://www.federalbank.co.in", "FDRL",
                new String[]{"FEDERALBANK"});
        register("RBL", "RBL Bank", "RBL Bank", "#C8102E", "RBL", "rbl",
                Category.PRIVATE, "https://www.rblbank.com", "RATN",
                new String[]{"RBLBANK", "RATNAKARBANK"});
        register("SIB", "South Indian Bank", "South Indian Bank", "#004B87", "SIB", "sib",
                Category.PRIVATE, "https://www.southindianbank.com", "SIBL",
                new String[]{"SOUTHINDIANBANK"});
        register("KARNATAKA", "Karnataka Bank", "Karnataka Bank", "#8E1B2D", "KB", "karnataka",
                Category.PRIVATE, "https://karnatakabank.com", "KARB",
                new String[]{"KARNATAKABANK"});
        register("CITYUNION", "City Union Bank", "City Union Bank", "#F58220", "CUB", "cityunion",
                Category.PRIVATE, "https://www.cityunionbank.com", "CIUB",
                new String[]{"CITYUNIONBANK"});
        register("DCB", "DCB Bank", "DCB Bank", "#EF3E36", "DCB", "dcb",
                Category.PRIVATE, "https://www.dcbbank.com", "DCBL",
                new String[]{"DCBBANK"});
        register("KVB", "Karur Vysya Bank", "Karur Vysya Bank", "#4A1D3F", "KVB", "kvb",
                Category.PRIVATE, "https://www.kvb.co.in", "KVBL",
                new String[]{"KARURVYSYABANK", "KVB"});
        register("TMB", "Tamilnad Mercantile Bank", "Tamilnad Mercantile", "#00447C", "TMB", "tmb",
                Category.PRIVATE, "https://www.tmb.in", "TMBL",
                new String[]{"TAMILNADMERCANTILE", "TMB"});
        register("BANDHAN", "Bandhan Bank", "Bandhan Bank", "#8B1E3F", "BB", "bandhan",
                Category.PRIVATE, "https://www.bandhanbank.com", "BDBL",
                new String[]{"BANDHANBANK"});
        register("IDBI", "IDBI Bank", "IDBI Bank", "#004990", "IDBI", "idbi",
                Category.PRIVATE, "https://www.idbibank.in", "IBKL",
                new String[]{"IDBIBANK"});

        // ---------------- Small Finance Banks ----------------
        register("AU", "AU Small Finance Bank", "AU Small Finance", "#004990", "AU", "au",
                Category.SMALL_FINANCE, "https://www.aubank.in", "AUBL",
                new String[]{"AUSMALLFINANCE", "AUSFB"});
        register("EQUITAS", "Equitas Small Finance Bank", "Equitas SFB", "#00A651", "EQ", "equitas",
                Category.SMALL_FINANCE, "https://www.equitasbank.com", null,
                new String[]{"EQUITAS", "EQUITASSFB"});
        register("UJJIVAN", "Ujjivan Small Finance Bank", "Ujjivan SFB", "#F58220", "UJ", "ujjivan",
                Category.SMALL_FINANCE, "https://www.ujjivansfb.in", "UJVN",
                new String[]{"UJJIVAN", "UJJIVANSFB"});
        register("JANA", "Jana Small Finance Bank", "Jana SFB", "#E31E24", "JA", "jana",
                Category.SMALL_FINANCE, "https://www.janabank.com", null,
                new String[]{"JANASFB", "JANASMALLFINANCE"});
        register("ESAF", "ESAF Small Finance Bank", "ESAF SFB", "#00A19A", "ES", "esaf",
                Category.SMALL_FINANCE, "https://www.esafbank.com", null,
                new String[]{"ESAFSFB", "ESAFSMALLFINANCE"});
        register("SURYODAY", "Suryoday Small Finance Bank", "Suryoday SFB", "#F7941D", "SU", "suryoday",
                Category.SMALL_FINANCE, "https://www.suryodaybank.com", null,
                new String[]{"SURYODAYSFB", "SURYODAYSMALLFINANCE"});
        register("UTKARSH", "Utkarsh Small Finance Bank", "Utkarsh SFB", "#8B1D41", "UT", "utkarsh",
                Category.SMALL_FINANCE, "https://www.utkarsh.bank", "UTKS",
                new String[]{"UTKARSHSFB", "UTKARSHSMALLFINANCE"});
        register("UNITY", "Unity Small Finance Bank", "Unity SFB", "#1B1464", "UN", "unity",
                Category.SMALL_FINANCE, "https://www.unitybank.co.in", null,
                new String[]{"UNITYSFB", "UNITYSMALLFINANCE"});

        // ---------------- Foreign Banks ----------------
        register("HSBC", "HSBC", "HSBC", "#DB0011", "HSBC", "hsbc",
                Category.FOREIGN, "https://www.hsbc.co.in", "HSBC",
                new String[]{"HSBC"});
        register("SCB", "Standard Chartered", "Standard Chartered", "#0473EA", "SC", "standard-chartered",
                Category.FOREIGN, "https://www.sc.com/in", "SCBL",
                new String[]{"STANDARDCHARTERED", "STANCHART"});
        register("DBS", "DBS Bank", "DBS Bank", "#E31937", "DBS", "dbs",
                Category.FOREIGN, "https://www.dbs.com/in", "DBSS",
                new String[]{"DBSBANK"});
        register("DEUTSCHE", "Deutsche Bank", "Deutsche Bank", "#0018A8", "DB", "deutsche",
                Category.FOREIGN, "https://www.db.com/india", null,
                new String[]{"DEUTSCHEBANK"});
        register("CITI", "Citibank", "Citi", "#003B70", "CITI", "citi",
                Category.FOREIGN, "https://www.online.citibank.co.in", null,
                new String[]{"CITIBANK", "CITI"});

        // Always registered last, and never matched via alias or IFSC -- get()/all() fall back
        // to it explicitly rather than through either lookup table.
        REGISTRY.put(UNKNOWN_ID, new BankInfo(UNKNOWN_ID, null, "Bank", "#64748B", "",
                "/assets/banks/generic.svg", null, null, null, null, CSV_ONLY, RETAIL_ACCOUNT_TYPES));
    }

    private static void register(String id, String officialName, String shortName, String colorHex,
                                  String initials, String logoSlug, Category category, String websiteUrl,
                                  String ifscPrefix, String[] aliases) {
        BankInfo info = new BankInfo(id, officialName, shortName, colorHex, initials,
                "/assets/banks/" + logoSlug + ".svg", category, websiteUrl, ifscPrefix, null,
                CSV_ONLY, RETAIL_ACCOUNT_TYPES);
        REGISTRY.put(id, info);
        for (String alias : aliases) {
            ALIAS_TO_ID.put(normalize(alias), id);
        }
        if (ifscPrefix != null) {
            IFSC_PREFIX_TO_ID.put(ifscPrefix, id);
        }
    }

    /**
     * Multi-signal detection, in priority order (most to least reliable):
     *   1. Bank name found in the statement's own header/metadata text (extraTextHints) --
     *      the bank's own letterhead is a stronger signal than anything the user could rename.
     *   2. IFSC prefix found anywhere in that same statement text -- RBI-assigned, unambiguous
     *      when present (see {@link #detectFromIfsc}).
     *   3. Bank name/alias found in the filename -- weakest reliable signal, since users rename
     *      files freely (this was the exact bug report that motivated this registry: a renamed
     *      file was previously shown verbatim as the account's bank name).
     * A 4th signal -- CSV column-layout signatures per bank -- is intentionally NOT implemented:
     * doing it honestly would require real sample exports from each bank to derive a genuine
     * signature from, which this project doesn't have for most of the 40 banks above. Rather
     * than fabricate signatures that would silently misdetect, this step is skipped entirely.
     * A 5th signal, PDF template matching, doesn't apply -- PDF import isn't implemented (see
     * CsvImportService). The final fallback, manual user selection, happens one layer up: the
     * frontend lets the user pick/correct the bank on the import review screen before confirming,
     * which is passed through as an explicit bankId that overrides whatever this method returns.
     */
    public static BankInfo detect(String filename, List<String> extraTextHints) {
        String hintsCombined = normalize(joinHints(extraTextHints));

        // Signal 1: bank name/alias inside the statement's own text.
        BankInfo byHintAlias = matchAlias(hintsCombined);
        if (byHintAlias != null) return byHintAlias;

        // Signal 2: IFSC prefix inside the statement's own text.
        BankInfo byIfsc = detectFromIfsc(extraTextHints);
        if (byIfsc != null) return byIfsc;

        // Signal 3: filename.
        BankInfo byFilename = matchAlias(normalize(filename));
        if (byFilename != null) return byFilename;

        return REGISTRY.get(UNKNOWN_ID);
    }

    private static String joinHints(List<String> extraTextHints) {
        if (extraTextHints == null) return "";
        StringBuilder sb = new StringBuilder();
        for (String hint : extraTextHints) {
            if (hint != null && !hint.isBlank()) sb.append(' ').append(hint);
        }
        return sb.toString();
    }

    private static BankInfo matchAlias(String normalizedHaystack) {
        if (normalizedHaystack.isEmpty()) return null;
        for (Map.Entry<String, String> alias : ALIAS_TO_ID.entrySet()) {
            if (!alias.getKey().isEmpty() && normalizedHaystack.contains(alias.getKey())) {
                return REGISTRY.get(alias.getValue());
            }
        }
        return null;
    }

    /** Scans raw (non-normalized -- case matters for this one, since IFSC codes are conventionally
     *  upper-case and this regex requires it to avoid false-positives on ordinary lowercase text)
     *  hint text for an 11-character IFSC-shaped token (4 letters, then '0', then 6 alphanumerics)
     *  and looks its 4-letter prefix up against known bank prefixes. */
    private static BankInfo detectFromIfsc(List<String> extraTextHints) {
        if (extraTextHints == null) return null;
        java.util.regex.Pattern ifscPattern = java.util.regex.Pattern.compile("\\b([A-Z]{4})0[A-Z0-9]{6}\\b");
        for (String hint : extraTextHints) {
            if (hint == null) continue;
            var matcher = ifscPattern.matcher(hint);
            while (matcher.find()) {
                String prefix = matcher.group(1);
                String bankId = IFSC_PREFIX_TO_ID.get(prefix);
                if (bankId != null) return REGISTRY.get(bankId);
            }
        }
        return null;
    }

    /** Never null -- an unrecognized/blank id resolves to the OTHER entry rather than throwing,
     *  since bankId can be missing on accounts created before this field existed. */
    public static BankInfo get(String id) {
        if (id == null) return REGISTRY.get(UNKNOWN_ID);
        return REGISTRY.getOrDefault(id, REGISTRY.get(UNKNOWN_ID));
    }

    /** All registered banks except the OTHER fallback -- for the manual "add account" bank
     *  picker (grouped by category) and the /api/v1/banks listing. */
    public static List<BankInfo> all() {
        return REGISTRY.values().stream().filter(b -> !UNKNOWN_ID.equals(b.id())).toList();
    }

    /** Case-insensitive substring search over id/officialName/shortName -- backs the "Search
     *  Bank" step of manual account creation and bank-aware transaction search. */
    public static List<BankInfo> search(String query) {
        if (query == null || query.isBlank()) return all();
        String q = query.trim().toLowerCase(Locale.ROOT);
        return all().stream()
                .filter(b -> (b.officialName() != null && b.officialName().toLowerCase(Locale.ROOT).contains(q))
                        || b.shortName().toLowerCase(Locale.ROOT).contains(q)
                        || b.id().toLowerCase(Locale.ROOT).contains(q))
                .toList();
    }

    private static String normalize(String s) {
        return s == null ? "" : s.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }
}
