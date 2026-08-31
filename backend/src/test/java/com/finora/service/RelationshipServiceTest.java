package com.finora.service;

import com.finora.dto.RelationshipDto;
import com.finora.entity.Account;
import com.finora.entity.Relationship;
import com.finora.entity.RelationshipIdentifier;
import com.finora.entity.Transaction;
import com.finora.exception.ApiException;
import com.finora.repository.AccountRepository;
import com.finora.repository.CategoryRepository;
import com.finora.repository.RelationshipIdentifierRepository;
import com.finora.repository.RelationshipRepository;
import com.finora.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Covers RelationshipService's ownership validation for OWN_ACCOUNT relationships and the
 * identifier matching ReconciliationService consumes as a transfer-confidence signal — see
 * docs/rule-engine-relationship-engine-eds.md §2, §3.3 -- plus the Financial Intelligence
 * Workspace's update/merge/transactionsFor additions (Module 4).
 */
class RelationshipServiceTest {

    private RelationshipRepository relationshipRepository;
    private RelationshipIdentifierRepository identifierRepository;
    private AccountRepository accountRepository;
    private TransactionRepository transactionRepository;
    private CategoryRepository categoryRepository;
    private RelationshipService relationshipService;
    private AuditService auditService;
    private final UUID userId = UUID.randomUUID();
    private final UUID otherUserId = UUID.randomUUID();
    private final UUID actingAdminId = UUID.randomUUID();

    // Backing list standing in for "the identifiers table" -- lets merge()'s
    // findByRelationshipId/delete/save calls all see each other's effects within one test, the
    // same in-memory-list pattern MerchantServiceTest already uses for merge()'s own coverage.
    private final List<RelationshipIdentifier> identifierStore = new ArrayList<>();
    private Account liveAccount;

    @BeforeEach
    void setUp() {
        relationshipRepository = mock(RelationshipRepository.class);
        identifierRepository = mock(RelationshipIdentifierRepository.class);
        accountRepository = mock(AccountRepository.class);
        transactionRepository = mock(TransactionRepository.class);
        categoryRepository = mock(CategoryRepository.class);
        auditService = mock(AuditService.class);
        relationshipService = new RelationshipService(relationshipRepository, identifierRepository,
                accountRepository, transactionRepository, categoryRepository, auditService);

        // transactionsFor() (Financial Intelligence Workspace, Module 4) scopes its transaction
        // fetch to the user's live account ids -- see DashboardService.summarize for the deleted-
        // account-leak fix this mirrors. Only the transactionsFor tests below actually exercise
        // this, but the default keeps every other test (which never touches accountRepository)
        // unaffected.
        liveAccount = account(UUID.randomUUID(), userId);
        when(accountRepository.findByUserId(userId)).thenReturn(List.of(liveAccount));

        when(relationshipRepository.save(any(Relationship.class))).thenAnswer(inv -> {
            Relationship r = inv.getArgument(0);
            if (r.getId() == null) ReflectionTestUtils.setField(r, "id", UUID.randomUUID());
            return r;
        });
        when(identifierRepository.save(any(RelationshipIdentifier.class))).thenAnswer(inv -> {
            RelationshipIdentifier i = inv.getArgument(0);
            if (i.getId() == null) ReflectionTestUtils.setField(i, "id", UUID.randomUUID());
            identifierStore.removeIf(existing -> existing.getId().equals(i.getId()));
            identifierStore.add(i);
            return i;
        });
        when(identifierRepository.findByRelationshipId(any())).thenAnswer(inv -> identifierStore.stream()
                .filter(i -> i.getRelationshipId().equals(inv.getArgument(0))).toList());
        doAnswer(inv -> {
            identifierStore.remove((RelationshipIdentifier) inv.getArgument(0));
            return null;
        }).when(identifierRepository).delete(any(RelationshipIdentifier.class));
        doAnswer(inv -> {
            identifierStore.removeIf(i -> i.getRelationshipId().equals(inv.getArgument(0)));
            return null;
        }).when(identifierRepository).deleteByRelationshipId(any());
    }

