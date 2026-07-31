package com.finora.controller;

import com.finora.dto.ApiResponse;
import com.finora.dto.NetWorthDto;
import com.finora.security.CurrentUser;
import com.finora.service.NetWorthService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/networth")
public class NetWorthController {

    private final NetWorthService netWorthService;
    private final CurrentUser currentUser;

    public NetWorthController(NetWorthService netWorthService, CurrentUser currentUser) {
        this.netWorthService = netWorthService;
        this.currentUser = currentUser;
    }

    @GetMapping
    public ApiResponse<NetWorthDto> current() {
        return ApiResponse.ok(netWorthService.current(currentUser.id()));
    }

    @PostMapping("/snapshot")
    public ApiResponse<NetWorthDto> saveSnapshot() {
        return ApiResponse.ok(netWorthService.saveSnapshotForToday(currentUser.id()), "Snapshot saved");
    }
}
