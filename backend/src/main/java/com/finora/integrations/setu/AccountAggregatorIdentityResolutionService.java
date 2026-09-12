package com.finora.integrations.setu;

import com.finora.accounts.AccountDto;
import com.finora.accounts.AccountService;
import com.finora.entity.Account;
import com.finora.entity.FeatureEntitlement;
import com.finora.exception.ApiException;
import com.finora.imports.product.FinancialProductType;
import com.finora.imports.product.ProductIdentity;
import com.finora.imports.product.ProductIdentityResolver;
import com.finora.repository.AccountRepository;
import com.finora.security.OwnershipGuard;
import com.finora.service.EntitlementService;
import com.finora.util.BankRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Decides which Account (if any) a newly-approved AccountAggregatorLink belongs to. Reuses
 * ProductIdentityResolver -- the same NEW/MATCHED/PROBABLE model already governing manual
 * re-import -- rather than a separate, less-safe matching system. See the design spec's "Account
 * identity resolution" section for why silent attachment on anything less than an exact match was
 * rejected.
 */
@Service
public class AccountAggregatorIdentityResolutionService {

    private static final Logger log = LoggerFactory.getLogger(AccountAggregatorIdentityResolutionService.class);

    private final SetuConsentGateway gateway;
    private final AccountRepository accountRepository;
    private final AccountService accountService;
    private final ProductIdentityResolver productIdentityResolver;
    private final AccountAggregatorLinkRepository links;
    private final EntitlementService entitlementService;

    public AccountAggregatorIdentityResolutionService(SetuConsentGateway gateway, AccountRepository accountRepository,
                                                        AccountService accountService,
                                                        ProductIdentityResolver productIdentityResolver,
                                                        AccountAggregatorLinkRepository links,
                                                        EntitlementService entitlementService) {
        this.gateway = gateway;
        this.accountRepository = accountRepository;
        this.accountService = accountService;
        this.productIdentityResolver = productIdentityResolver;
        this.links = links;
        this.entitlementService = entitlementService;
    }

    /**
     * Triggered by the {@code consent.approved} webhook. Bug fix (found during post-implementation
     * review, not part of the original plan): two real gaps closed here.
     *
     * <p><b>Re-entrancy.</b> Setu (like most webhook senders) can redeliver the same logical event
     * under a different delivery id, which {@code WebhookEventService}'s idempotency ledger cannot
     * catch (it dedupes by event id, not by business meaning). Without the status check below, a
     * redelivered {@code consent.approved} would call {@link SetuConsentGateway#fetchConsentDetail}
     * again (a second billable Setu call for no reason) and could create a SECOND new Account for
     * what should resolve to NEW exactly once.
     *
     * <p><b>Entitlement can lapse mid-flight.</b> A user can downgrade from Premium in the window
     * between requesting a link and Setu's webhook arriving. Without the check below, the account
     * would still get silently attached and locked into {@code ACCOUNT_AGGREGATOR} with no active
     * entitlement and no sync ever running to justify blocking manual import -- a real dead end for
     * that account until support intervenes. Downgraded here means the same as a downgrade after a
     * link was already ACTIVE: PAUSED, no account touched.
     */
    public void resolveAndAttach(AccountAggregatorLink link) {
        if (link.getStatus() != AccountAggregatorLinkStatus.CONSENT_PENDING) {
            log.info("Ignoring consent.approved for link {} already in status {} (redelivered webhook).",
                    link.getId(), link.getStatus());
            return;
        }
        if (!entitlementService.hasEntitlement(link.getUserId(), FeatureEntitlement.ACCOUNT_AGGREGATOR_SYNC)) {
            log.info("Link {} approved but user is no longer entitled to ACCOUNT_AGGREGATOR_SYNC; pausing.",
                    link.getId());
            link.setStatus(AccountAggregatorLinkStatus.PAUSED);
            links.save(link);
            return;
        }

        SetuConsentDetail detail = gateway.fetchConsentDetail(link.getConsentHandleId());
        String bankId = detectBankId(detail);
        ProductIdentityResolver.ProductMatch match = resolve(link, detail, bankId);

        switch (match.resolution()) {
            case MATCHED -> attach(link, match.account());
            case NEW -> attach(link, createAccount(link, detail, bankId));
            case PROBABLE -> {
                // Never auto-attached -- see this class's own doc comment. The candidate(s) stay
                // available via match.candidates() for the confirmation endpoints to offer; this
                // method's job ends at surfacing that a decision is needed.
                link.setStatus(AccountAggregatorLinkStatus.PENDING_ACCOUNT_CONFIRMATION);
                links.save(link);
            }
        }
    }

