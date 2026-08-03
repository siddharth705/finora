package com.finora.service;

import com.finora.dto.AuthDtos.RegisterRequest;
import com.finora.dto.RoleDto;
import com.finora.entity.PlatformSettings;
import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** V33__bootstrap_admin.sql / SetupService.completeSetup(). Locks in the whole point of the
 *  design: the real admin gets created, the bootstrap account gets locked (not deleted), and
 *  setup gets marked complete -- see completeSetup()'s own doc comment for why this is one
 *  @Transactional method rather than three separate calls a caller could interleave. */
class SetupServiceTest {

    private UserRepository userRepository;
    private AuthService authService;
    private RoleService roleService;
    private PlatformSettingsService platformSettingsService;
    private AuditService auditService;
    private SetupKeyFileWriter setupKeyFileWriter;
    private SetupService setupService;

    private final UUID bootstrapUserId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        authService = mock(AuthService.class);
        roleService = mock(RoleService.class);
        platformSettingsService = mock(PlatformSettingsService.class);
        auditService = mock(AuditService.class);
        setupKeyFileWriter = mock(SetupKeyFileWriter.class);
        setupService = new SetupService(userRepository, authService, roleService, platformSettingsService, auditService, setupKeyFileWriter);
    }

    private PlatformSettings settingsWith(boolean setupCompleted) {
        PlatformSettings settings = new PlatformSettings();
        settings.setSetupCompleted(setupCompleted);
        return settings;
    }

    private User userWith(UUID id, String email) {
        User user = new User();
        ReflectionTestUtils.setField(user, "id", id);
        user.setEmail(email);
        return user;
    }

    @Test
    void completeSetup_createsTheRealAdmin_locksBootstrap_andMarksSetupComplete() throws Exception {
        when(platformSettingsService.tryMarkSetupCompleted()).thenReturn(true);
        RegisterRequest request = new RegisterRequest(
                "admin@example.com", "correct-horse-battery-staple", "Real Admin", "+919876543210");
        UUID newAdminId = UUID.randomUUID();
        User newAdmin = userWith(newAdminId, "admin@example.com");
        when(authService.adminCreateUser(request, bootstrapUserId, "ADMIN")).thenReturn(newAdmin);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        User bootstrap = userWith(bootstrapUserId, "BOOTSTRAP_ADMIN");
        when(userRepository.findById(bootstrapUserId)).thenReturn(Optional.of(bootstrap));
        when(roleService.assignRole(newAdminId, "SUPER_ADMIN"))
                .thenReturn(new RoleDto(UUID.randomUUID(), "SUPER_ADMIN", "desc", java.util.List.of()));

        setupService.completeSetup(bootstrapUserId, request);

        assertThat(newAdmin.getRole()).isEqualTo("SUPER_ADMIN");
        verify(roleService).assignRole(newAdminId, "SUPER_ADMIN");
        // Locked, not deleted (Gap 2) -- reuses the exact same status column/value
        // AdminUserService.suspend() already relies on, so login is blocked the same proven way.
        assertThat(bootstrap.getStatus()).isEqualTo("SUSPENDED");
        // Role revoked too, not just status changed -- closes the "still-valid JWT keeps
        // SYSTEM_INITIALIZE for up to 15 more minutes" gap, since AuthorizationService never
        // checks user.status, only roles/permissions.
        verify(roleService).revokeRole(bootstrapUserId, "BOOTSTRAP_ADMIN");
        verify(userRepository, never()).delete(any());
        verify(userRepository, never()).deleteById(any());
        verify(platformSettingsService).tryMarkSetupCompleted();
        verify(auditService).record(eq(bootstrapUserId), eq("SETUP_COMPLETED"), eq("User"), eq(newAdminId), any());
        // Best-effort cleanup of the installation-key file BootstrapService wrote (dev mode) --
        // a silent no-op if FINORA_SETUP_KEY was used instead, since no file would exist.
        verify(setupKeyFileWriter).deleteIfPresent();
    }

    @Test
    void completeSetup_stillSucceeds_whenDeletingTheKeyFileFails() throws Exception {
        // Per the design discussion: "if deletion fails, don't fail the setup ... log a warning."
        // A file permission hiccup at cleanup time must never undo (or even be perceived as
        // failing) a setup that already succeeded and committed to the database.
        when(platformSettingsService.tryMarkSetupCompleted()).thenReturn(true);
        RegisterRequest request = new RegisterRequest(
                "admin@example.com", "correct-horse-battery-staple", "Real Admin", "+919876543210");
        UUID newAdminId = UUID.randomUUID();
        User newAdmin = userWith(newAdminId, "admin@example.com");
        when(authService.adminCreateUser(request, bootstrapUserId, "ADMIN")).thenReturn(newAdmin);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        User bootstrap = userWith(bootstrapUserId, "BOOTSTRAP_ADMIN");
        when(userRepository.findById(bootstrapUserId)).thenReturn(Optional.of(bootstrap));
        when(roleService.assignRole(newAdminId, "SUPER_ADMIN"))
                .thenReturn(new RoleDto(UUID.randomUUID(), "SUPER_ADMIN", "desc", java.util.List.of()));
        doThrow(new java.io.IOException("permission denied")).when(setupKeyFileWriter).deleteIfPresent();

        setupService.completeSetup(bootstrapUserId, request); // must not throw

        verify(platformSettingsService).tryMarkSetupCompleted();
        verify(auditService).record(eq(bootstrapUserId), eq("SETUP_COMPLETED"), eq("User"), eq(newAdminId), any());
    }

    @Test
    void completeSetup_refusesToRunAgain_onceSetupIsAlreadyComplete() {
        // false = the atomic claim didn't win -- either a previous call already completed setup,
        // or (the bug this replaced a plain check-then-write with) a concurrent call is racing
        // this one. Either way, completeSetup() must reject immediately and do nothing else.
        when(platformSettingsService.tryMarkSetupCompleted()).thenReturn(false);
        RegisterRequest request = new RegisterRequest(
                "admin@example.com", "correct-horse-battery-staple", "Real Admin", "+919876543210");

        assertThatThrownBy(() -> setupService.completeSetup(bootstrapUserId, request))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("already been completed");

        verifyNoInteractions(authService);
        verifyNoInteractions(auditService);
    }

    @Test
    void isInstallationKeyAvailable_isMoot_onceSetupIsAlreadyComplete() {
        when(platformSettingsService.getEntity()).thenReturn(settingsWith(true));

        assertThat(setupService.isInstallationKeyAvailable()).isTrue();
        verifyNoInteractions(setupKeyFileWriter);
    }

    @Test
    void isInstallationKeyAvailable_isTrue_whenAnOperatorSuppliedKeyIsConfigured() {
        // Never checks the file at all when FINORA_SETUP_KEY is set -- no file is ever written in
        // that mode, so checking for one would incorrectly report "unavailable" in every valid
        // production deployment.
        when(platformSettingsService.getEntity()).thenReturn(settingsWith(false));
        ReflectionTestUtils.setField(setupService, "configuredSetupKey", "operator-chosen-key");

        assertThat(setupService.isInstallationKeyAvailable()).isTrue();
        verifyNoInteractions(setupKeyFileWriter);
    }

    @Test
    void isInstallationKeyAvailable_reflectsWhetherTheFileExists_inDevMode() {
        when(platformSettingsService.getEntity()).thenReturn(settingsWith(false));
        when(setupKeyFileWriter.exists()).thenReturn(true);
        assertThat(setupService.isInstallationKeyAvailable()).isTrue();

        when(setupKeyFileWriter.exists()).thenReturn(false);
        assertThat(setupService.isInstallationKeyAvailable()).isFalse();
    }
}
