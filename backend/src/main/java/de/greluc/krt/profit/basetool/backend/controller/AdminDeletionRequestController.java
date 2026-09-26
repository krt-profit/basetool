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

import de.greluc.krt.profit.basetool.backend.model.DeletionRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.DecideDeletionRequestRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.DeletionRequestDto;
import de.greluc.krt.profit.basetool.backend.service.DeletionRequestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The admin-only queue for the members' Art. 17 erasure requests (REQ-SEC-061), with separate
 * endpoints to decline and to execute a request.
 */
@RestController
@RequestMapping("/api/v1/admin/deletion-requests")
@RequiredArgsConstructor
public class AdminDeletionRequestController {

  private final DeletionRequestService deletionRequestService;

  /**
   * Lists the open requests, oldest first — the order the one-month Art. 12(3) deadline runs in.
   *
   * @return the pending requests, each with the requesting member's handle
   */
  @GetMapping
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(
      summary = "List the pending account-deletion requests",
      description =
          "Oldest first, which is the order the Art. 12(3) one-month response deadline runs in. "
              + "Each entry carries the requesting member's handle, because an admin cannot act on "
              + "an anonymous request.")
  public List<DeletionRequestDto> pending() {
    List<DeletionRequest> pending = deletionRequestService.listPending();
    Map<UUID, String> handles =
        deletionRequestService.handlesOf(pending.stream().map(DeletionRequest::getUserId).toList());
    return pending.stream()
        .map(r -> DeletionRequestController.toDto(r, handles.get(r.getUserId())))
        .toList();
  }

  /**
   * Refuses a request with a mandatory reason (Art. 12(4)) and notifies the member.
   *
   * @param id the request to refuse
   * @param request the decision, whose {@code note} must not be blank
   * @return the refused request
   */
  @PostMapping("/{id}/decline")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(
      summary = "Refuse an account-deletion request",
      description =
          "Records the admin's reason and notifies the member. The reason is MANDATORY: "
              + "Art. 12(4) requires telling the requester why a request is refused.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Refused"),
    @ApiResponse(responseCode = "400", description = "No reason was given"),
    @ApiResponse(responseCode = "404", description = "No such pending request")
  })
  public DeletionRequestDto decline(
      @PathVariable UUID id, @NotNull @Valid @RequestBody DecideDeletionRequestRequest request) {
    return DeletionRequestController.toDto(
        deletionRequestService.decline(id, request.requiredNote(), request.version()), null);
  }

  /**
   * Irreversibly carries out a request: deletes the local account and the Keycloak account
   * (REQ-DATA-008), anonymising the member's surviving handle snapshots first when the admin grants
   * {@code grantHistoryErasure}.
   *
   * @param id the request to carry out
   * @param request the decision, including whether the history wish is granted
   * @return {@code 204}; the request row cascades away with the account
   */
  @PostMapping("/{id}/execute")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(
      summary = "Carry out an account-deletion request",
      description =
          "IRREVERSIBLE. Deletes the local row and the Keycloak account, in that order "
              + "(ADR-0111). With grantHistoryErasure, the member's surviving handle snapshots are "
              + "anonymised first, across both audit trails, the bank booking history, the booking "
              + "requests and the two handover recipients. Returns 204: the request row cascades "
              + "away with the account, so there is nothing left to return.")
  @ApiResponses({
    @ApiResponse(responseCode = "204", description = "Carried out"),
    @ApiResponse(responseCode = "404", description = "No such pending request")
  })
  public ResponseEntity<Void> execute(
      @PathVariable UUID id, @NotNull @Valid @RequestBody DecideDeletionRequestRequest request) {
    deletionRequestService.execute(id, request.grantHistoryErasure(), request.version());
    return ResponseEntity.noContent().build();
  }
}
