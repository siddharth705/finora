package com.finora.service;

import com.finora.dto.AdminDtos.RecentImportDto;
import com.finora.entity.StatementImport;
import com.finora.entity.User;
import com.finora.repository.StatementImportRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** AdminSystemService.recentImports() -- mocked-repository unit test. See RecentImportDto's class
 *  comment for why hadSkippedRows (not "status", which never varies) is the one real per-row
 *  signal this surfaces. */
class AdminSystemServiceRecentImportsTest {

    private StatementImportRepository statementImportRepository;
    private UserRepository userRepository;
    private AdminSystemService service;

    @BeforeEach
    void setUp() {
        statementImportRepository = mock(StatementImportRepository.class);
        userRepository = mock(UserRepository.class);
        service = new AdminSystemService(mock(HealthEndpoint.class), statementImportRepository, userRepository);
    }

    private StatementImport statementImport(UUID userId, String fileName, int skipped) {
        StatementImport si = new StatementImport();
        ReflectionTestUtils.setField(si, "id", UUID.randomUUID());
        si.setUserId(userId);
        si.setFileName(fileName);
        si.setTransactionsImported(5);
        si.setTransactionsSkipped(skipped);
        si.setImportedAt(Instant.now());
        return si;
    }

    private User user(UUID id, String email) {
        User u = new User();
        ReflectionTestUtils.setField(u, "id", id);
        u.setEmail(email);
        return u;
    }

    @Test
    void recentImports_returnsEmptyList_whenThereAreNoImportsAtAll() {
        when(statementImportRepository.findAllByOrderByImportedAtDesc(any())).thenReturn(List.of());

        assertThat(service.recentImports()).isEmpty();
    }

    @Test
    void recentImports_resolvesTheOwningUsersEmail_andFlagsSkippedRows() {
        UUID userId = UUID.randomUUID();
        StatementImport withSkips = statementImport(userId, "messy.csv", 4);
        StatementImport clean = statementImport(userId, "clean.csv", 0);
        when(statementImportRepository.findAllByOrderByImportedAtDesc(any())).thenReturn(List.of(withSkips, clean));
        when(userRepository.findAllById(any())).thenReturn(List.of(user(userId, "owner@example.com")));

        List<RecentImportDto> result = service.recentImports();

        assertThat(result).hasSize(2);
        RecentImportDto messyRow = result.stream().filter(r -> r.fileName().equals("messy.csv")).findFirst().orElseThrow();
        assertThat(messyRow.userEmail()).isEqualTo("owner@example.com");
        assertThat(messyRow.hadSkippedRows()).isTrue();
        assertThat(messyRow.transactionsSkipped()).isEqualTo(4);

        RecentImportDto cleanRow = result.stream().filter(r -> r.fileName().equals("clean.csv")).findFirst().orElseThrow();
        assertThat(cleanRow.hadSkippedRows()).isFalse();
    }

    @Test
    void recentImports_fallsBackToAPlaceholderEmail_whenTheOwningUserCantBeResolved() {
        UUID userId = UUID.randomUUID();
        when(statementImportRepository.findAllByOrderByImportedAtDesc(any()))
                .thenReturn(List.of(statementImport(userId, "orphaned.csv", 0)));
        // The user row is gone (or was never resolvable) -- findAllById legitimately returns
        // nothing for that id in that case.
        when(userRepository.findAllById(any())).thenReturn(List.of());

        List<RecentImportDto> result = service.recentImports();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).userEmail()).isEqualTo("Unknown user");
    }
}
