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
import de.greluc.krt.profit.basetool.backend.model.dto.CreateDeletionRequestRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.DeletionRequestDto;
import de.greluc.krt.profit.basetool.backend.service.DeletionRequestService;
import de.greluc.krt.profit.basetool.backend.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The caller's own side of the Art. 17 erasure request: raise, read and withdraw it (REQ-SEC-061).
 *
 * <p>The subject is always derived from the token; no endpoint accepts a user id. Nothing here
 * deletes anything: the request lands in the admin queue ({@code AdminDeletionRequestController}).
 */
@RestController
@RequestMapping("/api/v1/users/me/deletion-request")
@RequiredArgsConstructor
public class DeletionRequestController {

  private final DeletionRequestService deletionRequestService;
  private final UserService userService;

  /**
   * Returns the caller's most recent erasure request in any state, including a refused one with its
   * reasoning, or {@code 204 No Content} when they have never made one.
   *
   * @param jwt the caller's validated token
   * @return the caller's latest request, or no content
   */
  @GetMapping
  @PreAuthorize("isAuthenticated()")
  @Operation(
      summary = "Read my own account-deletion request",
      description =
          "Returns the caller's latest Art. 17 erasure request - including a refused one, whose "
              + "recorded reason Art. 12(4) requires the requester to be told - or 204 when they "
              + "have never made one. "
              + "The subject is always the caller; no user id can be passed.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "The caller has a pending request"),
    @ApiResponse(responseCode = "204", description = "The caller has no pending request")
  })
  public ResponseEntity<DeletionRequestDto> myRequest(@AuthenticationPrincipal Jwt jwt) {
    return deletionRequestService
        .findLatest(userService.getUserIdFromJwt(jwt))
        .map(r -> ResponseEntity.ok(toDto(r, null)))
        .orElseGet(() -> ResponseEntity.noContent().build());
  }

  /**
   * Raises the caller's erasure request; idempotent, returning the existing open request on a
   * repeat submit.
   *
   * @param jwt the caller's validated token
   * @param request whether the surviving handle snapshots should be anonymised as well
   * @return the caller's open request
   */
  @NotNull
  @PostMapping
  @PreAuthorize("isAuthenticated()")
  @Operation(
      summary = "Request erasure of my own account (Art. 17)",
      description =
          "Raises a request for an admin to decide. Does NOT delete the account: the deletion "
              + "removes the Keycloak account and purges owned data irreversibly, so it is never "
              + "triggered by the member's own click. Idempotent - a second submission returns the "
              + "existing request.")
  public DeletionRequestDto request(
      @AuthenticationPrincipal Jwt jwt,
      @NotNull @Valid @RequestBody CreateDeletionRequestRequest request) {
    DeletionRequest raised =
        deletionRequestService.raise(userService.getUserIdFromJwt(jwt), request.eraseHistory());
    return toDto(raised, null);
  }

  /**
   * Takes the caller's own pending request back.
   *
   * @param jwt the caller's validated token
   * @return {@code 204} whether or not a pending request existed
   */
  @DeleteMapping
  @PreAuthorize("isAuthenticated()")
  @Operation(
      summary = "Withdraw my own account-deletion request",
      description =
          "Takes back a pending request. Idempotent: 204 whether or not one existed. The row is "
              + "kept as WITHDRAWN rather than deleted, because 'asked and changed their mind' is "
              + "a different fact from 'never asked'.")
  public ResponseEntity<Void> withdraw(@AuthenticationPrincipal Jwt jwt) {
    deletionRequestService.withdraw(userService.getUserIdFromJwt(jwt));
    return ResponseEntity.noContent().build();
  }

  /**
   * Maps a deletion request to its wire DTO.
   *
   * @param request the persisted request
   * @param handle the requesting member's handle, or {@code null} to leave it out
   * @return the request DTO
   */
  @NotNull
  static DeletionRequestDto toDto(@NotNull DeletionRequest request, @Nullable String handle) {
    return new DeletionRequestDto(
        request.getId(),
        request.getVersion(),
        request.getUserId(),
        handle,
        request.getStatus(),
        request.isEraseHistoryRequested(),
        request.getCreatedAt(),
        request.getDecidedAt(),
        request.getDecisionNote());
  }
}
