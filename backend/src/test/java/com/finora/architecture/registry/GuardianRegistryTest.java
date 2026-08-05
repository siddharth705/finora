package com.finora.architecture.registry;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Repository Guardian rule registry, and the reason it cannot rot.
 *
 * <p>A list of architecture rules kept in a markdown file is worth exactly as much as the last
 * person's discipline in updating it, which over a few years is nothing. So the registry is
 * derived from {@link GuardianRule} annotations on the rules themselves, and this test fails the
 * build whenever {@code docs/architecture/repository-guardian-rules.md} and the code disagree --
 * in either direction. Adding a rule without publishing it fails. Publishing a rule that no longer
 * exists fails. Editing a rule's intent in one place and not the other fails.
 *
 * <p>It also enforces the taxonomy: every {@code @Test} in the architecture package is either a
 * {@link GuardianRule} or a {@link GuardianSelfTest}. That is what makes "23 rules" a fact rather
 * than a claim -- a new rule cannot be quietly added without an id, and a self-test cannot be
 * miscounted as a rule.
 *
 * <p>Side effect by design: writes {@code target/guardian-rules.json} for
 * {@code scripts/guardian-report.py} to join against surefire results. The manifest is generated,
 * never committed.
 */
class GuardianRegistryTest {

    private static final Pattern ID_FORMAT = Pattern.compile("FG-\\d{3}");
    private static final Path REGISTRY_DOC =
            Paths.get("..", "docs", "architecture", "repository-guardian-rules.md");

    private record Rule(String id, GuardianRule annotation, Method method) {}

    // ---------------------------------------------------------------- discovery