    private Account account(UUID id, UUID owner) {
        Account a = new Account();
        ReflectionTestUtils.setField(a, "id", id);
        a.setUserId(owner);
        return a;
    }

    private RelationshipDto.CreateRequest ownAccountRequest(UUID linkedAccountId) {
        return new RelationshipDto.CreateRequest("My HDFC Savings", "OWN_ACCOUNT", linkedAccountId,
                List.of(new RelationshipDto.IdentifierRequest("ACCOUNT_LAST4", "4802")));
    }

    @Test
    void create_ownAccount_requiresLinkedAccountId() {
        assertThatThrownBy(() -> relationshipService.create(userId, ownAccountRequest(null), actingAdminId))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("linkedAccountId");
    }

    @Test
    void create_ownAccount_throwsForbidden_whenAccountBelongsToAnotherUser() {
        UUID accountId = UUID.randomUUID();
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account(accountId, otherUserId)));

        assertThatThrownBy(() -> relationshipService.create(userId, ownAccountRequest(accountId), actingAdminId))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("does not belong to you");
    }

    @Test
    void create_ownAccount_succeeds_whenAccountBelongsToCaller_andNormalizesIdentifiers() {
        UUID accountId = UUID.randomUUID();
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account(accountId, userId)));

        var result = relationshipService.create(userId, ownAccountRequest(accountId), actingAdminId);

        assertThat(result.relationshipType()).isEqualTo("OWN_ACCOUNT");
        assertThat(result.linkedAccountId()).isEqualTo(accountId);
        verify(identifierRepository).save(argThat(i -> "4802".equals(i.getIdentifierValue())
                && i.getIdentifierType() == RelationshipIdentifier.Type.ACCOUNT_LAST4));
    }

    @Test
    void create_family_doesNotRequireLinkedAccountId() {
        var req = new RelationshipDto.CreateRequest("Mom", "FAMILY", null,
                List.of(new RelationshipDto.IdentifierRequest("UPI_ID", "mom@okhdfcbank")));

        var result = relationshipService.create(userId, req, actingAdminId);

        assertThat(result.relationshipType()).isEqualTo("FAMILY");
        assertThat(result.linkedAccountId()).isNull();
        verify(accountRepository, never()).findById(any());
    }

    @Test
    void create_recordsActorIdInAuditMetadata() {
        var req = new RelationshipDto.CreateRequest("Mom", "FAMILY", null,
                List.of(new RelationshipDto.IdentifierRequest("UPI_ID", "mom@okhdfcbank")));

        var result = relationshipService.create(userId, req, actingAdminId);

        verify(auditService).record(eq(userId), eq("RELATIONSHIP_CREATED"), eq("Relationship"), eq(result.id()),
                argThat(metadata -> actingAdminId.toString().equals(metadata.get("actorId"))));
    }

    @Test
    void matchesOwnAccountIdentifier_true_whenDescriptionContainsANormalizedIdentifier() {
        UUID relationshipId = UUID.randomUUID();
        Relationship r = new Relationship();
        ReflectionTestUtils.setField(r, "id", relationshipId);
        r.setUserId(userId);
        r.setRelationshipType(Relationship.Type.OWN_ACCOUNT);
        when(relationshipRepository.findByUserIdAndRelationshipType(userId, Relationship.Type.OWN_ACCOUNT))
                .thenReturn(List.of(r));

        RelationshipIdentifier identifier = new RelationshipIdentifier();
        identifier.setRelationshipId(relationshipId);
        identifier.setIdentifierType(RelationshipIdentifier.Type.ACCOUNT_LAST4);
        identifier.setIdentifierValue("4802"); // stored already-normalized, same as create() does
        when(identifierRepository.findByRelationshipId(relationshipId)).thenReturn(List.of(identifier));

        boolean matched = relationshipService.matchesOwnAccountIdentifier(userId, "UPI TRANSFER TO A/C XX4802");

        assertThat(matched).isTrue();
    }

    @Test
    void matchesOwnAccountIdentifier_false_whenNoOwnAccountRelationshipMatches() {
        when(relationshipRepository.findByUserIdAndRelationshipType(userId, Relationship.Type.OWN_ACCOUNT))
                .thenReturn(List.of());

        boolean matched = relationshipService.matchesOwnAccountIdentifier(userId, "SWIGGY ORDER");

        assertThat(matched).isFalse();
    }

    @Test
    void matchesOwnAccountIdentifier_false_forBlankDescription() {
        boolean matched = relationshipService.matchesOwnAccountIdentifier(userId, "");

        assertThat(matched).isFalse();
        verifyNoInteractions(relationshipRepository);
    }

    @Test
    void delete_throwsForbidden_whenRelationshipBelongsToAnotherUser() {
        UUID relationshipId = UUID.randomUUID();
        Relationship r = new Relationship();
        ReflectionTestUtils.setField(r, "id", relationshipId);
        r.setUserId(otherUserId);
        when(relationshipRepository.findById(relationshipId)).thenReturn(Optional.of(r));

        assertThatThrownBy(() -> relationshipService.delete(userId, relationshipId, actingAdminId))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("does not belong to you");
    }

    @Test
    void delete_removesRelationshipAndItsIdentifiers() {
        UUID relationshipId = UUID.randomUUID();
        Relationship r = new Relationship();
        ReflectionTestUtils.setField(r, "id", relationshipId);
        r.setUserId(userId);
        when(relationshipRepository.findById(relationshipId)).thenReturn(Optional.of(r));

        relationshipService.delete(userId, relationshipId, actingAdminId);

        verify(identifierRepository).deleteByRelationshipId(relationshipId);
        verify(relationshipRepository).delete(r);
        verify(auditService).record(eq(userId), eq("RELATIONSHIP_DELETED"), eq("Relationship"), eq(relationshipId),
                argThat(metadata -> actingAdminId.toString().equals(metadata.get("actorId"))));
    }

    private Relationship relationship(UUID id, UUID owner, Relationship.Type type) {
        Relationship r = new Relationship();
        ReflectionTestUtils.setField(r, "id", id);
        r.setUserId(owner);
        r.setRelationshipType(type);
        r.setLabel("Test");
        when(relationshipRepository.findById(id)).thenReturn(Optional.of(r));
        return r;
    }

    // --- update (Financial Intelligence Workspace, Module 4) ---

    @Test
    void update_changesOnlySuppliedFields() {
        UUID id = UUID.randomUUID();
        Relationship r = relationship(id, userId, Relationship.Type.FAMILY);
        r.setLabel("Mom");

        var result = relationshipService.update(userId, id, new RelationshipDto.UpdateRequest("Mother", null, null, null), actingAdminId);

        assertThat(result.label()).isEqualTo("Mother");
        assertThat(result.relationshipType()).isEqualTo("FAMILY"); // untouched
    }

    @Test
    void update_recordsActorIdInAuditMetadata() {
        UUID id = UUID.randomUUID();
        relationship(id, userId, Relationship.Type.FAMILY);

        relationshipService.update(userId, id, new RelationshipDto.UpdateRequest("Mother", null, null, null), actingAdminId);

        verify(auditService).record(eq(userId), eq("RELATIONSHIP_UPDATED"), eq("Relationship"), eq(id),
                argThat(metadata -> actingAdminId.toString().equals(metadata.get("actorId"))));
    }

    @Test
    void update_blankLabel_throws() {
        UUID id = UUID.randomUUID();
        relationship(id, userId, Relationship.Type.FAMILY);

        assertThatThrownBy(() -> relationshipService.update(userId, id, new RelationshipDto.UpdateRequest("   ", null, null, null), actingAdminId))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("blank");
    }

    @Test
    void update_changingTypeToOwnAccount_requiresAndValidatesLinkedAccountId() {
        UUID id = UUID.randomUUID();
        relationship(id, userId, Relationship.Type.FAMILY);

        assertThatThrownBy(() -> relationshipService.update(userId, id,
                new RelationshipDto.UpdateRequest(null, "OWN_ACCOUNT", null, null), actingAdminId))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("linkedAccountId");
    }

    @Test
    void update_changingTypeAwayFromOwnAccount_clearsLinkedAccountId() {
        UUID id = UUID.randomUUID();
        Relationship r = relationship(id, userId, Relationship.Type.OWN_ACCOUNT);
        UUID accountId = UUID.randomUUID();
        r.setLinkedAccountId(accountId);

        var result = relationshipService.update(userId, id, new RelationshipDto.UpdateRequest(null, "FRIEND", null, null), actingAdminId);

        assertThat(result.relationshipType()).isEqualTo("FRIEND");
        assertThat(result.linkedAccountId()).isNull();
    }

    @Test
    void update_withIdentifiers_replacesTheWholeListRatherThanAppending() {
        UUID id = UUID.randomUUID();
        relationship(id, userId, Relationship.Type.FAMILY);
        identifierStore.add(existingIdentifier(id, RelationshipIdentifier.Type.UPI_ID, "old@upi"));

        var result = relationshipService.update(userId, id, new RelationshipDto.UpdateRequest(
                null, null, null, List.of(new RelationshipDto.IdentifierRequest("UPI_ID", "new@upi"))), actingAdminId);

        assertThat(result.identifiers()).hasSize(1);
        // Normalized at write time, same as create() -- "@" doesn't survive CategoryRules.normalize().
        assertThat(result.identifiers().get(0).identifierValue()).isEqualTo("new upi");
    }

    @Test
    void update_withoutIdentifiersField_leavesExistingIdentifiersUntouched() {
        UUID id = UUID.randomUUID();
        relationship(id, userId, Relationship.Type.FAMILY);
        identifierStore.add(existingIdentifier(id, RelationshipIdentifier.Type.UPI_ID, "mom@upi"));

        var result = relationshipService.update(userId, id, new RelationshipDto.UpdateRequest("Mother", null, null, null), actingAdminId);

        assertThat(result.identifiers()).hasSize(1);
        assertThat(result.identifiers().get(0).identifierValue()).isEqualTo("mom upi"); // normalized when originally stored
    }

    // --- merge (Financial Intelligence Workspace, Module 4) ---

    @Test
    void merge_intoItself_throws() {
        UUID id = UUID.randomUUID();
        relationship(id, userId, Relationship.Type.FAMILY);

        assertThatThrownBy(() -> relationshipService.merge(userId, id, id, actingAdminId))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("itself");
    }

    @Test
    void merge_repointsIdentifiers_deletesAbsorbedRelationship() {
        UUID survivingId = UUID.randomUUID();
        UUID absorbedId = UUID.randomUUID();
        relationship(survivingId, userId, Relationship.Type.FAMILY);
        Relationship absorbed = relationship(absorbedId, userId, Relationship.Type.FAMILY);
        identifierStore.add(existingIdentifier(absorbedId, RelationshipIdentifier.Type.UPI_ID, "mom@upi"));

        relationshipService.merge(userId, survivingId, absorbedId, actingAdminId);

        assertThat(identifierStore).hasSize(1);
        assertThat(identifierStore.get(0).getRelationshipId()).isEqualTo(survivingId);
        verify(relationshipRepository).delete(absorbed);
    }

    @Test
    void merge_dropsDuplicateIdentifiers_ratherThanDuplicatingThem() {
        UUID survivingId = UUID.randomUUID();
        UUID absorbedId = UUID.randomUUID();
        relationship(survivingId, userId, Relationship.Type.FAMILY);
        relationship(absorbedId, userId, Relationship.Type.FAMILY);
        identifierStore.add(existingIdentifier(survivingId, RelationshipIdentifier.Type.UPI_ID, "mom@upi"));
        identifierStore.add(existingIdentifier(absorbedId, RelationshipIdentifier.Type.UPI_ID, "mom@upi")); // same value

        relationshipService.merge(userId, survivingId, absorbedId, actingAdminId);

        assertThat(identifierStore).hasSize(1); // not duplicated
    }

    @Test
    void merge_recordsActorIdInAuditMetadata() {
        UUID survivingId = UUID.randomUUID();
        UUID absorbedId = UUID.randomUUID();
        relationship(survivingId, userId, Relationship.Type.FAMILY);
        relationship(absorbedId, userId, Relationship.Type.FAMILY);

        relationshipService.merge(userId, survivingId, absorbedId, actingAdminId);

        verify(auditService).record(eq(userId), eq("RELATIONSHIP_MERGED"), eq("Relationship"), eq(survivingId),
                argThat(metadata -> actingAdminId.toString().equals(metadata.get("actorId"))));
    }

    // --- transactionsFor (Financial Intelligence Workspace, Module 4) ---

    @Test
    void transactionsFor_matchesTransactionsContainingAnyOfThisRelationshipsIdentifiers() {
        UUID id = UUID.randomUUID();
        relationship(id, userId, Relationship.Type.FRIEND);
        identifierStore.add(existingIdentifier(id, RelationshipIdentifier.Type.UPI_ID, "rahul@okhdfc"));
        when(categoryRepository.findByUserId(userId)).thenReturn(List.of());

        Transaction matching = txn("UPI TRANSFER TO rahul@okhdfc", LocalDate.of(2026, 7, 1));
        Transaction notMatching = txn("SWIGGY ORDER", LocalDate.of(2026, 7, 2));
        when(transactionRepository.findByUserIdAndAccountIdIn(eq(userId), any())).thenReturn(List.of(matching, notMatching));

        var result = relationshipService.transactionsFor(userId, id);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).description()).isEqualTo("UPI TRANSFER TO rahul@okhdfc");
    }

    @Test
    void transactionsFor_noIdentifiers_returnsEmptyList_withoutScanningTransactions() {
        UUID id = UUID.randomUUID();
        relationship(id, userId, Relationship.Type.FRIEND);

        var result = relationshipService.transactionsFor(userId, id);

        assertThat(result).isEmpty();
        verifyNoInteractions(transactionRepository);
    }

    // Deleted-account leak (see DashboardService.summarize for the original fix): a deleted
    // account's transactions deliberately keep deleted_at unset, so transactionsFor must scope
    // its fetch to exactly the live account ids, not just userId.
    @Test
    void transactionsFor_scopesTransactionFetch_toExactlyTheLiveAccountIds() {
        UUID id = UUID.randomUUID();
        relationship(id, userId, Relationship.Type.FRIEND);
        identifierStore.add(existingIdentifier(id, RelationshipIdentifier.Type.UPI_ID, "rahul@okhdfc"));
        when(categoryRepository.findByUserId(userId)).thenReturn(List.of());
        when(transactionRepository.findByUserIdAndAccountIdIn(eq(userId), any())).thenReturn(List.of());

        relationshipService.transactionsFor(userId, id);

        verify(transactionRepository).findByUserIdAndAccountIdIn(userId, List.of(liveAccount.getId()));
    }

    @Test
    void transactionsFor_withNoLiveAccounts_shortCircuits_withoutQueryingTransactions() {
        UUID id = UUID.randomUUID();
        relationship(id, userId, Relationship.Type.FRIEND);
        identifierStore.add(existingIdentifier(id, RelationshipIdentifier.Type.UPI_ID, "rahul@okhdfc"));
        when(accountRepository.findByUserId(userId)).thenReturn(List.of());

        var result = relationshipService.transactionsFor(userId, id);

        assertThat(result).isEmpty();
        verifyNoInteractions(transactionRepository);
    }

    private RelationshipIdentifier existingIdentifier(UUID relationshipId, RelationshipIdentifier.Type type, String value) {
        RelationshipIdentifier i = new RelationshipIdentifier();
        ReflectionTestUtils.setField(i, "id", UUID.randomUUID());
        i.setRelationshipId(relationshipId);
        i.setIdentifierType(type);
        // Normalized here the same way create()/update() normalize at write time -- e.g. strips
        // "@" so a UPI id like "rahul@okhdfc" is actually matchable against a normalized
        // transaction description, which also has its "@" stripped to a space.
        i.setIdentifierValue(com.finora.util.CategoryRules.normalize(value));
        return i;
    }

    private Transaction txn(String description, LocalDate date) {
        Transaction t = new Transaction();
        ReflectionTestUtils.setField(t, "id", UUID.randomUUID());
        t.setUserId(userId);
        t.setDescription(description);
        t.setTxnDate(date);
        t.setTxnType(Transaction.Type.EXPENSE);
        return t;
    }
}
