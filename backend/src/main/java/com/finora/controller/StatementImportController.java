package com.finora.controller;

import com.finora.dto.ApiResponse;
import com.finora.dto.ImportDto.ConfirmRequest;
import com.finora.dto.ImportDto.ConfirmResponse;
import com.finora.dto.StatementImportDto.AccountGroup;
import com.finora.dto.StatementImportDto.ReimportRequest;
import com.finora.dto.StatementImportDto.ReimportResult;
import com.finora.dto.StatementImportDto.Summary;
import com.finora.dto.StatementImportDto.SupersedeRequest;
import com.finora.dto.StatementImportDto.SupersedeResult;
import com.finora.transactions.TransactionDto;
import com.finora.security.CurrentUser;
import com.finora.service.StatementImportService;
import jakarta.validation.Valid;
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
        // Bug fix: this hardcoded "text/csv" for every download, including PDFs -- while
        // StatementImport.sourceFormat held the real answer and its own comment says the format is
        // "explicit, not inferred from fileName's extension." Mobile had already worked around it
        // by inferring from fileName.endsWith('.pdf') -- the exact heuristic the backend rejected
        // -- and web only survived because link.download ignores the type. Serving the right type
        // means neither client has to compensate.
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(file.fileName()).build().toString())
                .body(file.content());
    }

    // The body is optional and so is the password inside it, so a client that posts nothing at all
    // behaves exactly as this endpoint always has. It only ever carries a document password for a
    // protected PDF, whose stored bytes are still encrypted -- see ReimportRequest. A body rather
    // than a query parameter, for the same reason /import/pdf/stage takes one: a password in a URL
    // is captured by access logs, proxy logs and browser history.
    @PostMapping("/{id}/reimport")
    public ApiResponse<ReimportResult> reimport(@PathVariable UUID id,
                                                @RequestBody(required = false) ReimportRequest request) throws Exception {
        return ApiResponse.ok(statementImportService.reimport(currentUser.id(), id,
                request == null ? null : request.password()));
    }

    // Plain JSON, unlike /import/csv/confirm — the file is already stored server-side from the
    // original import, so there's nothing to re-upload here.
    //
    // Deliberately NOT gated by ImportController's Free-tier statement-period cap: `id` names a
    // StatementImport that was already imported once (it always must be, to reimport it), so this
    // reprocesses existing data rather than admitting a new multi-month statement -- exactly the
    // "grandfather existing data, only block new imports" policy that cap is meant to follow.
    @PostMapping("/{id}/reimport/confirm")
    public ApiResponse<ConfirmResponse> confirmReimport(@PathVariable UUID id, @Valid @RequestBody ConfirmRequest request) throws java.io.IOException {
        return ApiResponse.ok(statementImportService.confirmReimport(currentUser.id(), id, request), "Import complete");
    }

    // "Import this one as a replacement?" (Phase 4, §0.3/§0.23): id is the ORIGINAL statement, now
    // marked superseded rather than deleted. The replacement must already be confirmed as its own
    // statement (a normal POST /import/*/confirm) before this call -- see
    // StatementImportService.supersede's own doc comment for why this is two calls, not one.
    @PostMapping("/{id}/supersede")
    public ApiResponse<SupersedeResult> supersede(@PathVariable UUID id, @Valid @RequestBody SupersedeRequest request) {
        return ApiResponse.ok(statementImportService.supersede(currentUser.id(), id, request.supersededByStatementId()),
                "Statement marked as replaced");
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        statementImportService.delete(currentUser.id(), id);
        return ApiResponse.ok(null, "Statement import deleted");
    }
}
