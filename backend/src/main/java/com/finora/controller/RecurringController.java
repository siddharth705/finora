package com.finora.controller;

import com.finora.dto.ApiResponse;
import com.finora.dto.RecurringDto;
import com.finora.security.CurrentUser;
import com.finora.service.RecurringService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/recurring")
public class RecurringController {

    private final RecurringService recurringService;
    private final CurrentUser currentUser;

    public RecurringController(RecurringService recurringService, CurrentUser currentUser) {
        this.recurringService = recurringService;
        this.currentUser = currentUser;
    }

    @GetMapping
    public ApiResponse<List<RecurringDto>> list() {
        return ApiResponse.ok(recurringService.detectForUser(currentUser.id()));
    }
}
