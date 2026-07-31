package com.finora.service;

import com.finora.dto.AdminDtos.RecentImportDto;
import com.finora.dto.AdminDtos.SystemHealthDto;
import com.finora.entity.StatementImport;
import com.finora.entity.User;
import com.finora.repository.StatementImportRepository;
import com.finora.repository.UserRepository;
import org.springframework.boot.actuate.health.CompositeHealth;
import org.springframework.boot.actuate.health.HealthComponent;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Wraps Spring Boot Actuator's own HealthEndpoint bean rather than re-implementing DB/disk
 * connectivity checks -- Actuator's default indicators (db, diskSpace, ...) already do exactly
 * what a system-health panel needs, auto-configured for free the moment spring-boot-starter-
 * actuator + a DataSource are both on the classpath (they already are -- see pom.xml). Calling
 * the bean directly (not proxying GET /actuator/health over HTTP) is deliberate: that endpoint
 * has show-details:never set in application.yml, by design, because it's permitAll with no auth
 * in front of it. This service sits behind AdminSystemController's SYSTEM_SETTINGS gate instead,
 * so returning the full per-component breakdown here is intentional, not a leak the public
 * endpoint would otherwise have.
 *
 * recentImports() (Admin Portal Phase 7) is the closest honest equivalent to a background-job
 * monitor this codebase can offer -- see RecentImportDto's class comment for why there's no real
 * job queue or FAILED status to show, and StatementImportHealthProvider for how the aggregate
 * skip-rate signal already feeds the health registry separately from this per-row list.
 */
@Service
public class AdminSystemService {

    private static final int RECENT_IMPORTS_LIMIT = 20;

    private final HealthEndpoint healthEndpoint;
    private final StatementImportRepository statementImportRepository;
    private final UserRepository userRepository;

    public AdminSystemService(HealthEndpoint healthEndpoint, StatementImportRepository statementImportRepository,
                               UserRepository userRepository) {
        this.healthEndpoint = healthEndpoint;
        this.statementImportRepository = statementImportRepository;
        this.userRepository = userRepository;
    }

    public SystemHealthDto health() {
        HealthComponent root = healthEndpoint.health();
        Map<String, String> components = new LinkedHashMap<>();
        // The root is a CompositeHealth only when Actuator has more than one indicator registered
        // (the normal case here -- db + diskSpace + ping at minimum); falling back to a single
        // "application" entry keeps this from ever returning an empty map in a minimal setup.
        if (root instanceof CompositeHealth composite) {
            composite.getComponents().forEach((name, component) ->
                    components.put(name, component.getStatus().getCode()));
        } else {
            components.put("application", root.getStatus().getCode());
        }

        long uptimeSeconds = ManagementFactory.getRuntimeMXBean().getUptime() / 1000;
        return new SystemHealthDto(root.getStatus().getCode(), components, uptimeSeconds, Instant.now());
    }

    @Transactional(readOnly = true)
    public List<RecentImportDto> recentImports() {
        List<StatementImport> imports = statementImportRepository.findAllByOrderByImportedAtDesc(
                PageRequest.of(0, RECENT_IMPORTS_LIMIT));
        if (imports.isEmpty()) return List.of();

        List<UUID> userIds = imports.stream().map(StatementImport::getUserId).distinct().toList();
        Map<UUID, String> emailsById = new HashMap<>();
        for (User u : userRepository.findAllById(userIds)) emailsById.put(u.getId(), u.getEmail());

        return imports.stream()
                .map(i -> new RecentImportDto(
                        i.getId(),
                        i.getUserId(),
                        emailsById.getOrDefault(i.getUserId(), "Unknown user"),
                        i.getFileName(),
                        i.getTransactionsImported(),
                        i.getTransactionsSkipped(),
                        i.getTransactionsSkipped() > 0,
                        i.getImportedAt()))
                .toList();
    }
}
