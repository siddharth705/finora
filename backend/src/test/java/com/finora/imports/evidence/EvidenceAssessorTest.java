package com.finora.imports.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finora.imports.pdf.TextSource;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class EvidenceAssessorTest {

    @Test
    void shareAnUpstreamFailureMode_isTrueForAnySharedNode() {
        ProvenanceNode acquisitionA = new ProvenanceNode.Acquisition(TextSource.NATIVE_PDF);
        ProvenanceNode acquisitionB = new ProvenanceNode.Acquisition(TextSource.OCR);
        ProvenanceNode shared = new ProvenanceNode.SectionAttribution(0, TextSource.NATIVE_PDF);

        assertThat(EvidenceAssessor.shareAnUpstreamFailureMode(
                List.of(acquisitionA, shared), List.of(acquisitionB, shared))).isTrue();
    }

    @Test
    void shareAnUpstreamFailureMode_isFalseWhenNoNodesInCommon() {
        ProvenanceNode acquisitionA = new ProvenanceNode.Acquisition(TextSource.NATIVE_PDF);
        ProvenanceNode acquisitionB = new ProvenanceNode.Acquisition(TextSource.OCR);

        assertThat(EvidenceAssessor.shareAnUpstreamFailureMode(
                List.of(acquisitionA), List.of(acquisitionB))).isFalse();
    }

    @Test
    void columnLayoutInterpretation_sharedAcrossDifferentAcquisitionSources_isFlagged() {
        // The scenario this model exists to catch: native and OCR runs, therefore different
        // Acquisition nodes, but both reconstructed against the same column-boundary
        // interpretation -- e.g. an OCR pass reusing the native page's own coordinate frame.
        ProvenanceNode.ColumnLayoutInterpretation sharedColumns =
                new ProvenanceNode.ColumnLayoutInterpretation(2, "anchors:120,340,480");

        List<ProvenanceNode> nativeChain =
                List.of(new ProvenanceNode.Acquisition(TextSource.NATIVE_PDF), sharedColumns);
        List<ProvenanceNode> ocrChain =
                List.of(new ProvenanceNode.Acquisition(TextSource.OCR), sharedColumns);

        assertThat(EvidenceAssessor.shareAnUpstreamFailureMode(nativeChain, ocrChain)).isTrue();
    }

    @Test
    void deriveStatus_fewerThanTwoFacts_isInsufficient() {
        FieldFact<String> onlyFact = new FieldFact<>(MaterialField.ACCOUNT_HOLDER, "Jane Doe",
                List.of(new ProvenanceNode.Acquisition(TextSource.NATIVE_PDF)));

        assertThat(EvidenceAssessor.deriveStatus(MaterialField.ACCOUNT_HOLDER, List.of(onlyFact)))
                .isEqualTo(EvidenceStatus.INSUFFICIENT);
        assertThat(EvidenceAssessor.deriveStatus(MaterialField.ACCOUNT_HOLDER, List.of()))
                .isEqualTo(EvidenceStatus.INSUFFICIENT);
    }

    @Test
    void deriveStatus_independentAgreeingFacts_isSupported() {
        FieldFact<String> native_ = new FieldFact<>(MaterialField.ACCOUNT_HOLDER, "Jane Doe",
                List.of(new ProvenanceNode.Acquisition(TextSource.NATIVE_PDF)));
        FieldFact<String> ocr = new FieldFact<>(MaterialField.ACCOUNT_HOLDER, "Jane Doe",
                List.of(new ProvenanceNode.Acquisition(TextSource.OCR)));

        assertThat(EvidenceAssessor.deriveStatus(MaterialField.ACCOUNT_HOLDER, List.of(native_, ocr)))
                .isEqualTo(EvidenceStatus.SUPPORTED);
    }

    @Test
    void deriveStatus_independentDisagreeingFacts_isConflicting() {
        FieldFact<String> native_ = new FieldFact<>(MaterialField.ACCOUNT_HOLDER, "Jane Doe",
                List.of(new ProvenanceNode.Acquisition(TextSource.NATIVE_PDF)));
        FieldFact<String> ocr = new FieldFact<>(MaterialField.ACCOUNT_HOLDER, "Jane D0e",
                List.of(new ProvenanceNode.Acquisition(TextSource.OCR)));

        assertThat(EvidenceAssessor.deriveStatus(MaterialField.ACCOUNT_HOLDER, List.of(native_, ocr)))
                .isEqualTo(EvidenceStatus.CONFLICTING);
    }

    @Test
    void deriveStatus_disagreementWinsOverAnUnrelatedAgreeingPair() {
        // Two independently-provenanced facts agree; a third, also independent, disagrees with
        // both. The real disagreement must not be masked by the agreeing pair.
        FieldFact<String> native_ = new FieldFact<>(MaterialField.ACCOUNT_HOLDER, "Jane Doe",
                List.of(new ProvenanceNode.Acquisition(TextSource.NATIVE_PDF)));
        FieldFact<String> ocr = new FieldFact<>(MaterialField.ACCOUNT_HOLDER, "Jane Doe",
                List.of(new ProvenanceNode.Acquisition(TextSource.OCR)));
        FieldFact<String> thirdSource = new FieldFact<>(MaterialField.ACCOUNT_HOLDER, "J. Doe",
                List.of(new ProvenanceNode.SectionAttribution(1, TextSource.NATIVE_PDF)));

        assertThat(EvidenceAssessor.deriveStatus(
                MaterialField.ACCOUNT_HOLDER, List.of(native_, ocr, thirdSource)))
                .isEqualTo(EvidenceStatus.CONFLICTING);
    }

    @Test
    void deriveStatus_onlyNonIndependentAgreement_isInsufficientByDefaultPolicy() {
        ProvenanceNode.ColumnLayoutInterpretation sharedColumns =
                new ProvenanceNode.ColumnLayoutInterpretation(0, "anchors:100,300");
        FieldFact<String> native_ = new FieldFact<>(MaterialField.TRANSACTION_AMOUNT, "55,000",
                List.of(new ProvenanceNode.Acquisition(TextSource.NATIVE_PDF), sharedColumns));
        FieldFact<String> ocr = new FieldFact<>(MaterialField.TRANSACTION_AMOUNT, "55,000",
                List.of(new ProvenanceNode.Acquisition(TextSource.OCR), sharedColumns));

        assertThat(EvidenceAssessor.deriveStatus(MaterialField.TRANSACTION_AMOUNT, List.of(native_, ocr)))
                .isEqualTo(EvidenceStatus.INSUFFICIENT);
    }

    @Test
    void deriveStatus_remediationPolicyCanClearASharedFailureModeToSupported() {
        ProvenanceNode.ColumnLayoutInterpretation sharedColumns =
                new ProvenanceNode.ColumnLayoutInterpretation(0, "anchors:100,300");
        FieldFact<String> native_ = new FieldFact<>(MaterialField.TRANSACTION_DIRECTION, "DEBIT",
                List.of(new ProvenanceNode.Acquisition(TextSource.NATIVE_PDF), sharedColumns));
        FieldFact<String> ocr = new FieldFact<>(MaterialField.TRANSACTION_DIRECTION, "DEBIT",
                List.of(new ProvenanceNode.Acquisition(TextSource.OCR), sharedColumns));

        // A policy may only ever promote an otherwise-disqualified AGREEING pair to count as
        // support -- it has no way to manufacture SUPPORTED or CONFLICTING directly.
        IndependenceRemediationPolicy independentlyConfirmed = (field, a, b) -> true;

        assertThat(EvidenceAssessor.deriveStatus(
                MaterialField.TRANSACTION_DIRECTION, List.of(native_, ocr), independentlyConfirmed))
                .isEqualTo(EvidenceStatus.SUPPORTED);
    }

    @Test
    void deriveStatus_nonIndependentDisagreement_isConflictingRegardlessOfPolicy() {
        // Same-source-multiple-locations disagreement (design §3.4): two facts that share an
        // upstream node but propose different values are a real contradiction. No policy can
        // explain this away -- remediate() is never even consulted for a disagreeing pair.
        ProvenanceNode.ColumnLayoutInterpretation sharedColumns =
                new ProvenanceNode.ColumnLayoutInterpretation(0, "anchors:100,300");
        FieldFact<String> first = new FieldFact<>(MaterialField.TRANSACTION_AMOUNT, "55,000",
                List.of(new ProvenanceNode.Acquisition(TextSource.NATIVE_PDF), sharedColumns));
        FieldFact<String> second = new FieldFact<>(MaterialField.TRANSACTION_AMOUNT, "5,000",
                List.of(new ProvenanceNode.Acquisition(TextSource.NATIVE_PDF), sharedColumns));

        IndependenceRemediationPolicy alwaysClears = (field, a, b) -> true;

        assertThat(EvidenceAssessor.deriveStatus(
                MaterialField.TRANSACTION_AMOUNT, List.of(first, second), alwaysClears))
                .isEqualTo(EvidenceStatus.CONFLICTING);
    }

    @Test
    void deriveStatus_rejectsFactForAWrongField() {
        FieldFact<String> wrongField = new FieldFact<>(MaterialField.IFSC, "HDFC0XXXXXX",
                List.of(new ProvenanceNode.Acquisition(TextSource.NATIVE_PDF)));

        assertThatThrownBy(() ->
                EvidenceAssessor.deriveStatus(MaterialField.ACCOUNT_HOLDER, List.of(wrongField)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- Adversarial review: attempts to break the model, per the post-fix re-review ---

    @Test
    void deriveStatus_rejectsNullFacts() {
        assertThatThrownBy(() -> EvidenceAssessor.deriveStatus(MaterialField.ACCOUNT_HOLDER, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void deriveStatus_duplicateObservation_sameValueAndProvenanceTwice_isNeverSupportedByDefault() {
        // A caller mistakenly recording the identical observation twice (aliasing, or a retry that
        // re-emits the same fact) must not manufacture SUPPORTED -- there is zero independent
        // evidence here, only one observation counted twice.
        FieldFact<String> fact = new FieldFact<>(MaterialField.ACCOUNT_HOLDER, "Jane Doe",
                List.of(new ProvenanceNode.Acquisition(TextSource.NATIVE_PDF)));

        assertThat(EvidenceAssessor.deriveStatus(MaterialField.ACCOUNT_HOLDER, List.of(fact, fact)))
                .isEqualTo(EvidenceStatus.INSUFFICIENT);
    }

    @Test
    void deriveStatus_sameAcquisitionNode_contradictoryValues_isConflicting() {
        // Same-source contradiction in its simplest shape: one Acquisition node, two different
        // values -- not merely "shares a column layout" but the exact same acquisition run.
        ProvenanceNode.Acquisition sameRun = new ProvenanceNode.Acquisition(TextSource.NATIVE_PDF);
        FieldFact<String> first = new FieldFact<>(MaterialField.TRANSACTION_AMOUNT, "55,000", List.of(sameRun));
        FieldFact<String> second = new FieldFact<>(MaterialField.TRANSACTION_AMOUNT, "5,000", List.of(sameRun));

        assertThat(EvidenceAssessor.deriveStatus(MaterialField.TRANSACTION_AMOUNT, List.of(first, second)))
                .isEqualTo(EvidenceStatus.CONFLICTING);
    }

    @Test
    void deriveStatus_sharedSectionAttribution_icicShape_agreeingIsInsufficientNotSupported() {
        // The ICICI failure shape this whole model exists to catch: native and OCR are genuinely
        // different Acquisition runs, but both were routed to their section by the identical
        // (possibly wrong) SectionAttribution decision. Agreement here must stay INSUFFICIENT
        // under the default policy -- never SUPPORTED -- until something independent of that
        // section-boundary decision clears it.
        ProvenanceNode.SectionAttribution sameSectionDecision =
                new ProvenanceNode.SectionAttribution(1, TextSource.NATIVE_PDF);
        FieldFact<String> native_ = new FieldFact<>(MaterialField.CREDIT_LIMIT, "1,15,000",
                List.of(new ProvenanceNode.Acquisition(TextSource.NATIVE_PDF), sameSectionDecision));
        FieldFact<String> ocr = new FieldFact<>(MaterialField.CREDIT_LIMIT, "1,15,000",
                List.of(new ProvenanceNode.Acquisition(TextSource.OCR), sameSectionDecision));

        assertThat(EvidenceAssessor.deriveStatus(MaterialField.CREDIT_LIMIT, List.of(native_, ocr)))
                .isEqualTo(EvidenceStatus.INSUFFICIENT);
    }

    @Test
    void deriveStatus_fourFacts_disagreementBuriedInLastPair_isStillCaught() {
        // Guards against a future refactor that scans pairs but stops early: the contradiction is
        // deliberately placed in the very last (i, j) combination the nested loop would visit.
        FieldFact<String> a = new FieldFact<>(MaterialField.ACCOUNT_HOLDER, "Jane Doe",
                List.of(new ProvenanceNode.Acquisition(TextSource.NATIVE_PDF)));
        FieldFact<String> b = new FieldFact<>(MaterialField.ACCOUNT_HOLDER, "Jane Doe",
                List.of(new ProvenanceNode.SectionAttribution(0, TextSource.NATIVE_PDF)));
        FieldFact<String> c = new FieldFact<>(MaterialField.ACCOUNT_HOLDER, "Jane Doe",
                List.of(new ProvenanceNode.SectionAttribution(1, TextSource.NATIVE_PDF)));
        FieldFact<String> d = new FieldFact<>(MaterialField.ACCOUNT_HOLDER, "J4ne D0e",
                List.of(new ProvenanceNode.SectionAttribution(2, TextSource.NATIVE_PDF)));

        assertThat(EvidenceAssessor.deriveStatus(MaterialField.ACCOUNT_HOLDER, List.of(a, b, c, d)))
                .isEqualTo(EvidenceStatus.CONFLICTING);
    }

    @Test
    void deriveStatus_policyIsNeverConsultedForADisagreeingPair() {
        // By construction, not by discipline: a policy that would explode if it were ever asked to
        // remediate a disagreement must never actually be invoked for one.
        ProvenanceNode.Acquisition sameRun = new ProvenanceNode.Acquisition(TextSource.NATIVE_PDF);
        FieldFact<String> first = new FieldFact<>(MaterialField.TRANSACTION_AMOUNT, "55,000", List.of(sameRun));
        FieldFact<String> second = new FieldFact<>(MaterialField.TRANSACTION_AMOUNT, "5,000", List.of(sameRun));

        IndependenceRemediationPolicy explodesIfCalled = (field, x, y) -> {
            throw new AssertionError("remediate() must never be called for a disagreeing pair");
        };

        assertThat(EvidenceAssessor.deriveStatus(
                MaterialField.TRANSACTION_AMOUNT, List.of(first, second), explodesIfCalled))
                .isEqualTo(EvidenceStatus.CONFLICTING);
    }

    @Test
    void deriveStatus_fullyPermissivePolicy_cannotMaskAnUnrelatedDisagreement() {
        // Even a maximally over-permissive policy (clears every shared-failure-mode pairing) must
        // not be able to hide a genuine, independent disagreement elsewhere in the fact set.
        ProvenanceNode.ColumnLayoutInterpretation sharedColumns =
                new ProvenanceNode.ColumnLayoutInterpretation(0, "anchors:100,300");
        FieldFact<String> nonIndependentAgreeingA = new FieldFact<>(MaterialField.TRANSACTION_DIRECTION, "DEBIT",
                List.of(new ProvenanceNode.Acquisition(TextSource.NATIVE_PDF), sharedColumns));
        FieldFact<String> nonIndependentAgreeingB = new FieldFact<>(MaterialField.TRANSACTION_DIRECTION, "DEBIT",
                List.of(new ProvenanceNode.Acquisition(TextSource.OCR), sharedColumns));
        FieldFact<String> independentDisagreeing = new FieldFact<>(MaterialField.TRANSACTION_DIRECTION, "CREDIT",
                List.of(new ProvenanceNode.SectionAttribution(9, TextSource.NATIVE_PDF)));

        IndependenceRemediationPolicy alwaysClears = (field, x, y) -> true;

        assertThat(EvidenceAssessor.deriveStatus(MaterialField.TRANSACTION_DIRECTION,
                List.of(nonIndependentAgreeingA, nonIndependentAgreeingB, independentDisagreeing), alwaysClears))
                .isEqualTo(EvidenceStatus.CONFLICTING);
    }

    @Test
    void deriveStatus_facts_defensiveCopyDoesNotAffectConcurrentCallerMutation() {
        // FieldFact itself defensively copies provenance; deriveStatus must behave correctly even
        // when handed a mutable (non-List.of) facts list, since callers are not required to pass
        // an immutable list.
        List<FieldFact<String>> mutable = new ArrayList<>();
        mutable.add(new FieldFact<>(MaterialField.ACCOUNT_HOLDER, "Jane Doe",
                List.of(new ProvenanceNode.Acquisition(TextSource.NATIVE_PDF))));
        mutable.add(new FieldFact<>(MaterialField.ACCOUNT_HOLDER, "Jane Doe",
                List.of(new ProvenanceNode.Acquisition(TextSource.OCR))));

        assertThat(EvidenceAssessor.deriveStatus(MaterialField.ACCOUNT_HOLDER, mutable))
                .isEqualTo(EvidenceStatus.SUPPORTED);
    }
}
