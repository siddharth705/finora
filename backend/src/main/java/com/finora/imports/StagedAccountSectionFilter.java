package com.finora.imports;

import com.finora.dto.ImportDto.StagedAccountSection;
import com.finora.dto.ImportDto.UnparseableRow;
import java.util.ArrayList;
import java.util.List;

/**
 * The single definition of which detected sections are offered to the user as ACCOUNTS, and
 * therefore the single definition of what a "section index" means anywhere downstream of staging.
 *
 * <h2>Why this is its own class</h2>
 *
 * <p>This logic used to live as a private method on {@link ImportService}, which made it reachable
 * from exactly one place -- the staging path that writes {@code sectionsJson}. Every index the
 * system later speaks in (the {@code confirmMultiSection} loop index, the implicit index 0 of a
 * single-account {@code confirmSession}) is an index into THIS method's OUTPUT. Anything that
 * re-runs {@link com.finora.imports.pdf.PdfPreviewGenerator} and indexes into its raw section list
 * is therefore indexing in a different coordinate space, and will silently attribute one section's
 * data to another section's label as soon as a non-account section precedes an account one -- the
 * ordinary shape of a real combined statement, whose deposit schedules are printed above the
 * savings ledger.
 *
 * <p>So the filter is shared rather than re-implemented: {@link ImportService} calls it when it
 * stages, and the shadow-mode re-derivation in {@code com.finora.imports.evidence} calls the same
 * function when it re-reads a staged section. Two callers of one deterministic function cannot
 * drift; two implementations of one rule always eventually do.
 *
 * <p>(That second caller is named only as a package here, deliberately. {@code
 * ShadowModeHasNoConsumerTest} scans production source outside the evidence package for the names
 * of evidence types and of the re-derivation service, so that "shadow mode has no consumer" is
 * enforced by a text scan rather than by review. This class is not a consumer -- the dependency
 * runs the other way -- but the scan is deliberately blunt, and satisfying it by rewording a
 * sentence is right where loosening its pattern would not be.)
 *
 * <p>It is pure -- input list to output list, no fields, no I/O, no mutation of its argument -- so
 * calling it a second time over a second, freshly regenerated section list is well defined and
 * costs nothing.
 */
public final class StagedAccountSectionFilter {

    private StagedAccountSectionFilter() {}

    /**
     * Stops offering a located table as a transaction ACCOUNT when it plainly isn't one, without
     * throwing its contents away.
     *
     * INTERIM. A real HDFC combined statement carries a term-deposit summary and a recurring-deposit
     * installment schedule alongside the savings account. Those are genuine financial products the
     * customer holds, and the correct end state is that they become Investments -- see the planned
     * product-classification stage, which will identify what each section IS (savings, current, FD,
     * RD, loan, overdraft, credit card, demat) and route it to the matching Finora domain before
     * anything is imported. Until that exists this method must not pretend to do it.
     *
     * What it fixes today is narrower and purely a defect: all three sections were presented as
     * ACCOUNTS, so the user was offered two empty ones to confirm. That is the same failure as the
     * repeated-account-banner bug by a different route -- asserting something is an account on
     * evidence that only shows it is a table.
     *
     * So a section with no transactions stops being offered as an account, and its rows move onto
     * the first surviving section as unparseable so the deposit details still surface for review
     * ("never lose information"). Nothing is discarded, and nothing here encodes a guess about what
     * those sections are -- that judgement belongs to product classification, not to a filter.
     * When every section is empty, the caller's zero-transaction guard reports the failure instead.
     *
     * <p><b>Note for anyone reading an index that came out of here:</b> the returned list is
     * renumbered from zero. Section {@code i} of this output is NOT section {@code i} of the input
     * whenever anything was dropped, and the merge below also means output section 0 is not
     * {@code ==} to any input element. Only ever pair an index with the list it was derived from.
     */
    public static List<StagedAccountSection> onlySectionsThatAreActuallyAccounts(
            List<StagedAccountSection> sections) {
        if (sections.size() <= 1) return sections;

        List<StagedAccountSection> accounts = sections.stream().filter(s -> !s.rows().isEmpty()).toList();
        if (accounts.isEmpty() || accounts.size() == sections.size()) return accounts.isEmpty() ? sections : accounts;

        List<UnparseableRow> carriedOver = new ArrayList<>(accounts.get(0).unparseableRows());
        for (StagedAccountSection dropped : sections) {
            if (dropped.rows().isEmpty()) carriedOver.addAll(dropped.unparseableRows());
        }
        StagedAccountSection first = accounts.get(0);
        List<StagedAccountSection> merged = new ArrayList<>(accounts);
        // Only unparseableRows is being replaced here; everything else must arrive on the other
        // side of this rebuild exactly as it went in, and verification is the field that did not.
        // The five-argument StagedAccountSection constructor defaults it to null, and this rebuild
        // took that default -- so a combined statement whose deposit tables were filtered out
        // reached the review screen with no verification, even though the surviving account's rules
        // had all run. That is the same loss as the two conversion sites in ImportService and
        // PdfPreviewGenerator, one step earlier: fixing only those two leaves this shape (savings
        // account plus a term-deposit table, a very ordinary combined statement) still silent.
        // See docs/architecture/system-design/pdfpreviewgenerator-verification-loss-investigation.md.
        merged.set(0, new StagedAccountSection(first.detectedAccount(), first.rows(), first.totalParsed(),
                first.flaggedDuplicates(), carriedOver, first.verification()));
        return merged;
    }
}
