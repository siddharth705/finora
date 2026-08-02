package com.finora.service;

import com.finora.dto.UserSettingsDto;
import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Previously had no coverage at all. Added specifically for the timezone validation added
 *  alongside DashboardService's fix (User.timezone existed and was persisted/displayed but never
 *  actually consulted by anything -- see that fix's own commit for the full story). Without this
 *  validation, an unparseable timezone could reach the database and only surface as a problem
 *  much later, whenever something finally tried to resolve it as a real ZoneId. */
class UserSettingsServiceTest {

    private UserRepository userRepository;
    private UserSettingsService service;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        service = new UserSettingsService(userRepository);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private User existingUser() {
        User u = new User();
        ReflectionTestUtils.setField(u, "id", userId);
        u.setEmail("amy@example.com");
        u.setFullName("Amy");
        return u;
    }

    @Test
    void update_acceptsARealIanaTimezone() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(existingUser()));

        UserSettingsDto result = service.update(userId,
                new UserSettingsDto.UpdateRequest(null, null, "America/New_York", null));

        assertThat(result.timezone()).isEqualTo("America/New_York");
    }

    @Test
    void update_rejectsAMalformedTimezone_withoutEverPersistingIt() {
        User user = existingUser();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.update(userId, new UserSettingsDto.UpdateRequest(null, null, "Not/A_Real_Zone", null)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Not/A_Real_Zone");

        // The default from the entity's own field initializer -- confirms the rejected value
        // never got as far as user.setTimezone(...), not just that an exception was thrown.
        assertThat(user.getTimezone()).isEqualTo("Asia/Kolkata");
    }

    @Test
    void update_leavesTimezoneUnchanged_whenNotProvided() {
        User user = existingUser();
        user.setTimezone("Europe/London");
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        UserSettingsDto result = service.update(userId,
                new UserSettingsDto.UpdateRequest(new BigDecimal("500"), null, null, null));

        assertThat(result.timezone()).isEqualTo("Europe/London");
    }

    @Test
    void update_changesFullName_whenProvided() {
        User user = existingUser();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        UserSettingsDto result = service.update(userId,
                new UserSettingsDto.UpdateRequest(null, null, null, "  Amy Santiago  "));

        // Trimmed -- a name field is exactly the kind of input a user is likely to paste with
        // stray leading/trailing whitespace from another app.
        assertThat(result.fullName()).isEqualTo("Amy Santiago");
    }

    @Test
    void update_rejectsABlankFullName_withoutEverPersistingIt() {
        User user = existingUser();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.update(userId, new UserSettingsDto.UpdateRequest(null, null, null, "   ")))
                .isInstanceOf(ApiException.class);

        assertThat(user.getFullName()).isEqualTo("Amy");
    }

    @Test
    void get_includesThePhoneAndCreatedAtFacts_alongsideTheEditablePreferences() {
        User user = existingUser();
        user.setPhoneNumber("+919876543210");
        user.setPhoneVerified(true);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        UserSettingsDto result = service.get(userId);

        assertThat(result.phoneNumber()).isEqualTo("+919876543210");
        assertThat(result.phoneVerified()).isTrue();
        assertThat(result.createdAt()).isEqualTo(user.getCreatedAt());
    }

    @Test
    void get_reportsPasswordChangedAtAsNull_forAnAccountThatHasNeverChangedItsPassword() {
        User user = existingUser();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        UserSettingsDto result = service.get(userId);

        // Never backfilled to a guess -- see User.passwordChangedAt's own doc comment.
        assertThat(result.passwordChangedAt()).isNull();
    }
}
