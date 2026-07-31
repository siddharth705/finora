package com.finora.dto;

import java.util.List;

/**
 * The calling user's own effective roles + permissions (AuthorizationService.effectiveAuthorities,
 * minus the "ROLE_" prefix Spring Security's GrantedAuthority convention adds). Backs
 * GET /api/v1/users/me/access -- the admin portal (frontend-admin/) calls this right after login
 * to decide whether the account has any admin-relevant permission at all before letting it into
 * the admin shell, and which nav sections to show based on which specific ones it holds. The
 * regular user frontend has no use for this today, which is exactly why it isn't folded into
 * AuthDtos.AuthResponse -- that record is returned from login/register and asserted on by several
 * existing tests positionally; adding fields there would force every one of those call sites to
 * change for a capability only the admin app needs.
 */
public record MeAccessDto(List<String> roles, List<String> permissions) {}
