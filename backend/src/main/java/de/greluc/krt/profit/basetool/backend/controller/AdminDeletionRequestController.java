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

import de.greluc.krt.profit.basetool.backend.model.dto.DecideDeletionRequestRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.DeletionRequestDto;
import de.greluc.krt.profit.basetool.backend.service.DeletionRequestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The admin queue for the members' Art. 17 erasure requests (REQ-SEC-061).
 *
 * <p>Admin-only, and the reason is not merely that deletion is destructive: the queue names every
 * member who has asked to be erased, which is itself information about them.
 *
 * <p>The two decisions are separate endpoints rather than one with a flag, because they are not
 * variants of each other. {@code /decline} writes a reason and tells the member; {@code /execute}
 * deletes an account and cannot be undone. Giving them one path would make the difference a
 * parameter, and a parameter is a thing a mistake can flip.
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
    return deletionRequestService.listPending().stream()
        .map(
            r -> DeletionRequestController.toDto(r, deletionRequestService.handleOf(r.getUserId())))
        .toList();
  }

  /**
   * Refuses a request, recording why.
   *
   * <p>The note is mandatory: Art. 12(4) requires the requester to be told the reason, together
   * with their right to complain to a supervisory authority and to a judicial remedy. The member is
   * notified, and reads the reason on their profile page.
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
      @PathVariable UUID id, @Valid @RequestBody DecideDeletionRequestRequest request) {
    return DeletionRequestController.toDto(
        deletionRequestService.decline(id, request.requiredNote()), null);
  }

  /**
   * Carries a request out: deletes the account, and — when the admin grants it — anonymises the
   * member's surviving handle snapshots first.
   *
   * <p><b>Irreversible.</b> Both halves happen: the local row and the Keycloak account. See
   * REQ-DATA-008 for exactly what is purged, reassigned and unlinked.
   *
   * <p>{@code grantHistoryErasure} is the admin's answer to the member's wish and is deliberately
   * independent of it — a wish is not an instruction, and the interest in an auditable ledger is
   * weighed case by case ({@code docs/privacy/data-subject-requests.md}).
   *
   * @param id the request to carry out
   * @param request the decision, including whether the history wish is granted
   * @return {@code 204}; the request row no longer exists, having cascaded away with the account
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
      @PathVariable UUID id, @Valid @RequestBody DecideDeletionRequestRequest request) {
    // request.note() is deliberately not read here: an execution has nowhere durable to
    // record a note (see DeletionRequestService#decline). The field stays on the shared
    // request record because a refusal requires it.
    deletionRequestService.execute(id, request.grantHistoryErasure());
    return ResponseEntity.noContent().build();
  }
}
