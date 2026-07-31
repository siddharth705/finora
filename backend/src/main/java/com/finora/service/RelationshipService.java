package com.finora.service;

import com.finora.dto.RelationshipDto;
import com.finora.transactions.TransactionDto;
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
import com.finora.util.CategoryRules;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * CRUD for relationships (family/friend/own-account tags) + the identifier matching
 * ReconciliationService consumes as a transfer-confidence signal. See
 * docs/rule-engine-relationship-engine-eds.md §2, §3.3, §4.
 *
 * Financial Intelligence Workspace, Module 4 additions (see docs/team-message-financial-
 * intelligence-workspace-kickoff.md): update(), merge(), transactionsFor() -- the three gaps
 * flagged during the Workspace gap analysis (no edit endpoint, no merge, no related-transactions
 * lookup). TransactionRepository/CategoryRepository are new dependencies here specifically for
 * transactionsFor(); nothing else added needs them.
 */
@Service
public class RelationshipService {

    private final RelationshipRepository relationshipRepository;
    private final RelationshipIdentifierRepository identifierRepository;
    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final CategoryRepository categoryRepository;
    private final AuditService auditService;

    public RelationshipService(RelationshipRepository relationshipRepository,
                                RelationshipIdentifierRepository identifierRepository,
                                AccountRepository accountRepository,
                                TransactionRepository transactionRepository,
                                CategoryRepository categoryRepository,
                                AuditService auditService) {
        this.relationshipRepository = relationshipRepository;
        this.identifierRepository = identifierRepository;
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.categoryRepository = categoryRepository;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<RelationshipDto> listForUser(UUID userId) {
        return relationshipRepository.findByUserId(userId).stream().map(this::toDto).toList();
    }

    @Transactional
    public RelationshipDto create(UUID userId, RelationshipDto.CreateRequest req) {
        Relationship.Type type = parseType(req.relationshipType());
        UUID linkedAccountId = type == Relationship.Type.OWN_ACCOUNT
                ? requireOwnedAccount(userId, req.linkedAccountId()) : null;

        Relationship relationship = new Relationship();
        relationship.setUserId(userId);
        relationship.setLabel(req.label());
        relationship.setRelationshipType(type);
        relationship.setLinkedAccountId(linkedAccountId);
        Relationship saved = relationshipRepository.save(relationship);

        saveIdentifiers(saved.getId(), req.identifiers());

        auditService.record(userId, "RELATIONSHIP_CREATED", "Relationship", saved.getId(),
                Map.of("label", saved.getLabel(), "type", type.name()));
        return toDto(saved);
    }

    /** Financial Intelligence Workspace, Module 4 -- every field optional, see
     *  RelationshipDto.UpdateRequest's own doc comment for the identifiers-replace-not-append
     *  contract. */
    @Transactional
    public RelationshipDto update(UUID userId, UUID relationshipId, RelationshipDto.UpdateRequest req) {
        Relationship relationship = getOwned(userId, relationshipId);

        if (req.label() != null) {
            if (req.label().isBlank()) throw new ApiException(HttpStatus.BAD_REQUEST, "Label can't be blank.");
            relationship.setLabel(req.label());
        }

        Relationship.Type effectiveType = req.relationshipType() != null
                ? parseType(req.relationshipType()) : relationship.getRelationshipType();
        if (req.relationshipType() != null) relationship.setRelationshipType(effectiveType);

        if (effectiveType == Relationship.Type.OWN_ACCOUNT) {
            UUID requestedAccountId = req.linkedAccountId() != null ? req.linkedAccountId() : relationship.getLinkedAccountId();
            relationship.setLinkedAccountId(requireOwnedAccount(userId, requestedAccountId));
        } else if (req.relationshipType() != null) {
            // Type changed away from OWN_ACCOUNT -- linkedAccountId is only ever meaningful for
            // that type (see Relationship.linkedAccountId's own doc comment), so it's cleared
            // rather than left dangling with no relationshipType to justify it.
            relationship.setLinkedAccountId(null);
        }

        relationshipRepository.save(relationship);

        if (req.identifiers() != null) {
            identifierRepository.deleteByRelationshipId(relationshipId);
            saveIdentifiers(relationshipId, req.identifiers());
        }

        auditService.record(userId, "RELATIONSHIP_UPDATED", "Relationship", relationshipId,
                Map.of("label", relationship.getLabel(), "type", relationship.getRelationshipType().name()));
        return toDto(relationship);
    }

    /**
     * Financial Intelligence Workspace, Module 4 -- same spirit as MerchantService.merge() (repoint
     * onto the survivor, delete the absorbed row, single @Transactional boundary) but simpler:
     * relationships carry no learning distribution to sum, just identifiers to repoint. Identical
     * (type, normalized value) pairs already present on the survivor are dropped rather than
     * duplicated -- relationship_identifiers has no unique constraint at the DB level (V18), so
     * nothing stops a duplicate row from being written, but a match check gains nothing from
     * checking the same identifier twice.
     */
    @Transactional
    public RelationshipDto merge(UUID userId, UUID survivingId, UUID mergeFromId) {
        if (survivingId.equals(mergeFromId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Can't merge a relationship into itself.");
        }
        Relationship surviving = getOwned(userId, survivingId);
        Relationship absorbed = getOwned(userId, mergeFromId);

        Set<String> existingKeys = new HashSet<>();
        for (RelationshipIdentifier i : identifierRepository.findByRelationshipId(surviving.getId())) {
            existingKeys.add(i.getIdentifierType() + "|" + i.getIdentifierValue());
        }

        for (RelationshipIdentifier identifier : identifierRepository.findByRelationshipId(absorbed.getId())) {
            String key = identifier.getIdentifierType() + "|" + identifier.getIdentifierValue();
            if (existingKeys.contains(key)) {
                identifierRepository.delete(identifier); // already covered by an identical identifier on the survivor
            } else {
                identifier.setRelationshipId(surviving.getId());
                identifierRepository.save(identifier);
                existingKeys.add(key);
            }
        }

        relationshipRepository.delete(absorbed);

        auditService.record(userId, "RELATIONSHIP_MERGED", "Relationship", surviving.getId(),
                Map.of("survivingRelationshipId", surviving.getId(), "mergeFromRelationshipId", absorbed.getId(),
                        "mergeFromLabel", absorbed.getLabel()));
        return toDto(surviving);
    }

    /** Financial Intelligence Workspace, Module 4 -- every transaction whose (normalized)
     *  description contains one of this relationship's identifiers, regardless of relationship
     *  type (not OWN_ACCOUNT-only -- a FAMILY/FRIEND relationship's identifiers are exactly as
     *  matchable, this just isn't consumed by ReconciliationService's transfer-confidence signal
     *  the way OWN_ACCOUNT's is). Scans the user's whole transaction set, same "fine at
     *  personal-finance volumes" tradeoff RecurringService/matchesOwnAccountIdentifier already
     *  accept -- see those classes' own doc comments. */
    @Transactional(readOnly = true)
    public List<TransactionDto> transactionsFor(UUID userId, UUID relationshipId) {
        getOwned(userId, relationshipId);
        List<String> identifierValues = identifierRepository.findByRelationshipId(relationshipId).stream()
                .map(RelationshipIdentifier::getIdentifierValue)
                .filter(v -> v != null && !v.isBlank())
                .toList();
        if (identifierValues.isEmpty()) return List.of();

        Map<UUID, String> categoryNames = new HashMap<>();
        categoryRepository.findByUserId(userId).forEach(c -> categoryNames.put(c.getId(), c.getName()));

        return transactionRepository.findByUserId(userId).stream()
                .filter(t -> t.getDescription() != null && !t.getDescription().isBlank())
                .filter(t -> {
                    String normalized = CategoryRules.normalize(t.getDescription());
                    return identifierValues.stream().anyMatch(normalized::contains);
                })
                .sorted(Comparator.comparing(Transaction::getTxnDate).reversed())
                .map(t -> TransactionDto.from(t, categoryNames.getOrDefault(t.getCategoryId(), "Uncategorized")))
                .toList();
    }

    @Transactional
    public void delete(UUID userId, UUID relationshipId) {
        Relationship relationship = getOwned(userId, relationshipId);
        identifierRepository.deleteByRelationshipId(relationshipId);
        relationshipRepository.delete(relationship);
        auditService.record(userId, "RELATIONSHIP_DELETED", "Relationship", relationshipId);
    }

    private UUID requireOwnedAccount(UUID userId, UUID accountId) {
        if (accountId == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "An OWN_ACCOUNT relationship needs linkedAccountId.");
        }
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Account not found"));
        if (!account.getUserId().equals(userId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "This account does not belong to you");
        }
        return accountId;
    }

    private void saveIdentifiers(UUID relationshipId, List<RelationshipDto.IdentifierRequest> requests) {
        for (var idReq : requests) {
            RelationshipIdentifier identifier = new RelationshipIdentifier();
            identifier.setRelationshipId(relationshipId);
            identifier.setIdentifierType(parseIdentifierType(idReq.identifierType()));
            // Normalized at write time (not just at match time) so RelationshipService's own
            // match check is a plain contains() against already-normalized text -- same
            // normalize-once convention as MerchantAlias.normalizedAlias.
            identifier.setIdentifierValue(CategoryRules.normalize(idReq.identifierValue()));
            identifierRepository.save(identifier);
        }
    }

    /**
     * All of this user's OWN_ACCOUNT relationship identifiers (already normalized, blanks
     * filtered out), fetched once. ReconciliationService.reconcileForUser() calls this ONCE per
     * run and reuses the result across every candidate transaction, rather than each transaction
     * re-querying relationshipRepository/identifierRepository for what is, within a single
     * reconciliation pass, always the same answer for the same user -- that repeated-query
     * pattern was the original (fixed) shape of this method, an easy-to-miss N+1 given
     * reconcileForUser() runs after every import/create/edit/delete.
     */
    @Transactional(readOnly = true)
    public List<String> ownAccountIdentifierValues(UUID userId) {
        List<Relationship> ownAccounts = relationshipRepository.findByUserIdAndRelationshipType(userId, Relationship.Type.OWN_ACCOUNT);
        List<String> values = new java.util.ArrayList<>();
        for (Relationship r : ownAccounts) {
            for (RelationshipIdentifier identifier : identifierRepository.findByRelationshipId(r.getId())) {
                String value = identifier.getIdentifierValue();
                if (value != null && !value.isBlank()) values.add(value);
            }
        }
        return values;
    }

    /** True if `description` contains any of this user's OWN_ACCOUNT relationship identifiers.
     *  Convenience wrapper over {@link #ownAccountIdentifierValues} for one-off checks; callers
     *  evaluating many descriptions for the same user (e.g. ReconciliationService) should call
     *  ownAccountIdentifierValues() once instead of this per description -- see its doc comment. */
    @Transactional(readOnly = true)
    public boolean matchesOwnAccountIdentifier(UUID userId, String description) {
        if (description == null || description.isBlank()) return false;
        String normalized = CategoryRules.normalize(description);
        for (String value : ownAccountIdentifierValues(userId)) {
            if (normalized.contains(value)) return true;
        }
        return false;
    }

    private Relationship getOwned(UUID userId, UUID relationshipId) {
        Relationship r = relationshipRepository.findById(relationshipId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Relationship not found"));
        if (!userId.equals(r.getUserId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "This relationship does not belong to you");
        }
        return r;
    }

    // Both parse methods below guard against null explicitly rather than relying solely on the
    // @NotNull/@Valid annotations at the controller boundary to keep it out -- v.toUpperCase()
    // on a null v throws NullPointerException, which is NOT an IllegalArgumentException, so the
    // catch block below would not have caught it and a validation gap upstream would have
    // surfaced as an unhelpful 500 instead of a clean 400.
    private Relationship.Type parseType(String v) {
        if (v == null) throw new ApiException(HttpStatus.BAD_REQUEST, "Relationship type is required.");
        try { return Relationship.Type.valueOf(v.toUpperCase()); }
        catch (IllegalArgumentException e) { throw new ApiException(HttpStatus.BAD_REQUEST, "Unknown relationship type: " + v); }
    }

    private RelationshipIdentifier.Type parseIdentifierType(String v) {
        if (v == null) throw new ApiException(HttpStatus.BAD_REQUEST, "Identifier type is required.");
        try { return RelationshipIdentifier.Type.valueOf(v.toUpperCase()); }
        catch (IllegalArgumentException e) { throw new ApiException(HttpStatus.BAD_REQUEST, "Unknown identifier type: " + v); }
    }

    private RelationshipDto toDto(Relationship r) {
        List<RelationshipDto.IdentifierDto> identifiers = identifierRepository.findByRelationshipId(r.getId()).stream()
                .map(i -> new RelationshipDto.IdentifierDto(i.getId(), i.getIdentifierType().name(), i.getIdentifierValue()))
                .toList();
        return new RelationshipDto(r.getId(), r.getLabel(), r.getRelationshipType().name(), r.getLinkedAccountId(), identifiers);
    }
}
