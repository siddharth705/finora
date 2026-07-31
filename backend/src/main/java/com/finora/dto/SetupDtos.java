package com.finora.dto;

/**
 * DTOs for the first-run platform setup flow (V33__bootstrap_admin.sql, SetupService).
 */
public class SetupDtos {

    /** Backs GET /api/v1/setup/status -- public and unauthenticated by design, since the whole
     *  point is letting the login page decide whether to show a normal sign-in form or redirect
     *  to /setup before anyone has a token yet. installationKeyAvailable lets the setup page show
     *  a specific "the key wasn't found" message rather than a generic wrong-key error when
     *  there's nothing to actually enter -- see SetupService.isInstallationKeyAvailable(). */
    public record SetupStatusDto(boolean setupRequired, boolean installationKeyAvailable) {}
}
