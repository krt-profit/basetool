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

package de.greluc.krt.profit.basetool.backend.exchange.web;

import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeNameWithholdings;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthenticatedSubject;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Whether the calling member withholds their name from connected applications (REQ-XCH-044), the
 * profile page's toggle. Reachable only from the member's own browser session, so a connected
 * application can neither read nor change it.
 */
@RestController
@RequestMapping("/api/v1/connected-apps/name-withholding")
@RequiredArgsConstructor
@PreAuthorize("@connectedAppsGate.isMemberSession(authentication)")
@Tag(name = "Connected apps", description = "The member's own external client connections")
public class ExchangeNameWithholdingController {

  private final ExchangeNameWithholdings withholdings;

  /**
   * Returns whether the caller withholds their name, with the setting's own version.
   *
   * @param authentication the caller
   * @return the stored choice; off for a member who never chose
   */
  @NotNull
  @GetMapping
  @Operation(summary = "Read whether my name is withheld from connected apps")
  @ApiResponse(responseCode = "200", description = "The stored choice and its version")
  public NameWithholdingResponse getNameWithholding(@NotNull Authentication authentication) {
    return NameWithholdingResponse.of(withholdings.snapshotOf(member(authentication)));
  }

  /**
   * Sets whether the caller withholds their name, guarded by the setting's version.
   *
   * @param request the new choice and the version the caller last read
   * @param authentication the caller
   * @return the persisted choice and the new version
   */
  @NotNull
  @PutMapping
  @Operation(
      summary = "Withhold my name from connected apps, or share it again",
      description = "Takes effect on a connected application's next request.")
  @ApiResponse(responseCode = "200", description = "The persisted choice and its new version")
  @ApiResponse(responseCode = "409", description = "The version is stale")
  public NameWithholdingResponse updateNameWithholding(
      @NotNull @Valid @RequestBody NameWithholdingRequest request,
      @NotNull Authentication authentication) {
    return NameWithholdingResponse.of(
        withholdings.update(member(authentication), request.withholdName(), request.version()));
  }

  /**
   * Returns the caller's user id.
   *
   * @param authentication the caller
   * @return the id
   * @throws AccessDeniedException when the caller has none
   */
  @NotNull
  private static UUID member(@NotNull Authentication authentication) {
    return AuthenticatedSubject.idOf(authentication)
        .orElseThrow(() -> new AccessDeniedException("No member"));
  }

  /**
   * The member's choice as the profile toggle reads it.
   *
   * @param withholdName whether the member withholds their name from connected applications
   * @param version the setting's own optimistic-lock version, 0 while never saved
   */
  public record NameWithholdingResponse(boolean withholdName, long version) {

    /**
     * Maps a service snapshot.
     *
     * @param snapshot the stored choice
     * @return the response
     */
    static @NotNull NameWithholdingResponse of(
        @NotNull ExchangeNameWithholdings.Snapshot snapshot) {
      return new NameWithholdingResponse(snapshot.withholdName(), snapshot.version());
    }
  }

  /**
   * The member's new choice and the version they last read.
   *
   * @param withholdName the new choice; never {@code null}
   * @param version the version the caller last read; never {@code null}
   */
  public record NameWithholdingRequest(
      @jakarta.validation.constraints.NotNull Boolean withholdName,
      @jakarta.validation.constraints.NotNull Long version) {}
}
