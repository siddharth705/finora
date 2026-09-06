package com.finora.service;

import com.finora.entity.PlatformSettings;
import com.finora.entity.Role;
import com.finora.entity.User;
import com.finora.repository.RoleRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** V33__bootstrap_admin.sql / BootstrapService.run(). See BootstrapService's own doc comment for
 *  why this only ever fires under exactly one condition (setup not completed AND no bootstrap
 *  account already exists) -- these tests lock in each side of that guard, plus the race case
 *  where another instance wins the create between this instance's own check and insert. */
class BootstrapServiceTest {

    private UserRepository userRepository;
    private RoleRepository roleRepository;
    private PlatformSettingsService platformSettingsService;
    private PasswordEncoder passwordEncoder;
    private AuditService auditService;
    private SetupKeyFileWriter setupKeyFileWriter;
    private BootstrapService bootstrapService;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        roleRepository = mock(RoleRepository.class);
        platformSettingsService = mock(PlatformSettingsService.class);
        passwordEncoder = mock(PasswordEncoder.class);
        auditService = mock(AuditService.class);
        setupKeyFileWriter = mock(SetupKeyFileWriter.class);
        bootstrapService = new BootstrapService(userRepository, roleRepository, platformSettingsService, passwordEncoder, auditService, setupKeyFileWriter);

