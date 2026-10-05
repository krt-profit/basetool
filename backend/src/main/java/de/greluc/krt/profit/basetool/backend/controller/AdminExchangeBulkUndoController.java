/*
 * Profit Basetool - squadron-management web app.
 * Copyright (C) 2026 Lucas Greuloch
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package de.greluc.krt.profit.basetool.backend.controller;

import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.model.dto.ExchangeBulkUndoInstallationDto;
import de.greluc.krt.profit.basetool.backend.model.dto.ExchangeBulkUndoPreviewDto;
import de.greluc.krt.profit.basetool.backend.model.dto.ExchangeBulkUndoRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.ExchangeBulkUndoRunDetailDto;
import de.greluc.krt.profit.basetool.backend.model.dto.ExchangeBulkUndoRunDto;
import de.greluc.krt.profit.basetool.backend.service.exchange.ExchangeBulkUndoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * An admin's undo of one exchange client's writes for every member, {@code ADMIN} only
 * (REQ-XCH-034).
 */
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
@PreAuthorize(Roles.HAS_ROLE_ADMIN)
@Tag(name = "Admin — Exchange bulk undo", description = "Undo one client's writes for all members")
public class AdminExchangeBulkUndoController {

  private final ExchangeBulkUndoService bulkUndoService;

  /**
   * Shows what a bulk undo would reach.
   *
   * @param id the registry id of the client
   * @param request the scope
   * @return the members and entries in scope
   */
  @NotNull
  @PostMapping("/exchange-clients/{id}/undo/preview")
  @Operation(
      summary = "Preview a bulk undo",
      description = "Counts the members and journal entries in scope; writes nothing.")
  @ApiResponse(responseCode = "200", description = "The members and entries in scope")
  @ApiResponse(responseCode = "400", description = "The span starts in the future")
  @ApiResponse(responseCode = "404", description = "No such client or installation")
  public ResponseEntity<ExchangeBulkUndoPreviewDto> preview(
      @PathVariable @NotNull UUID id,
      @RequestBody @Valid @NotNull ExchangeBulkUndoRequest request) {
    return ResponseEntity.ok(bulkUndoService.preview(id, request));
  }

  /**
   * Suspends the client and starts undoing its writes for every member in the background.
   *
   * @param id the registry id of the client
   * @param request the scope
   * @param authentication the admin, whom the run acts as
   * @return the started run
   */
  @NotNull
  @PostMapping("/exchange-clients/{id}/undo")
  @Operation(
      summary = "Start a bulk undo",
      description =
          "Suspends the client through the registry unless it already is, then undoes its writes"
              + " member by member in the background with the member's own undo semantics.")
  @ApiResponse(responseCode = "202", description = "The started run")
  @ApiResponse(responseCode = "400", description = "The span starts in the future")
  @ApiResponse(responseCode = "404", description = "No such client or installation")
  @ApiResponse(
      responseCode = "409",
      description =
          "A bulk undo of the client is already running, or too many runs are queued; a queued-out"
              + " run is recorded as failed and the client stays suspended")
  @ApiResponse(responseCode = "502", description = "The suspension could not reach the mirror")
  public ResponseEntity<ExchangeBulkUndoRunDto> start(
      @PathVariable @NotNull UUID id,
      @RequestBody @Valid @NotNull ExchangeBulkUndoRequest request,
      @NotNull Authentication authentication) {
    return ResponseEntity.status(HttpStatus.ACCEPTED)
        .body(bulkUndoService.start(id, request, authentication));
  }

  /**
   * Lists the client's installations with writes a bulk undo could reach.
   *
   * @param id the registry id of the client
   * @param since the start of the span
   * @return the installations, most writes first, without their labels
   */
  @NotNull
  @GetMapping("/exchange-clients/{id}/undo/installations")
  @Operation(summary = "Installations a bulk undo could reach")
  @ApiResponse(responseCode = "200", description = "At most 500 installations")
  @ApiResponse(responseCode = "404", description = "No such client")
  public ResponseEntity<List<ExchangeBulkUndoInstallationDto>> installations(
      @PathVariable @NotNull UUID id, @RequestParam @NotNull Instant since) {
    return ResponseEntity.ok(bulkUndoService.installations(id, since));
  }

  /**
   * Lists the most recent bulk undo runs.
   *
   * @return at most twenty runs, newest first
   */
  @NotNull
  @GetMapping("/exchange-undo-runs")
  @Operation(summary = "Recent bulk undo runs")
  @ApiResponse(responseCode = "200", description = "At most twenty runs")
  public ResponseEntity<List<ExchangeBulkUndoRunDto>> runs() {
    return ResponseEntity.ok(bulkUndoService.recentRuns());
  }

  /**
   * Loads one run with the entries it left alone.
   *
   * @param runId the run
   * @return the run and its first skipped entries
   */
  @NotNull
  @GetMapping("/exchange-undo-runs/{runId}")
  @Operation(summary = "A bulk undo run and what it left alone")
  @ApiResponse(responseCode = "200", description = "The run")
  @ApiResponse(responseCode = "404", description = "No such run")
  public ResponseEntity<ExchangeBulkUndoRunDetailDto> run(@PathVariable @NotNull UUID runId) {
    return ResponseEntity.ok(bulkUndoService.run(runId));
  }
}
