package com.finora.goals;

import com.finora.entity.User;
import com.finora.repository.UserRepository;
import com.finora.security.OwnershipGuard;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

@Service
public class GoalService {

    private final GoalRepository goalRepository;
    private final GoalContributionRepository contributionRepository;
    private final UserRepository userRepository;

    public GoalService(GoalRepository goalRepository, GoalContributionRepository contributionRepository,
                        UserRepository userRepository) {
        this.goalRepository = goalRepository;
        this.contributionRepository = contributionRepository;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public List<GoalDto> listForUser(UUID userId) {
        return goalRepository.findByUserId(userId).stream()
                .map(g -> new GoalDto(g.getId(), g.getName(), g.getTargetAmount(), g.getCurrentAmount(), g.getTargetDate()))
                .toList();
    }

    /**
     * Bug fix: this had no @Transactional -- Goal creation and its initial GoalContribution row
     * (when the goal starts with a non-zero currentAmount) are two separate writes that should
     * either both happen or neither. Same bug class, same fix, as BudgetService.upsert()'s own
     * documented rationale: a failure partway through (e.g. the contribution save failing after
     * the goal save already committed) would leave the goal's currentAmount reflecting a
     * contribution with no GoalContribution row behind it -- silently breaking the contribution
     * history/audit trail a user would otherwise rely on to explain that balance.
     */
    @Transactional
    public GoalDto create(UUID userId, GoalDto.CreateRequest req) {
        Goal g = new Goal();
        g.setUserId(userId);
        g.setName(req.name());
        g.setTargetAmount(req.targetAmount());
        g.setCurrentAmount(req.currentAmount() != null ? req.currentAmount() : BigDecimal.ZERO);
        g.setTargetDate(req.targetDate());
        Goal saved = goalRepository.save(g);

        if (g.getCurrentAmount().compareTo(BigDecimal.ZERO) > 0) {
            GoalContribution gc = new GoalContribution();
            gc.setGoalId(saved.getId());
            gc.setAmount(saved.getCurrentAmount());
            // Bug fix: previously relied on GoalContribution.contributedAt's bare LocalDate.now()
            // field default, which resolves against the server's JVM timezone, not this user's --
            // same class of bug as NetWorthService.saveSnapshotForToday() had, fixed the same way.
            gc.setContributedAt(LocalDate.now(safeZoneId(userId)));
            contributionRepository.save(gc);
        }
        return new GoalDto(saved.getId(), saved.getName(), saved.getTargetAmount(), saved.getCurrentAmount(), saved.getTargetDate());
    }

    /** Bug fix: same atomicity gap as {@link #create}, on the same two writes (Goal +
     *  GoalContribution) -- see that method's own doc comment. */
    @Transactional
    public GoalDto addContribution(UUID userId, UUID goalId, BigDecimal amount) {
        Goal g = getOwned(userId, goalId);
        // Floor at zero as defense-in-depth: GoalDto.ContributionRequest's @DecimalMin(0.01)
        // already rejects a non-positive amount at the API boundary, but this keeps the
        // invariant true even if that validation is ever bypassed or this method is called
        // directly from elsewhere in the backend.
        BigDecimal newAmount = g.getCurrentAmount().add(amount);
        g.setCurrentAmount(newAmount.compareTo(BigDecimal.ZERO) >= 0 ? newAmount : BigDecimal.ZERO);
        Goal saved = goalRepository.save(g);

        GoalContribution gc = new GoalContribution();
        gc.setGoalId(goalId);
        gc.setAmount(amount);
        gc.setContributedAt(LocalDate.now(safeZoneId(userId)));
        contributionRepository.save(gc);

        return new GoalDto(saved.getId(), saved.getName(), saved.getTargetAmount(), saved.getCurrentAmount(), saved.getTargetDate());
    }

    public void delete(UUID userId, UUID goalId) {
        goalRepository.delete(getOwned(userId, goalId));
    }

    private Goal getOwned(UUID userId, UUID goalId) {
        return OwnershipGuard.requireOwned(goalRepository.findById(goalId), Goal::getUserId, userId, "Goal");
    }

    /** Same defensive-fallback contract as DashboardService.safeZoneId() / NetWorthService's own
     *  copy -- timezone has no format validation on the settings-update path, so this falls back
     *  to the column's own default (V11 migration) rather than an uncaught DateTimeException. */
    private ZoneId safeZoneId(UUID userId) {
        String timezone = userRepository.findById(userId).map(User::getTimezone).orElse(null);
        if (timezone == null) return ZoneId.of("Asia/Kolkata");
        try {
            return ZoneId.of(timezone);
        } catch (Exception e) {
            return ZoneId.of("Asia/Kolkata");
        }
    }
}
