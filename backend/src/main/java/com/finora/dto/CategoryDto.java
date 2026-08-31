package com.finora.dto;

import java.util.UUID;

public record CategoryDto(UUID id, String name, boolean isSystem, String icon, String color) {}
