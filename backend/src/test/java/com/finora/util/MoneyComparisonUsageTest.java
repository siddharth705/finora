package com.finora.util;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The diagnostic half of {@link MoneyMath}: fails the build the moment any production code calls
 * {@code BigDecimal.equals()} directly, instead of relying on the next reviewer to remember that
 * it's scale-sensitive. This is precisely how {@code RuleEngineService}'s AMOUNT+EQUALS rules
 * ended up silently never matching -- {@code "1500.00".equals("1500")}-shaped logic with nothing
 * anywhere flagging it as wrong.
 *
 * <p>Unlike {@code OwnershipGuardUsageTest} (which has to scan raw class-file bytes for a string
 * literal, because ArchUnit's model doesn't expose those), this rule inspects actual method call
 * targets from ArchUnit's bytecode-derived call graph -- {@link JavaClass#getMethodCallsFromSelf()}
 * -- which is precise: it cannot be confused by a comment or a similarly-named method on an
 * unrelated type the way a text search could be.
 */
class MoneyComparisonUsageTest {

    private List<String> findBigDecimalEqualsCallers(String rootPackage, boolean excludeTestClasses) {
        ClassFileImporter importer = new ClassFileImporter();
        if (excludeTestClasses) {
            importer = importer.withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS);
        }
        JavaClasses classes = importer.importPackages(rootPackage);

        List<String> offenders = new ArrayList<>();
        for (JavaClass javaClass : classes) {
            if (javaClass.getPackageName().equals(MoneyMath.class.getPackageName())
                    && javaClass.getSimpleName().equals(MoneyMath.class.getSimpleName())) {
                continue; // MoneyMath itself is the one legitimate implementation
            }
            for (JavaMethodCall call : javaClass.getMethodCallsFromSelf()) {
                boolean callsBigDecimalEquals = call.getTarget().getOwner().isEquivalentTo(BigDecimal.class)
                        && call.getTarget().getName().equals("equals");
                if (callsBigDecimalEquals) {
                    offenders.add(javaClass.getFullName() + "." + call.getOrigin().getName() + "()");
                    break;
                }
            }
        }
        return offenders;
    }

    @Test
    void noProductionCodeComparesBigDecimalsWithEquals_exceptMoneyMathItself() {
        assertThat(findBigDecimalEqualsCallers("com.finora", true))
                .as("""
                        BigDecimal.equals() is scale-sensitive ("1500.00" != "1500") -- use \
                        MoneyMath.equalsValue()/isGreaterThan()/isLessThan() instead, the way \
                        RuleEngineService now does. See MoneyMath's class doc for the bug this \
                        prevents.""")
                .isEmpty();
    }

    /**
     * Guards the guard: proves the detection genuinely fires on the call shape it exists to
     * catch, using the same fixture-based approach as AdminEndpointAuthorizationTest and
     * OwnershipGuardUsageTest.
     */
    @Test
    void theRuleDetectsARawBigDecimalEqualsCall() {
        assertThat(findBigDecimalEqualsCallers("com.finora.util.fixtures", false))
                .contains("com.finora.util.fixtures.RawBigDecimalEqualsFixture.compare()");
    }
}
