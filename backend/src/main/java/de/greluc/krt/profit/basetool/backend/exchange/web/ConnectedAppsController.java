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

import de.greluc.krt.profit.basetool.backend.exchange.internal.ConnectedAppDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ConnectedAppMassChangeRequestDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ConnectedAppMassChangeResultDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ConnectedAppsService;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeMassChangeService;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeRevocationSecond;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeUndoRequestDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeUndoResultDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeUndoService;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthenticatedSubject;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The member's own exchange connections, reachable only from the member's browser session
 * (REQ-XCH-001, REQ-XCH-008, REQ-XCH-032).
 */
@RestController
@RequestMapping("/api/v1/connected-apps")
@RequiredArgsConstructor
@PreAuthorize("@connectedAppsGate.isMemberSession(authentication)")
@Tag(name = "Connected apps", description = "The member's own external client connections")
public class ConnectedAppsController {

  private final ConnectedAppsService connectedAppsService;
  private final ExchangeUndoService undoService;
  private final ExchangeMassChangeService massChangeService;
  private final ExchangeRevocationSecond revocationSecond;

  /**
   * Shows what a change set the guard held back would do, writing nothing.
   *
   * @param request the staged change set the page consumed
   * @param authentication the caller
   * @return what it would apply
   */
  @NotNull
  @PostMapping("/mass-changes/preview")
  @Operation(
      summary = "Preview a held-back change set",
      description =
          "Checks the client and installation again and plans the staged change set without"
              + " writing.")
  @ApiResponse(responseCode = "200", description = "What it would apply")
  @ApiResponse(responseCode = "403", description = "The client may no longer write it")
  @ApiResponse(responseCode = "404", description = "The client is not registered")
  public ResponseEntity<ConnectedAppMassChangeResultDto> previewMassChange(
      @NotNull @Valid @RequestBody ConnectedAppMassChangeRequestDto request,
      @NotNull Authentication authentication) {
    return ResponseEntity.ok(massChangeService.preview(member(authentication), request));
  }

  /**
   * Applies a change set the guard held back, which the member confirmed.
   *
   * @param request the staged change set the page consumed
   * @param authentication the caller
   * @return what it applied
   */
  @NotNull
  @PostMapping("/mass-changes/confirm")
  @Operation(
      summary = "Confirm a held-back change set",
      description =
          "Checks the client and installation again and applies the staged change set as the"
              + " client's own write, without asking the mass-change guard again.")
  @ApiResponse(responseCode = "200", description = "What it applied")
  @ApiResponse(responseCode = "403", description = "The client may no longer write it")
  @ApiResponse(responseCode = "404", description = "The client is not registered")
  public ResponseEntity<ConnectedAppMassChangeResultDto> confirmMassChange(
      @NotNull @Valid @RequestBody ConnectedAppMassChangeRequestDto request,
      @NotNull Authentication authentication) {
    return ResponseEntity.ok(massChangeService.confirm(member(authentication), request));
  }

  /**
   * Undoes a client's writes to the caller's blueprints, stock and ships since a point in time.
   *
   * @param clientId the Keycloak client id
   * @param request the point in time
   * @param authentication the caller
   * @return how many entries were restored, and the ones left alone
   */
  @NotNull
  @PostMapping("/{clientId}/undo")
  @Operation(
      summary = "Undo a client's changes",
      description =
          "Sets every entry the client changed since the point in time back to its earlier state,"
              + " unless it was changed afterwards; Materialbörse offers are not restored.")
  @ApiResponse(responseCode = "200", description = "The entries restored and skipped")
  @ApiResponse(responseCode = "404", description = "The client is not registered")
  public ResponseEntity<ExchangeUndoResultDto> undo(
      @PathVariable @NotNull String clientId,
      @NotNull @Valid @RequestBody ExchangeUndoRequestDto request,
      @NotNull Authentication authentication) {
    return ResponseEntity.ok(undoService.undo(member(authentication), clientId, request.since()));
  }

  /**
   * Lists the caller's connected clients and their installations.
   *
   * @param authentication the caller
   * @return the connected clients
   */
  @NotNull
  @GetMapping
  @Operation(summary = "List my connected apps")
  @ApiResponse(responseCode = "200", description = "The connected clients")
  public ResponseEntity<List<ConnectedAppDto>> list(@NotNull Authentication authentication) {
    return ResponseEntity.ok(connectedAppsService.list(member(authentication)));
  }

  /**
   * Marks one of the caller's new installations seen, ending its highlight on the page.
   *
   * @param installationId the installation the caller acknowledged
   * @param authentication the caller
   * @return {@code 204}, also when nothing was unseen
   */
  @NotNull
  @PostMapping("/installations/{installationId}/seen")
  @Operation(
      summary = "Mark a new installation seen",
      description =
          "Marks the installation's new-connection notification read; changes no connection.")
  @ApiResponse(responseCode = "204", description = "Marked")
  public ResponseEntity<Void> markSeen(
      @PathVariable @NotNull UUID installationId, @NotNull Authentication authentication) {
    connectedAppsService.markSeen(member(authentication), installationId);
    return ResponseEntity.noContent().build();
  }

  /**
   * Disconnects a whole client for the caller and answers only once the clock has left the
   * revocation's second, so a connection the member starts afterwards is not refused (REQ-XCH-008).
   *
   * @param clientId the Keycloak client id
   * @param authentication the caller
   * @return {@code 204}
   */
  @NotNull
  @DeleteMapping("/{clientId}")
  @Operation(
      summary = "Disconnect a client",
      description =
          "Refuses every token the client holds for me from the next request on and removes my"
              + " consent; a new connection works at once.")
  @ApiResponse(responseCode = "204", description = "Disconnected")
  @ApiResponse(responseCode = "502", description = "The gateway mirror or Keycloak was unreachable")
  public ResponseEntity<Void> disconnectClient(
      @PathVariable @NotNull String clientId, @NotNull Authentication authentication) {
    revocationSecond.awaitSecondAfter(
        connectedAppsService.disconnectClient(member(authentication), clientId));
    return ResponseEntity.noContent().build();
  }

  /**
   * Disconnects one of the caller's installations.
   *
   * @param installationId the installation
   * @param authentication the caller
   * @return {@code 204}
   */
  @NotNull
  @DeleteMapping("/installations/{installationId}")
  @Operation(
      summary = "Disconnect an installation",
      description =
          "Refuses every token bound to that installation's key; reconnecting needs a new key.")
  @ApiResponse(responseCode = "204", description = "Disconnected")
  @ApiResponse(responseCode = "404", description = "Not one of my installations")
  @ApiResponse(responseCode = "502", description = "The gateway mirror was unreachable")
  public ResponseEntity<Void> disconnectInstallation(
      @PathVariable @NotNull UUID installationId, @NotNull Authentication authentication) {
    connectedAppsService.disconnectInstallation(member(authentication), installationId);
    return ResponseEntity.noContent().build();
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
}
