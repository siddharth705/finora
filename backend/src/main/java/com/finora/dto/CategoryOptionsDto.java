package com.finora.dto;

import java.util.List;

public record CategoryOptionsDto(List<Option> icons, List<Option> colors) {
    public record Option(String token, String label) {}
}
