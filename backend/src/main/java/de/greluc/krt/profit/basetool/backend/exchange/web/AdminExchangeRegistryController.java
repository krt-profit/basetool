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

import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeClientCreateRequest;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeClientDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeClientMapper;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeClientStatusRequest;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeClientUpdateRequest;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeClientUsageDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeRegistryService;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeSettingsDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeSettingsUpdateRequest;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The exchange client registry and the global exchange switch, {@code ADMIN} only (REQ-XCH-003).
 */
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
@PreAuthorize(Roles.HAS_ROLE_ADMIN)
@Tag(name = "Admin — Exchange registry", description = "Approved third-party exchange clients")
public class AdminExchangeRegistryController {

  private final ExchangeRegistryService registryService;
  private final ExchangeClientMapper mapper;

  /**
   * Lists every registry client.
   *
   * @return all clients, ordered by client id
   */
  @NotNull
  @GetMapping("/exchange-clients")
  @Operation(summary = "List exchange clients")
  @ApiResponse(responseCode = "200", description = "Every registry client")
  public ResponseEntity<List<ExchangeClientDto>> listClients() {
    return ResponseEntity.ok(registryService.listClients().stream().map(mapper::toDto).toList());
  }

  /**
   * Reports how widely each client is in use.
   *
   * @return connected members and last activity per client in use; unused clients have no row
   */
  @NotNull
  @GetMapping("/exchange-clients/usage")
  @Operation(
      summary = "Exchange client usage",
      description = "Connected members and last activity per client, over live installations.")
  @ApiResponse(responseCode = "200", description = "One row per client in use")
  public ResponseEntity<List<ExchangeClientUsageDto>> usage() {
    return ResponseEntity.ok(registryService.usage());
  }

  /**
   * Loads one registry client.
   *
   * @param id the registry id
   * @return the client
   */
  @NotNull
  @GetMapping("/exchange-clients/{id}")
  @Operation(summary = "Get an exchange client")
  @ApiResponse(responseCode = "200", description = "The client")
  @ApiResponse(responseCode = "404", description = "No such client")
  public ResponseEntity<ExchangeClientDto> getClient(@PathVariable @NotNull UUID id) {
    return ResponseEntity.ok(mapper.toDto(registryService.getClient(id)));
  }

  /**
   * Registers a client as active.
   *
   * @param request the client
   * @return the saved client
   */
  @NotNull
  @PostMapping("/exchange-clients")
  @Operation(
      summary = "Register an exchange client",
      description = "Registers an approved client as ACTIVE; exchange.connect is required.")
  @ApiResponse(responseCode = "201", description = "The registered client")
  @ApiResponse(responseCode = "409", description = "The client id is already registered")
  public ResponseEntity<ExchangeClientDto> createClient(
      @RequestBody @Valid @NotNull ExchangeClientCreateRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(mapper.toDto(registryService.createClient(request)));
  }

  /**
   * Replaces a client's editable fields.
   *
   * @param id the registry id
   * @param request the new values and the version last seen
   * @return the saved client
   */
  @NotNull
  @PutMapping("/exchange-clients/{id}")
  @Operation(
      summary = "Update an exchange client",
      description =
          "Replaces display name, capabilities, version floor, contact and limit overrides. A "
              + "removed capability is mirrored before the commit; if that fails, nothing changes.")
  @ApiResponse(responseCode = "200", description = "The saved client")
  @ApiResponse(responseCode = "409", description = "Stale version")
  @ApiResponse(responseCode = "502", description = "The registry mirror could not be written")
  public ResponseEntity<ExchangeClientDto> updateClient(
      @PathVariable @NotNull UUID id,
      @RequestBody @Valid @NotNull ExchangeClientUpdateRequest request) {
    return ResponseEntity.ok(mapper.toDto(registryService.updateClient(id, request)));
  }

  /**
   * Suspends a client.
   *
   * @param id the registry id
   * @param request the version last seen
   * @return the saved client
   */
  @NotNull
  @PostMapping("/exchange-clients/{id}/suspend")
  @Operation(
      summary = "Suspend an exchange client",
      description = "The suspension reaches the gateway's mirror before the commit or not at all.")
  @ApiResponse(responseCode = "200", description = "The suspended client")
  @ApiResponse(responseCode = "409", description = "Stale version")
  @ApiResponse(responseCode = "502", description = "The registry mirror could not be written")
  public ResponseEntity<ExchangeClientDto> suspendClient(
      @PathVariable @NotNull UUID id,
      @RequestBody @Valid @NotNull ExchangeClientStatusRequest request) {
    return ResponseEntity.ok(mapper.toDto(registryService.suspendClient(id, request.version())));
  }

  /**
   * Activates a suspended client.
   *
   * @param id the registry id
   * @param request the version last seen
   * @return the saved client
   */
  @NotNull
  @PostMapping("/exchange-clients/{id}/activate")
  @Operation(summary = "Activate an exchange client")
  @ApiResponse(responseCode = "200", description = "The active client")
  @ApiResponse(responseCode = "409", description = "Stale version")
  public ResponseEntity<ExchangeClientDto> activateClient(
      @PathVariable @NotNull UUID id,
      @RequestBody @Valid @NotNull ExchangeClientStatusRequest request) {
    return ResponseEntity.ok(mapper.toDto(registryService.activateClient(id, request.version())));
  }

  /**
   * Reads the global exchange switch.
   *
   * @return the switch
   */
  @NotNull
  @GetMapping("/exchange-settings")
  @Operation(summary = "Get the global exchange switch")
  @ApiResponse(responseCode = "200", description = "The switch")
  public ResponseEntity<ExchangeSettingsDto> getSettings() {
    return ResponseEntity.ok(mapper.toDto(registryService.getSettings()));
  }

  /**
   * Turns the global exchange switch on or off.
   *
   * @param request the new state and the version last seen
   * @return the saved switch
   */
  @NotNull
  @PutMapping("/exchange-settings")
  @Operation(
      summary = "Set the global exchange switch",
      description = "Switching off reaches the gateway's mirror before the commit or not at all.")
  @ApiResponse(responseCode = "200", description = "The saved switch")
  @ApiResponse(responseCode = "409", description = "Stale version")
  @ApiResponse(responseCode = "502", description = "The registry mirror could not be written")
  public ResponseEntity<ExchangeSettingsDto> updateSettings(
      @RequestBody @Valid @NotNull ExchangeSettingsUpdateRequest request) {
    return ResponseEntity.ok(
        mapper.toDto(registryService.updateSettings(request.enabled(), request.version())));
  }
}
