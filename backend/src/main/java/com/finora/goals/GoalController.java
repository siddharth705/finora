package com.finora.goals;

import com.finora.dto.ApiResponse;
import com.finora.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/goals")
public class GoalController {

    private final GoalService goalService;
    private final CurrentUser currentUser;

    public GoalController(GoalService goalService, CurrentUser currentUser) {
        this.goalService = goalService;
        this.currentUser = currentUser;
    }

    @GetMapping
    public ApiResponse<List<GoalDto>> list() {
        return ApiResponse.ok(goalService.listForUser(currentUser.id()));
    }

    @PostMapping
    public ApiResponse<GoalDto> create(@Valid @RequestBody GoalDto.CreateRequest request) {
        return ApiResponse.ok(goalService.create(currentUser.id(), request), "Goal created");
    }

    @PostMapping("/{id}/contributions")
    public ApiResponse<GoalDto> addContribution(@PathVariable UUID id, @Valid @RequestBody GoalDto.ContributionRequest request) {
        return ApiResponse.ok(goalService.addContribution(currentUser.id(), id, request.amount()), "Contribution added");
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable UUID id) {
        goalService.delete(currentUser.id(), id);
        return ResponseEntity.ok(ApiResponse.ok(null, "Goal deleted"));
    }
}