        when(passwordEncoder.encode(any())).thenReturn("hashed-password");
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        ReflectionTestUtils.setField(bootstrapService, "bootstrapEnabled", true);
    }

    private PlatformSettings settingsWith(boolean setupCompleted) {
        PlatformSettings settings = new PlatformSettings();
        settings.setSetupCompleted(setupCompleted);
        return settings;
    }

    @Test
    void createsABootstrapAccount_whenSetupIsNotCompleteAndNoneExistsYet() throws Exception {
        when(platformSettingsService.getEntity()).thenReturn(settingsWith(false));
        when(userRepository.findByEmailIgnoreCaseAndAccountScope("BOOTSTRAP_ADMIN", "ADMIN")).thenReturn(Optional.empty());
        Role bootstrapRole = new Role();
        ReflectionTestUtils.setField(bootstrapRole, "id", UUID.randomUUID());
        bootstrapRole.setName("BOOTSTRAP_ADMIN");
        when(roleRepository.findByName("BOOTSTRAP_ADMIN")).thenReturn(Optional.of(bootstrapRole));

        bootstrapService.run(null);

        // ONE write, carrying the role already. run() used to saveAndFlush the user and then
        // save() it again with the role attached, which left a window where a bootstrap account
        // existed without the role that makes it usable -- and the "already exists" early return
        // means a restart would never repair it. Asserting on saveAndFlush specifically is what
        // pins that: a second save() reappearing is the regression.
        verify(userRepository).saveAndFlush(argThat(u ->
                "BOOTSTRAP_ADMIN".equals(u.getEmail())
                        && u.isPhoneVerified() // bypasses PhoneVerificationFilter -- see doc comment
                        && u.getRoles().contains(bootstrapRole)));
        verify(userRepository, never()).save(any());
        verify(auditService).record(any(), eq("BOOTSTRAP_CREATED"), eq("User"), any());
        // Dev default (no FINORA_SETUP_KEY configured): the generated key is written to the file,
        // never logged directly -- see announceSetupKey's own doc comment for why.
        verify(setupKeyFileWriter).write(any());
    }

    @Test
    void doesNothing_whenBootstrapIsDisabled() {
        // A boot that only exists to prove the jar starts (CI's "Production classpath check" /
        // "Runtime dependency verification") sets this false because it shares a database with the
        // boot that follows -- see application.yml's app.bootstrap comment for the failure this
        // guards against. Must not even check platformSettingsService, same as the already-complete
        // guard below: no path here should ever look for a reason to create a bootstrap account.
        ReflectionTestUtils.setField(bootstrapService, "bootstrapEnabled", false);

        bootstrapService.run(null);

        verifyNoInteractions(platformSettingsService);
        verifyNoInteractions(userRepository);
        verifyNoInteractions(roleRepository);
        verifyNoInteractions(auditService);
    }

    @Test
    void doesNothing_whenSetupIsAlreadyComplete() {
        when(platformSettingsService.getEntity()).thenReturn(settingsWith(true));

        bootstrapService.run(null);

        // Not just "no user created" -- Gap 7 from the design discussion is specifically that an
        // already-initialized platform must never even check for an existing bootstrap account,
        // let alone create a fresh one (e.g. after every admin was accidentally deleted).
        verifyNoInteractions(userRepository);
        verifyNoInteractions(roleRepository);
        verifyNoInteractions(auditService);
    }

    @Test
    void doesNotCreateADuplicate_whenABootstrapAccountAlreadyExists() {
        when(platformSettingsService.getEntity()).thenReturn(settingsWith(false));
        User existing = new User();
        existing.setEmail("BOOTSTRAP_ADMIN");
        when(userRepository.findByEmailIgnoreCaseAndAccountScope("BOOTSTRAP_ADMIN", "ADMIN")).thenReturn(Optional.of(existing));

        bootstrapService.run(null);

        verify(userRepository, never()).save(any());
        verifyNoInteractions(roleRepository);
        verifyNoInteractions(auditService);
    }

    /**
     * The race this test simulates: two instances (or two threads racing during a crash/restart)
     * both call findByEmail() before either has inserted anything, so both see "doesn't exist yet"
     * and both attempt to create it. User.email's unique constraint guarantees only one row can
     * ever exist -- but without this catch, the LOSING instance's constraint-violation exception
     * would propagate out of run() and, per Spring Boot's ApplicationRunner contract, abort that
     * instance's entire application startup rather than just skip a redundant bootstrap creation.
     *
     * <p><b>This test could not see the bug it was written for.</b> run() was @Transactional, so
     * in production the constraint violation marked the transaction rollback-only and returning
     * normally from the catch made Spring throw UnexpectedRollbackException at commit -- aborting
     * startup anyway, exactly what the catch existed to prevent. A Mockito test never sees it,
     * because there is no transaction to commit; the same blind spot RefreshTokenService.rotate's
     * own comment describes. run() is no longer transactional, which is what actually makes this
     * assertion mean something.
     */
    @Test
    void doesNotCrashApplicationStartup_whenAnotherInstanceWinsTheRaceToCreateBootstrap() throws Exception {
        when(platformSettingsService.getEntity()).thenReturn(settingsWith(false));
        when(userRepository.findByEmailIgnoreCaseAndAccountScope("BOOTSTRAP_ADMIN", "ADMIN")).thenReturn(Optional.empty());
        Role bootstrapRole = new Role();
        ReflectionTestUtils.setField(bootstrapRole, "id", UUID.randomUUID());
        bootstrapRole.setName("BOOTSTRAP_ADMIN");
        when(roleRepository.findByName("BOOTSTRAP_ADMIN")).thenReturn(Optional.of(bootstrapRole));
        when(userRepository.saveAndFlush(any(User.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint"));

        bootstrapService.run(null); // must not throw

        // The role is now resolved BEFORE the insert, so that a winning save writes the user and
        // its grant in one flush -- meaning roleRepository IS touched on this path, unlike before.
        // What must still not happen is any claim that this instance created something.
        verifyNoInteractions(auditService);
        verify(setupKeyFileWriter, never()).write(any());
    }

    @Test
    void usesTheOperatorSuppliedKey_andNeverWritesAFile_whenFinoraSetupKeyIsConfigured() throws Exception {
        when(platformSettingsService.getEntity()).thenReturn(settingsWith(false));
        when(userRepository.findByEmailIgnoreCaseAndAccountScope("BOOTSTRAP_ADMIN", "ADMIN")).thenReturn(Optional.empty());
        Role bootstrapRole = new Role();
        ReflectionTestUtils.setField(bootstrapRole, "id", UUID.randomUUID());
        bootstrapRole.setName("BOOTSTRAP_ADMIN");
        when(roleRepository.findByName("BOOTSTRAP_ADMIN")).thenReturn(Optional.of(bootstrapRole));
        ReflectionTestUtils.setField(bootstrapService, "configuredSetupKey", "operator-chosen-key");

        bootstrapService.run(null);

        // The operator already knows the key they set -- nothing generated, nothing written,
        // nothing logged that contains it.
        verify(passwordEncoder).encode("operator-chosen-key");
        verifyNoInteractions(setupKeyFileWriter);
    }

    @Test
    void fallsBackToLogging_whenWritingTheKeyFileFails() throws Exception {
        when(platformSettingsService.getEntity()).thenReturn(settingsWith(false));
        when(userRepository.findByEmailIgnoreCaseAndAccountScope("BOOTSTRAP_ADMIN", "ADMIN")).thenReturn(Optional.empty());
        Role bootstrapRole = new Role();
        ReflectionTestUtils.setField(bootstrapRole, "id", UUID.randomUUID());
        bootstrapRole.setName("BOOTSTRAP_ADMIN");
        when(roleRepository.findByName("BOOTSTRAP_ADMIN")).thenReturn(Optional.of(bootstrapRole));
        doThrow(new IOException("read-only filesystem")).when(setupKeyFileWriter).write(any());

        // Must not throw and must not abort application startup -- a working-but-imperfect
        // fallback (logging the key directly, same as before this feature existed) beats a setup
        // flow where the key can't be discovered at all.
        bootstrapService.run(null);

        verify(setupKeyFileWriter).write(any());
        verify(auditService).record(any(), eq("BOOTSTRAP_CREATED"), eq("User"), any());
    }
}
