package com.finora.controller;

import com.finora.dto.ApiResponse;
import com.finora.dto.ReportDto;
import com.finora.security.CurrentUser;
import com.finora.service.ReportService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/reports")
public class ReportController {

    private final ReportService reportService;
    private final CurrentUser currentUser;

    public ReportController(ReportService reportService, CurrentUser currentUser) {
        this.reportService = reportService;
        this.currentUser = currentUser;
    }

    @GetMapping("/months")
    public ApiResponse<List<String>> availableMonths() {
        return ApiResponse.ok(reportService.availableMonths(currentUser.id()));
    }

    @GetMapping
    public ApiResponse<ReportDto> forMonth(@RequestParam String month) {
        return ApiResponse.ok(reportService.forMonth(currentUser.id(), month));
    }
}
