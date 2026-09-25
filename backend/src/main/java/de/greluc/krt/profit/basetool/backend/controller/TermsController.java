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

import de.greluc.krt.profit.basetool.backend.model.dto.TermsStatusDto;
import de.greluc.krt.profit.basetool.backend.service.TermsAcceptanceService;
import de.greluc.krt.profit.basetool.backend.web.CurrentUserId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Terms-of-Use consent endpoints (REQ-SEC-028).
 *
 * <p>Both endpoints are deliberately allowlisted in {@code TermsAcceptanceAccessFilter}: they are
 * the only API a user who has not yet accepted may reach, because refusing them would make the gate
 * impossible to pass. They still require authentication — consent is recorded against a specific
 * account, so an anonymous caller has nothing to record.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/terms")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
@Tag(name = "Terms of Use", description = "Consent to the Terms of Use")
public class TermsController {

  private final TermsAcceptanceService termsAcceptanceService;

  /**
   * Reports whether the caller has accepted the wording currently in force.
   *
   * @param userId the caller's {@code app_user.id}
   * @return the consent status and the version in force, always {@code 200}
   */
  @NotNull
  @GetMapping("/status")
  @PreAuthorize("isAuthenticated()")
  @Operation(
      summary = "Consent status of the calling user",
      description =
          "Reports whether the caller has accepted the Terms-of-Use version currently in force. "
              + "Reachable even while the caller is otherwise blocked by the consent gate.")
  @ApiResponse(responseCode = "200", description = "Consent status")
  public ResponseEntity<TermsStatusDto> getStatus(@CurrentUserId @NotNull UUID userId) {
    return ResponseEntity.ok(
        new TermsStatusDto(
            termsAcceptanceService.hasAcceptedCurrentTerms(userId),
            termsAcceptanceService.currentVersion()));
  }

  /**
   * Records the caller's consent to the Terms-of-Use version currently in force.
   *
   * <p>The accepted version is always the server's, never client-supplied. Service accounts such as
   * the ingest gateway are refused, because a machine cannot consent (ADR-0129).
   *
   * @param userId the caller's {@code app_user.id}
   * @return the resulting consent status, always {@code 200} with {@code accepted = true};
   *     repeating the call is a no-op
   */
  @NotNull
  @PostMapping("/acceptance")
  @PreAuthorize("isAuthenticated() and !hasRole('INGEST_GATEWAY')")
  @Operation(
      summary = "Accept the Terms of Use",
      description =
          "Records the calling user's consent to the version currently in force. Idempotent: "
              + "repeating it neither fails nor adds a second history entry.")
  @ApiResponse(responseCode = "200", description = "Consent recorded (or already present)")
  public ResponseEntity<TermsStatusDto> accept(@CurrentUserId @NotNull UUID userId) {
    termsAcceptanceService.acceptCurrentTerms(userId);
    return ResponseEntity.ok(new TermsStatusDto(true, termsAcceptanceService.currentVersion()));
  }
}
