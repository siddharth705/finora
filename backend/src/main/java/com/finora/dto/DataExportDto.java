package com.finora.dto;

import com.finora.entity.NetWorthSnapshot;
import com.finora.entity.Merchant;
import com.finora.integrations.google.GmailConnection;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * "Download My Data" (Phase C of the account-lifecycle work). One container class holding nested
 * records, same convention as {@code ImportDto}/{@code StatementImportDto}.
 */
public final class DataExportDto {
    private DataExportDto() {}

    /**
     * Full-fidelity net worth snapshot -- {@code NetWorthDto.SnapshotPoint} drops id/totalAssets/
     * totalLiabilities and keeps only date+netWorth, which is the right shape for the Net Worth
     * chart but not for an export that owes the user everything stored about them.
     */
    public record NetWorthSnapshotExportDto(
            UUID id, LocalDate snapshotDate, BigDecimal totalAssets, BigDecimal totalLiabilities, BigDecimal netWorth
    ) {
        public static NetWorthSnapshotExportDto from(NetWorthSnapshot s) {
            return new NetWorthSnapshotExportDto(s.getId(), s.getSnapshotDate(), s.getTotalAssets(),
                    s.getTotalLiabilities(), s.getNetWorth());
        }
    }

    /**
     * Identity only -- deliberately excludes {@code topCategory}/{@code topCategoryConfidence}/
     * {@code distribution}, which {@code MerchantDto} derives from {@code
     * MerchantCategoryLearningRepository}, one of the tables this export's locked-in scope
     * explicitly excludes (derived categorization intelligence, not data the user provided).
     */
    public record MerchantExportDto(UUID id, String canonicalName, String logoUrl, String website, String lifecycleStatus) {
        public static MerchantExportDto from(Merchant m) {
            return new MerchantExportDto(m.getId(), m.getCanonicalName(), m.getLogoUrl(), m.getWebsite(),
                    m.getLifecycleStatus().name());
        }
    }

    /**
     * One {@code GmailConnection} row, any status -- live or historical. Carries the same "no
     * credential material" guarantee {@code GmailConnectionStatusDto}'s own doc states, but
     * without the {@code transactionsFound}/{@code needsReview} cross-table stats that only make
     * sense for a single live connection (and would need an extra query per row here).
     */
    public record GmailConnectionExportDto(
            String status, String googleEmail, List<String> grantedScopes,
            Instant connectedAt, Instant lastSyncedAt, Instant lastDiscoveryAt, Instant createdAt
    ) {
        public static GmailConnectionExportDto from(GmailConnection c) {
            List<String> scopes = c.getGrantedScopes() == null || c.getGrantedScopes().isBlank()
                    ? List.of()
                    : List.of(c.getGrantedScopes().split(" "));
            return new GmailConnectionExportDto(c.getStatus().name(), c.getGoogleEmail(), scopes,
                    c.getConnectedAt(), c.getLastSyncedAt(), c.getLastDiscoveryAt(), c.getCreatedAt());
        }
    }

    /**
     * accounts.json entries -- pairs {@code AccountDto} with the deleted marker that reading via
     * {@code AccountRepository.findByUserIdIncludingDeleted} (to mirror the purge's own scope,
     * which includes soft-deleted accounts) requires: {@code AccountDto.status()} is hardcoded
     * {@code "ACTIVE"} by {@code AccountService.listForUser}'s own design (see that method's doc
     * comment), which would misrepresent a soft-deleted account here.
     */
    public record AccountExportEntry(com.finora.accounts.AccountDto account, boolean deleted, Instant deletedAt) {}

    public record Manifest(
            Instant generatedAt, UUID userId, String email,
            List<ManifestEntry> included, List<ManifestEntry> excluded
    ) {}

    public record ManifestEntry(String name, String description, Integer rowCount) {}
}