    /** Shared by the MATCHED and NEW branches above, and by the confirm-existing-account path (a
     *  user-confirmed PROBABLE match is handled identically to an automatic MATCHED one once the
     *  account is settled). */
    void attach(AccountAggregatorLink link, Account account) {
        account.setPrimarySource(Account.PrimarySource.ACCOUNT_AGGREGATOR);
        accountRepository.save(account);
        link.setAccountId(account.getId());
        link.setStatus(AccountAggregatorLinkStatus.ACTIVE);
        links.save(link);
    }

    private Account createAccount(AccountAggregatorLink link, SetuConsentDetail detail, String bankId) {
        String accountType = link.getFiType() == FiType.CREDIT_CARD ? "CREDIT_CARD" : "SAVINGS";
        AccountDto created = accountService.create(link.getUserId(), new AccountDto.CreateRequest(
                bankNameOr(bankId, "Bank"), accountType, java.math.BigDecimal.ZERO, null, null,
                null, detail.accountHolderName(), detail.maskedAccountNumber(), bankId,
                null, detail.ifscCode(),
                null, null, null, null, null, null, null), link.getUserId());
        return accountRepository.findById(created.id())
                .orElseThrow(() -> new IllegalStateException("Just-created account not found: " + created.id()));
    }

    /** Controller-facing: the user, shown a PROBABLE match, picked one of the offered candidates.
     *  Loads and ownership-checks both the link and the chosen account, re-checks entitlement and
     *  the link's own status (refusing a link that isn't actually awaiting confirmation -- the same
     *  re-entrancy concern {@link #resolveAndAttach} guards against, here for a double-submitted
     *  confirm request), then delegates to {@link #attach}. */
    public void confirmExistingAccount(UUID userId, UUID linkId, UUID accountId) {
        AccountAggregatorLink link = requireConfirmable(userId, linkId);
        Account account = OwnershipGuard.requireOwned(
                accountRepository.findById(accountId), Account::getUserId, userId, "Account");
        attach(link, account);
    }

    /** Controller-facing: the user, shown a PROBABLE match, said "no, this is a different/new
     *  account." Re-fetches consent detail rather than requiring the caller to supply it (there is
     *  nowhere for a client to have gotten it from) -- the one extra Setu call this costs only
     *  happens on this less-common path, not on every link. */
    public void confirmNewAccount(UUID userId, UUID linkId) {
        AccountAggregatorLink link = requireConfirmable(userId, linkId);
        SetuConsentDetail detail = gateway.fetchConsentDetail(link.getConsentHandleId());
        String bankId = detectBankId(detail);
        attach(link, createAccount(link, detail, bankId));
    }

    private AccountAggregatorLink requireConfirmable(UUID userId, UUID linkId) {
        AccountAggregatorLink link = OwnershipGuard.requireOwned(
                links.findById(linkId), AccountAggregatorLink::getUserId, userId, "AccountAggregatorLink");
        if (link.getStatus() != AccountAggregatorLinkStatus.PENDING_ACCOUNT_CONFIRMATION) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "This link isn't waiting for an account confirmation.");
        }
        if (!entitlementService.hasEntitlement(userId, FeatureEntitlement.ACCOUNT_AGGREGATOR_SYNC)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Account Aggregator sync is a Premium feature.");
        }
        return link;
    }

    private ProductIdentityResolver.ProductMatch resolve(AccountAggregatorLink link, SetuConsentDetail detail,
                                                           String bankId) {
        FinancialProductType type = link.getFiType() == FiType.CREDIT_CARD
                ? FinancialProductType.CREDIT_CARD : FinancialProductType.SAVINGS;

        ProductIdentity discovered = (detail.fullAccountNumber() != null
                ? ProductIdentity.of(bankId, type, detail.fullAccountNumber(), detail.maskedAccountNumber())
                : ProductIdentity.stored(bankId, type, null, detail.maskedAccountNumber()))
                .withWeakSignals(detail.ifscCode(), detail.accountHolderName());

        return productIdentityResolver.resolve(link.getUserId(), discovered);
    }

    /** IFSC is passed as a labelled hint, reusing BankRegistry's own "Signal 1: the account's own,
     *  labelled IFSC" detection path (see BankRegistry.detect) rather than adding a second,
     *  Setu-specific bank-id mapping table. */
    private static String detectBankId(SetuConsentDetail detail) {
        return BankRegistry.detect("account-aggregator", List.of("IFSC " + detail.ifscCode())).id();
    }

    private static String bankNameOr(String bankId, String fallback) {
        BankRegistry.BankInfo info = BankRegistry.get(bankId);
        return info != null ? info.shortName() : fallback;
    }
}
