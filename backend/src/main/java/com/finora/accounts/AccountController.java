package com.finora.accounts;

import com.finora.dto.ApiResponse;
import com.finora.security.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {

    private final AccountService accountService;
    private final CurrentUser currentUser;

    public AccountController(AccountService accountService, CurrentUser currentUser) {
        this.accountService = accountService;
        this.currentUser = currentUser;
    }

    @GetMapping
    public ApiResponse<List<AccountDto>> list() {
        return ApiResponse.ok(accountService.listForUser(currentUser.id()));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<AccountDto>> create(@RequestBody AccountDto.CreateRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(accountService.create(currentUser.id(), request), "Account created"));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<AccountDto>> update(@PathVariable UUID id, @RequestBody AccountDto.CreateRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(accountService.update(currentUser.id(), id, request), "Account updated"));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable UUID id) {
        accountService.delete(currentUser.id(), id);
        return ResponseEntity.ok(ApiResponse.ok(null, "Account deleted"));
    }
}
