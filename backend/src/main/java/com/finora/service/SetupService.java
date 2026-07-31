package com.finora.service;

import com.finora.dto.AuthDtos.RegisterRequest;
import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

/**
 * First-run platform setup (V33__bootstrap_admin.sql). Deliberately its own service rather than
 * folded into AuthService or AdminUserService: this is a one-time lifecycle event with its own
 * gate (SYSTEM_INITIALIZE, held by exactly one account, ever), not a variant of "an admin creates
 * a user" -- see completeSetup()'s own doc comment for why it still reuses
 * AuthService.adminCreateUser() for the actual row creation rather than duplicating that logic.
 *
 * BootstrapService creates the BOOTSTRAP_ADMIN account this service's caller must already be
 * authenticated as (enforced by SetupController's @PreAuthorize, not by this class -- this class
 * trusts its caller the same way every other *Service class in this codebase does).
 */
@Service
public class SetupService {

    private static final Logger log = LoggerFactory.getLogger(SetupService.class);

    private final UserRepository userRepository;
    private final AuthService authService;
    private final RoleService roleService;
    private final PlatformSettingsService platformSettingsService;
    private final AuditService auditService;
    private final SetupKeyFileWriter setupKeyFileWriter;

    // Same property BootstrapService reads -- duplicated here rather than shared, since this is
    // just a one-line config lookup and the two classes have no other reason to depend on each
    // other.
    @Value("${app.setup.key}")
    private String configuredSetupKey;

    public SetupService(UserRepository userRepository, AuthService authService, RoleService roleService,
                         PlatformSettingsService platformSettingsService, AuditService auditService,
                         SetupKeyFileWriter setupKeyFileWriter) {
        this.userRepository = userRepository;
        this.authService = authService;
        this.roleService = roleService;
        this.platformSettingsService = platformSettingsService;
        this.auditService = auditService;
        this.setupKeyFileWriter = setupKeyFileWriter;
    }

    public boolean isSetupRequired() {
        return !platformSettingsService.getEntity().isSetupCompleted();
    }

    /**
     * Lets SetupController distinguish "the key just doesn't match" from "there's no key to
     * enter at all" -- a much friendlier signal for the UI than a generic wrong-key error.
     * Deliberately not "does the file exist" alone: when an operator supplied FINORA_SETUP_KEY,
     * no file is ever written, and that must never be reported as unavailable.
     */
    public boolean isInstallationKeyAvailable() {
        if (!isSetupRequired()) {
            return true; // moot -- nothing to enter once setup is done
        }
        boolean operatorSuppliedKey = configuredSetupKey != null && !configuredSetupKey.isBlank();
        return operatorSuppliedKey || setupKeyFileWriter.exists();
    }

    /**
     * Everything below runs in one transaction (Gap 3 from the design discussion): if any step
     * fails -- the new admin's email collides, the SUPER_ADMIN role is somehow missing, anything
     * -- the whole thing rolls back and the bootstrap account is exactly as usable as it was
     * before this call. There is no partial state where the bootstrap account is already locked
     * but no real admin exists to replace it.
     *
     * Reuses AuthService.adminCreateUser() -- the same uniqueness checks (email, phone) and
     * default-category seeding every other admin-created account gets -- rather than
     * re-implementing user creation here. The only things specific to *this* admin are applied
     * after: the SUPER_ADMIN role (both the legacy User.role column and an explicit user_roles
     * row via RoleService.assignRole, matching how V16's own migration backfills both for
     * existing users) and the audit trail tying the new admin back to the bootstrap account that
     * created it.
     */
    @Transactional
    public void completeSetup(UUID bootstrapUserId, RegisterRequest request) {
        if (!isSetupRequired()) {
            // Defense in depth, not the primary gate -- SetupController's @PreAuthorize already
            // means only the bootstrap account can ever reach this, and once this method runs
            // once, that account is suspended below and setup_completed flips true, so it can't
            // reach here a second time either. Matches Gap 7's concern: this must never silently
            // re-run against an already-initialized platform.
            throw new ApiException(HttpStatus.CONFLICT, "Setup has already been completed.");
        }

        User newAdmin = authService.adminCreateUser(request, bootstrapUserId);
        newAdmin.setRole("SUPER_ADMIN");
        userRepository.save(newAdmin);
        roleService.assignRole(newAdmin.getId(), "SUPER_ADMIN");

        User bootstrap = userRepository.findById(bootstrapUserId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Bootstrap account not found"));
        // "Locked", not deleted (Gap 2): the row -- and whatever it did during setup -- stays in
        // every audit trail and foreign key that already reference it. Reuses the exact same
        // status column and login-time check AdminUserService.suspend() already relies on, rather
        // than inventing a parallel "locked" concept -- see User.status's own doc comment.
        bootstrap.setStatus("SUSPENDED");
        userRepository.save(bootstrap);
        // Suspension alone blocks new logins, but NOT a still-valid JWT already issued --
        // AuthorizationService.effectiveAuthorities() never checks user.status, only
        // roles/permissions. Explicitly revoking BOOTSTRAP_ADMIN means SYSTEM_INITIALIZE is gone
        // immediately, not just "until this token's 15-minute expiry" (application.yml).
        roleService.revokeRole(bootstrapUserId, "BOOTSTRAP_ADMIN");

        platformSettingsService.markSetupCompleted();
        deleteSetupKeyFileIfPresent();

        auditService.record(bootstrapUserId, "SETUP_COMPLETED", "User", newAdmin.getId(),
                Map.of("newAdminEmail", newAdmin.getEmail()));
    }

    /**
     * Best-effort cleanup of BootstrapService's .finora/installation.key, per the design
     * discussion: "if deletion fails, don't fail the setup ... log a warning that the file could
     * not be removed." A file permission hiccup at this point has nothing to do with whether
     * setup itself succeeded -- platformSettingsService.markSetupCompleted() above already
     * committed, and BootstrapService's own setup_completed gate means it will never look at this
     * file (or write a new one) again regardless of whether this delete succeeds. Silent no-op
     * when the file never existed in the first place (FINORA_SETUP_KEY was used instead, or dev
     * startup's own file write already failed and fell back to logging).
     */
    private void deleteSetupKeyFileIfPresent() {
        try {
            setupKeyFileWriter.deleteIfPresent();
        } catch (IOException e) {
            log.warn("Could not delete {} after setup completed ({}) -- safe to remove manually; "
                    + "it will never be read again.", SetupKeyFileWriter.SETUP_KEY_FILE_PATH, e.getMessage());
        }
    }
}