    private List<Class<?>> architectureTestClasses() {
        Path testClasses;
        try {
            testClasses = Paths.get(GuardianRegistryTest.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("cannot locate test-classes", e);
        }
        Path pkg = testClasses.resolve("com/finora/architecture");
        try (Stream<Path> walk = Files.walk(pkg)) {
            return walk.filter(p -> p.toString().endsWith("Test.class"))
                    .map(p -> testClasses.relativize(p).toString()
                            .replace('\\', '.').replace('/', '.')
                            .replaceAll("\\.class$", ""))
                    .sorted()
                    .map(name -> {
                        try {
                            return Class.forName(name);
                        } catch (ClassNotFoundException e) {
                            throw new IllegalStateException(name, e);
                        }
                    })
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private List<Rule> rules() {
        List<Rule> rules = new ArrayList<>();
        for (Class<?> c : architectureTestClasses()) {
            for (Method m : c.getDeclaredMethods()) {
                GuardianRule a = m.getAnnotation(GuardianRule.class);
                if (a != null) {
                    rules.add(new Rule(a.id(), a, m));
                }
            }
        }
        rules.sort(Comparator.comparing(Rule::id));
        return rules;
    }

    private List<Method> selfTests() {
        List<Method> found = new ArrayList<>();
        for (Class<?> c : architectureTestClasses()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.isAnnotationPresent(GuardianSelfTest.class)) {
                    found.add(m);
                }
            }
        }
        return found;
    }

    // ---------------------------------------------------------------- integrity

    @Test
    void everyArchitectureTestIsEitherARuleOrASelfTest() {
        List<String> unclassified = new ArrayList<>();
        for (Class<?> c : architectureTestClasses()) {
            if (c.equals(GuardianRegistryTest.class)) {
                continue;
            }
            for (Method m : c.getDeclaredMethods()) {
                if (!m.isAnnotationPresent(Test.class)) {
                    continue;
                }
                if (!m.isAnnotationPresent(GuardianRule.class)
                        && !m.isAnnotationPresent(GuardianSelfTest.class)) {
                    unclassified.add(c.getSimpleName() + "." + m.getName());
                }
            }
        }

        assertThat(unclassified)
                .as("""
                        Every test in com.finora.architecture is either an enforced rule or a test \
                        OF a rule. Annotate it @GuardianRule (with the next free FG-NNN id) if it \
                        constrains production code, or @GuardianSelfTest(rule = "FG-NNN") if it \
                        proves an existing rule fires. Without this the published rule count is a \
                        guess, and a rule can be added without ever appearing in the registry.""")
                .isEmpty();
    }

    @Test
    void ruleIdsAreWellFormedUniqueAndGapless() {
        List<Rule> rules = rules();

        List<String> malformed = rules.stream()
                .map(Rule::id)
                .filter(id -> !ID_FORMAT.matcher(id).matches())
                .toList();
        assertThat(malformed).as("Rule ids are FG-NNN, three digits.").isEmpty();

        Set<String> seen = new HashSet<>();
        List<String> duplicates = rules.stream()
                .map(Rule::id)
                .filter(id -> !seen.add(id))
                .toList();
        assertThat(duplicates)
                .as("""
                        Two rules share an id. Ids are permanent references used in commit \
                        messages and CI output; a duplicate makes every one of them ambiguous.""")
                .isEmpty();

        List<Integer> numbers = rules.stream()
                .map(r -> Integer.parseInt(r.id().substring(3)))
                .sorted()
                .toList();
        List<String> gaps = new ArrayList<>();
        for (int i = 0; i < numbers.size(); i++) {
            if (numbers.get(i) != i + 1) {
                gaps.add("expected FG-%03d, found FG-%03d".formatted(i + 1, numbers.get(i)));
                break;
            }
        }
        assertThat(gaps)
                .as("""
                        Rule ids run contiguously from FG-001 with no gaps. A gap means a rule was \
                        deleted rather than retired -- retire it by removing the code AND its \
                        registry row in the same commit, then renumber nothing: take the next free \
                        number for the replacement and note the retirement in the registry's \
                        history section.""")
                .isEmpty();
    }

    @Test
    void everyRuleCarriesCompleteLifecycleMetadata() {
        List<String> incomplete = new ArrayList<>();
        for (Rule r : rules()) {
            GuardianRule a = r.annotation();
            if (a.intent().isBlank()) incomplete.add(r.id() + ": intent");
            if (a.source().isBlank()) incomplete.add(r.id() + ": source");
            if (a.owner().isBlank()) incomplete.add(r.id() + ": owner");
            if (!a.intent().endsWith(".")) incomplete.add(r.id() + ": intent must be a sentence");
            try {
                LocalDate.parse(a.introduced());
            } catch (DateTimeParseException e) {
                incomplete.add(r.id() + ": introduced must be ISO yyyy-MM-dd");
            }
        }

        assertThat(incomplete)
                .as("""
                        A rule with no recorded reason or source is an opinion that breaks the \
                        build, and in three years nobody will know whether it still applies. Every \
                        rule states what it prevents, where its authority comes from, when it \
                        started and which area owns it.""")
                .isEmpty();
    }

    @Test
    void selfTestClaimsAreBackedByActualSelfTests() {
        Set<String> ruleIds = rules().stream().map(Rule::id).collect(Collectors.toSet());
        Set<String> tested = selfTests().stream()
                .map(m -> m.getAnnotation(GuardianSelfTest.class).rule())
                .collect(Collectors.toSet());

        List<String> danglingSelfTests = tested.stream()
                .filter(id -> !ruleIds.contains(id))
                .sorted()
                .toList();
        assertThat(danglingSelfTests)
                .as("A @GuardianSelfTest points at a rule id that does not exist.")
                .isEmpty();

        List<String> claimedButUntested = rules().stream()
                .filter(r -> r.annotation().verification() == GuardianRule.Verification.SELF_TEST)
                .map(Rule::id)
                .filter(id -> !tested.contains(id))
                .toList();
        assertThat(claimedButUntested)
                .as("""
                        These rules claim verification = SELF_TEST but nothing points at them. \
                        Either add the self-test or downgrade the claim to MANUAL_FALSIFICATION -- \
                        an overstated verification level is worse than an honest weak one, because \
                        it is the field a reviewer uses to decide how much to trust the rule.""")
                .isEmpty();

        List<String> testedButNotClaimed = rules().stream()
                .filter(r -> r.annotation().verification() != GuardianRule.Verification.SELF_TEST)
                .map(Rule::id)
                .filter(tested::contains)
                .toList();
        assertThat(testedButNotClaimed)
                .as("These rules have self-tests; upgrade verification to SELF_TEST.")
                .isEmpty();
    }

    // ---------------------------------------------------------------- published registry

    private String canonicalRow(Rule r) {
        GuardianRule a = r.annotation();
        String exceptions = a.exceptions().isBlank() ? "None" : a.exceptions();
        return "| `%s` | %s | %s | %s | %s | %s | %s | %s |".formatted(
                r.id(), a.category(), a.intent(), a.source(), a.introduced(), a.owner(),
                a.verification(), exceptions);
    }

    @Test
    void thePublishedRegistryMatchesTheEnforcedRules() throws IOException {
        assertThat(REGISTRY_DOC)
                .as("The rule registry must exist at docs/architecture/"
                        + "repository-guardian-rules.md.")
                .exists();

        String doc = Files.readString(REGISTRY_DOC, StandardCharsets.UTF_8);
        List<Rule> rules = rules();

        List<String> missingOrChanged = rules.stream()
                .map(this::canonicalRow)
                .filter(row -> !doc.contains(row))
                .toList();
        assertThat(missingOrChanged)
                .as("""
                        The published registry no longer matches the enforced rules. Each row above \
                        is the canonical text -- paste it into the table in \
                        docs/architecture/repository-guardian-rules.md. This fails when a rule is \
                        added, retired, recategorised or reworded without the registry following in \
                        the same commit, which is the only way a hand-maintained list stays true.""")
                .isEmpty();

        Matcher m = Pattern.compile("\\|\\s*`(FG-\\d{3})`").matcher(doc);
        Set<String> published = new HashSet<>();
        while (m.find()) {
            published.add(m.group(1));
        }
        Set<String> enforced = rules.stream().map(Rule::id).collect(Collectors.toSet());
        List<String> publishedButNotEnforced = published.stream()
                .filter(id -> !enforced.contains(id))
                .sorted()
                .toList();
        assertThat(publishedButNotEnforced)
                .as("""
                        The registry advertises rules that nothing enforces, which is the most \
                        damaging kind of drift: a reader concludes the codebase is protected \
                        against something it is not. Remove the row, or restore the rule.""")
                .isEmpty();
    }

    @Test
    void theRegistryStatesTheRuleCountItActuallyHas() throws IOException {
        String doc = Files.readString(REGISTRY_DOC, StandardCharsets.UTF_8);
        Map<String, Long> byCategory = rules().stream()
                .collect(Collectors.groupingBy(r -> r.annotation().category().name(),
                        TreeMap::new, Collectors.counting()));

        List<String> wrong = new ArrayList<>();
        String total = "**%d enforced rules**".formatted(rules().size());
        if (!doc.contains(total)) {
            wrong.add("total: expected the text " + total);
        }
        byCategory.forEach((category, count) -> {
            String line = "| %s | %d |".formatted(category, count);
            if (!doc.contains(line)) {
                wrong.add("summary row: expected " + line);
            }
        });

        assertThat(wrong)
                .as("The registry's own counts must match the rules it lists.")
                .isEmpty();
    }

    // ---------------------------------------------------------------- manifest

    @Test
    void writeManifestForTheReportGenerator() throws IOException {
        Map<String, Set<String>> selfTestsByRule = new TreeMap<>();
        for (Method m : selfTests()) {
            selfTestsByRule
                    .computeIfAbsent(m.getAnnotation(GuardianSelfTest.class).rule(),
                            k -> new java.util.TreeSet<>())
                    .add(m.getDeclaringClass().getName() + "#" + m.getName());
        }

        StringBuilder json = new StringBuilder("[\n");
        List<Rule> rules = rules();
        for (int i = 0; i < rules.size(); i++) {
            Rule r = rules.get(i);
            GuardianRule a = r.annotation();
            json.append("  {")
                    .append(field("id", r.id())).append(",")
                    .append(field("category", a.category().name())).append(",")
                    .append(field("intent", a.intent())).append(",")
                    .append(field("source", a.source())).append(",")
                    .append(field("introduced", a.introduced())).append(",")
                    .append(field("owner", a.owner())).append(",")
                    .append(field("verification", a.verification().name())).append(",")
                    .append(field("exceptions", a.exceptions())).append(",")
                    .append(field("testClass", r.method().getDeclaringClass().getName()))
                    .append(",")
                    .append(field("testMethod", r.method().getName())).append(",")
                    .append("\"selfTests\":")
                    .append(selfTestsByRule.getOrDefault(r.id(), Set.of()).stream()
                            .map(s -> "\"" + escape(s) + "\"")
                            .collect(Collectors.joining(",", "[", "]")))
                    .append("}")
                    .append(i < rules.size() - 1 ? ",\n" : "\n");
        }
        json.append("]\n");

        Path out = Paths.get("target", "guardian-rules.json");
        Files.createDirectories(out.getParent());
        Files.writeString(out, json.toString(), StandardCharsets.UTF_8);

        assertThat(out).exists();
    }

    private String field(String name, String value) {
        return "\"" + name + "\":\"" + escape(value) + "\"";
    }

    private String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
