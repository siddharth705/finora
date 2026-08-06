package com.finora.service;

import com.finora.entity.Role;
import com.finora.entity.User;
import com.finora.repository.RoleRepository;
import com.finora.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * First-run setup, step one (V33__bootstrap_admin.sql): on every startup, create a least-
 * privilege BOOTSTRAP_ADMIN account -- holding only SYSTEM_INITIALIZE, nothing else -- instead of
 * the platform ever handing out a temporary SUPER_ADMIN. SetupService (step two) is what actually
 * uses that account to create the first real SUPER_ADMIN and lock this one down again.
 *
 * ApplicationRunner (not @PostConstruct) deliberately -- this needs Flyway's migrations, and
 * therefore the BOOTSTRAP_ADMIN role V33 seeds, to have already run, which is only guaranteed
 * once the whole application context has finished refreshing.
 */
@Component
public class BootstrapService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapService.class);

    // Not a real email address on purpose (Gap 8 from the design discussion): this is a system
    // identity, not a person, and AuthService.resolveEmailForLogin() falls back to matching the
    // identifier as-typed against User.email when it isn't email- or phone-shaped -- so this
    // still works as the "Email or phone" field's value on the login form without needing that
    // resolution logic to change at all.
    static final String BOOTSTRAP_IDENTIFIER = "BOOTSTRAP_ADMIN";

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PlatformSettingsService platformSettingsService;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;
    private final SetupKeyFileWriter setupKeyFileWriter;

    // Empty by default (app.setup.key / FINORA_SETUP_KEY, application.yml). When an operator
    // supplies their own key -- the production path recommended in the design discussion -- there
    // is nothing to generate, write to a file, or print anywhere: they already know it.
    @Value("${app.setup.key}")
    private String configuredSetupKey;

    public BootstrapService(UserRepository userRepository, RoleRepository roleRepository,
                             PlatformSettingsService platformSettingsService, PasswordEncoder passwordEncoder,
                             AuditService auditService, SetupKeyFileWriter setupKeyFileWriter) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.platformSettingsService = platformSettingsService;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
        this.setupKeyFileWriter = setupKeyFileWriter;
    }

    /**
     * <p><b>Deliberately not {@code @Transactional}.</b> It used to be, and that silently defeated
     * the race handling below. Once a constraint violation fires, the JPA session marks the
     * surrounding transaction rollback-only; catching the exception and returning normally then
     * makes Spring throw {@code UnexpectedRollbackException} at commit -- which propagates out of
     * this {@code ApplicationRunner} and aborts startup, the exact outcome the catch was written
     * to prevent. {@code MerchantNormalizationEngine.resolve} states the rule this ran into:
     * "By the time the constraint fires the transaction is already poisoned, and no handling
     * un-poisons it."
     *
     * <p>With no outer transaction, the violation is raised and rolled back entirely inside
     * {@code saveAndFlush}'s own repository-level transaction, so it arrives here as an ordinary
     * catchable exception with nothing left poisoned behind it.
     *
     * <p>Atomicity is preserved by construction rather than by a transaction: the BOOTSTRAP_ADMIN
     * role is resolved and attached BEFORE the single save, so the user row and its {@code
     * user_roles} grant are written in one flush. There is no longer an intermediate state where
     * a bootstrap account exists without the role that makes it usable -- which matters more than
     * it looks, because a half-created account still takes the "already exists" early return
     * above on the next startup and would never be repaired.
     */
    @Override
    public void run(ApplicationArguments args) {
        // Single flag lookup (Gap 6), not a "SELECT * FROM users WHERE role='SUPER_ADMIN'" scan
        // on every boot -- and, per Gap 7, this is the ONLY condition that ever creates a
        // bootstrap account. A platform that has already been set up never gets a fresh one
        // handed out again just because, say, every admin account was later deleted; that would
        // turn an operational mistake into a standing security hole.
        if (platformSettingsService.getEntity().isSetupCompleted()) {
            return;
        }

        // The bootstrap account is an admin-portal account by definition.
        if (userRepository.findByEmailIgnoreCaseAndAccountScope(
                BOOTSTRAP_IDENTIFIER, User.SCOPE_ADMIN).isPresent()) {
            log.warn("Setup is not complete and a bootstrap admin account already exists. If you "
                    + "still have the installation key from when this account was first created, "
                    + "use it to continue setup. If it's lost, delete this user row from the "
                    + "database and restart the backend to generate a new one.");
            return;
        }

        boolean operatorSuppliedKey = configuredSetupKey != null && !configuredSetupKey.isBlank();
        String rawPassword = operatorSuppliedKey ? configuredSetupKey : generatePassword();

        User bootstrap = new User();
        bootstrap.setEmail(BOOTSTRAP_IDENTIFIER);
        bootstrap.setAccountScope(User.SCOPE_ADMIN);
        bootstrap.setPasswordHash(passwordEncoder.encode(rawPassword));
        bootstrap.setFullName("Bootstrap Installer");
        // Bypasses PhoneVerificationFilter -- there's no real phone number to verify, and this
        // account only ever needs to reach POST /api/v1/setup/complete.
        bootstrap.setPhoneVerified(true);
        // Legacy role string, not just the explicit roles row added below -- matches V16's own
        // "backfill both" convention, and is what lets UserRepository.countByRoleNot(...) exclude
        // this account from "total users" stats (AdminOperationalDashboardService/AdminStatsService)
        // with a plain WHERE clause, not a schema change.
        bootstrap.setRole("BOOTSTRAP_ADMIN");

        // Resolved and attached BEFORE the save, so the users row and its user_roles grant go in
        // as one unit -- see this method's doc comment for why that replaces the transaction this
        // method used to carry. A missing role here is a genuine deployment fault (the migration
        // did not run) and still aborts startup loudly, before anything has been written.
        Role bootstrapRole = roleRepository.findByName("BOOTSTRAP_ADMIN")
                .orElseThrow(() -> new IllegalStateException(
                        "BOOTSTRAP_ADMIN role missing -- V33__bootstrap_admin.sql did not run"));
        bootstrap.getRoles().add(bootstrapRole);

        try {
            // saveAndFlush, not save: a plain save()'s INSERT is deferred to the end of the
            // repository's own transaction, and the exception would then surface after this
            // try block rather than inside it. Flushing forces the INSERT -- and therefore
            // User.email's unique constraint check -- to happen right here, where a concurrent
            // race against another instance can actually be caught, instead of surfacing later as
            // an uncaught exception that aborts application startup entirely (Spring Boot treats
            // any exception from an ApplicationRunner as fatal).
            bootstrap = userRepository.saveAndFlush(bootstrap);
        } catch (DataIntegrityViolationException e) {
            // Lost a benign race: another instance (or another thread, in a multi-instance
            // deployment starting up simultaneously) already inserted BOOTSTRAP_ADMIN between our
            // findByEmail() check above and this insert. The unique constraint already guarantees
            // there's still only ever one such row -- this is that guarantee doing its job, not a
            // real failure, so it's logged and swallowed rather than crashing this instance's boot.
            log.info("Bootstrap admin account was created concurrently by another instance; skipping.");
            return;
        }

        // Not attributed to any acting admin -- there isn't one yet, this is the system creating
        // its own installer identity. auditService.record's actor field is this account itself,
        // same as USER_REGISTERED attributes a fresh signup to the user who just registered.
        auditService.record(bootstrap.getId(), "BOOTSTRAP_CREATED", "User", bootstrap.getId());

        announceSetupKey(operatorSuppliedKey, rawPassword);
    }

    /**
     * Three cases, in priority order:
     *  1. Operator supplied their own key (FINORA_SETUP_KEY) -- they already know it. Nothing to
     *     generate, write, or print; just confirm setup is waiting.
     *  2. Dev default: write the generated key to .finora/installation.key (docker-compose.yml bind-
     *     mounts this to the host) rather than printing the secret itself to a log stream that,
     *     in a real deployment, a centralized log aggregator would capture (see
     *     RFC-BOOTSTRAP-01 in docs/bootstrap-setup-future-work.md). The log line here confirms
     *     the file exists and where to find it, but never contains the key itself.
     *  3. If the file write fails for any reason (read-only filesystem, no volume mounted, running
     *     outside Docker some other way) -- fall back to logging the raw key directly, same as
     *     before this change. A working-but-imperfect fallback beats a setup flow with no way to
     *     discover the key at all.
     */
    private void announceSetupKey(boolean operatorSuppliedKey, String rawPassword) {
        if (operatorSuppliedKey) {
            log.warn("FINORA FIRST-RUN SETUP REQUIRED -- sign in with the installation key "
                    + "supplied via FINORA_SETUP_KEY to complete setup.");
            return;
        }

        try {
            setupKeyFileWriter.write(rawPassword);
            log.warn("FINORA FIRST-RUN SETUP REQUIRED -- an installation key was written to {}. "
                    + "Open the admin portal and enter it there to continue.", SetupKeyFileWriter.SETUP_KEY_FILE_PATH);
        } catch (IOException e) {
            log.warn("Could not write the installation key to {} ({}) -- falling back to printing "
                    + "it directly.", SetupKeyFileWriter.SETUP_KEY_FILE_PATH, e.getMessage());
            log.warn("""

                    ================================================================
                     FINORA FIRST-RUN SETUP REQUIRED
                     Open the admin portal and enter this installation key when prompted:

                       {}

                     This key can ONLY be used to complete setup, and is retired the moment it does.
                     It is shown once here and is not recoverable from the database afterward.
                    ================================================================
                    """, rawPassword);
        }
    }

    /** 20 random bytes, base64url-encoded (no padding) -- about 160 bits of entropy, well past
     *  what a brute-force attempt against a single login endpoint needs to worry about, and
     *  URL-safe so it's never mangled by whatever terminal, log viewer, or text file displays it. */
    private String generatePassword() {
        byte[] bytes = new byte[20];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
