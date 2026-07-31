package com.finora.controller;

import com.finora.dto.ApiResponse;
import com.finora.dto.ImportDto.ConfirmRequest;
import com.finora.dto.ImportDto.ConfirmResponse;
import com.finora.dto.StatementImportDto.AccountGroup;
import com.finora.dto.StatementImportDto.ReimportResult;
import com.finora.dto.StatementImportDto.Summary;
import com.finora.transactions.TransactionDto;
import com.finora.security.CurrentUser;
import com.finora.service.StatementImportService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/statement-imports")
public class StatementImportController {

    private final StatementImportService statementImportService;
    private final CurrentUser currentUser;

    public StatementImportController(StatementImportService statementImportService, CurrentUser currentUser) {
        this.statementImportService = statementImportService;
        this.currentUser = currentUser;
    }

    @GetMapping
    public ApiResponse<List<AccountGroup>> list() {
        return ApiResponse.ok(statementImportService.listGroupedByAccount(currentUser.id()));
    }

    @GetMapping("/{id}")
    public ApiResponse<Summary> detail(@PathVariable UUID id) {
        return ApiResponse.ok(statementImportService.getDetail(currentUser.id(), id));
    }

    @GetMapping("/{id}/transactions")
    public ApiResponse<List<TransactionDto>> transactions(@PathVariable UUID id) {
        return ApiResponse.ok(statementImportService.getTransactions(currentUser.id(), id));
    }

    @GetMapping("/{id}/file")
    public ResponseEntity<byte[]> downloadFile(@PathVariable UUID id) {
        var file = statementImportService.getFile(currentUser.id(), id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(file.fileName()).build().toString())
                .body(file.content());
    }

    @PostMapping("/{id}/reimport")
    public ApiResponse<ReimportResult> reimport(@PathVariable UUID id) throws Exception {
        return ApiResponse.ok(statementImportService.reimport(currentUser.id(), id));
    }

    // Plain JSON, unlike /import/csv/confirm — the file is already stored server-side from the
    // original import, so there's nothing to re-upload here.
    @PostMapping("/{id}/reimport/confirm")
    public ApiResponse<ConfirmResponse> confirmReimport(@PathVariable UUID id, @RequestBody ConfirmRequest request) {
        return ApiResponse.ok(statementImportService.confirmReimport(currentUser.id(), id, request), "Import complete");
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        statementImportService.delete(currentUser.id(), id);
        return ApiResponse.ok(null, "Statement import deleted");
    }
}
