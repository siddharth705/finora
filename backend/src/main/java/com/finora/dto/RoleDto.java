package com.finora.dto;

import java.util.List;
import java.util.UUID;

public record RoleDto(UUID id, String name, String description, List<PermissionDto> permissions) {}
