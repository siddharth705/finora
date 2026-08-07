package com.finora.imports.analysis;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ImportVerificationFindingRepository extends JpaRepository<ImportVerificationFinding, UUID> {

    /** Every finding for one synchronous upload, section by section and rule by rule. */
    List<ImportVerificationFinding> findByAnalysisSessionIdOrderBySectionIndexAscRuleAsc(UUID analysisSessionId);

    /** The same, for an upload that went through the asynchronous worker and has no analysis row. */
    List<ImportVerificationFinding> findByImportJobIdOrderBySectionIndexAscRuleAsc(UUID importJobId);
}
