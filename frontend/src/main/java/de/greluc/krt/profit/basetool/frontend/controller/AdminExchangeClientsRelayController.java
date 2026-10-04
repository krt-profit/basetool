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

package de.greluc.krt.profit.basetool.frontend.controller;

import static de.greluc.krt.profit.basetool.frontend.support.BackendErrorResponses.relay;

import de.greluc.krt.profit.basetool.frontend.model.dto.ExchangeBulkUndoInstallationDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.ExchangeBulkUndoPreviewDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.ExchangeBulkUndoRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.ExchangeBulkUndoRunDetailDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.ExchangeBulkUndoRunDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.ExchangeClientCreateRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.ExchangeClientDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.ExchangeClientStatusRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.ExchangeClientUpdateRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.ExchangeSettingsDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.ExchangeSettingsUpdateRequest;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.support.Roles;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AJAX relay of the exchange registry page to the backend's admin registry and bulk undo
 * (REQ-XCH-003, REQ-XCH-034); a backend failure is relayed as {@code application/problem+json}.
 */
@RestController
@RequestMapping("/admin/exchange-clients")
@RequiredArgsConstructor
@PreAuthorize("hasRole('" + Roles.ADMIN + "')")
@Slf4j
public class AdminExchangeClientsRelayController {

  /** The backend's registry endpoints. */
  static final String CLIENTS = "/api/v1/admin/exchange-clients";

  /** The backend's global switch endpoint. */
  static final String SETTINGS = "/api/v1/admin/exchange-settings";

  /** The backend's bulk undo runs. */
  static final String UNDO_RUNS = "/api/v1/admin/exchange-undo-runs";

  private static final ParameterizedTypeReference<List<ExchangeBulkUndoInstallationDto>>
      INSTALLATIONS_TYPE = new ParameterizedTypeReference<>() {};

  /** Talks to the backend. */
  private final BackendApiClient backendApiClient;

  /**
   * Returns one client so the edit form can prefill.
   *
   * @param id the registry id
   * @return the client, or the relayed backend error
   */
  @GetMapping(value = "/{id}", headers = "X-Requested-With=XMLHttpRequest")
  public ResponseEntity<Object> get(@PathVariable @NotNull UUID id) {
    return relay(
        log,
        "load exchange client " + id + " (ajax)",
        () -> ResponseEntity.ok(backendApiClient.get(CLIENTS + "/" + id, ExchangeClientDto.class)));
  }

  /**
   * Registers a client.
   *
   * @param request the new client
   * @return the registered client, or the relayed backend error
   */
  @PostMapping(headers = "X-Requested-With=XMLHttpRequest")
  public ResponseEntity<Object> create(@RequestBody @NotNull ExchangeClientCreateRequest request) {
    return relay(
        log,
        "register exchange client (ajax)",
        () -> ResponseEntity.ok(backendApiClient.post(CLIENTS, request, ExchangeClientDto.class)));
  }

  /**
   * Edits a client, forwarding the optimistic-lock version.
   *
   * @param id the registry id
   * @param request the edited client
   * @return the edited client, or the relayed backend error
   */
  @PutMapping(value = "/{id}", headers = "X-Requested-With=XMLHttpRequest")
  public ResponseEntity<Object> update(
      @PathVariable @NotNull UUID id, @RequestBody @NotNull ExchangeClientUpdateRequest request) {
    return relay(
        log,
        "edit exchange client " + id + " (ajax)",
        () ->
            ResponseEntity.ok(
                backendApiClient.put(CLIENTS + "/{id}", request, ExchangeClientDto.class, id)));
  }

  /**
   * Suspends a client, which the gateway refuses from then on.
   *
   * @param id the registry id
   * @param request the version the admin last saw
   * @return the suspended client, or the relayed backend error
   */
  @PostMapping(value = "/{id}/suspend", headers = "X-Requested-With=XMLHttpRequest")
  public ResponseEntity<Object> suspend(
      @PathVariable @NotNull UUID id, @RequestBody @NotNull ExchangeClientStatusRequest request) {
    return relay(
        log,
        "suspend exchange client " + id + " (ajax)",
        () ->
            ResponseEntity.ok(
                backendApiClient.post(
                    CLIENTS + "/{id}/suspend", request, ExchangeClientDto.class, id)));
  }

  /**
   * Activates a suspended client.
   *
   * @param id the registry id
   * @param request the version the admin last saw
   * @return the activated client, or the relayed backend error
   */
  @PostMapping(value = "/{id}/activate", headers = "X-Requested-With=XMLHttpRequest")
  public ResponseEntity<Object> activate(
      @PathVariable @NotNull UUID id, @RequestBody @NotNull ExchangeClientStatusRequest request) {
    return relay(
        log,
        "activate exchange client " + id + " (ajax)",
        () ->
            ResponseEntity.ok(
                backendApiClient.post(
                    CLIENTS + "/{id}/activate", request, ExchangeClientDto.class, id)));
  }

  /**
   * Turns the global exchange switch on or off.
   *
   * @param request the new state and the version the admin last saw
   * @return the switch, or the relayed backend error
   */
  @PutMapping(value = "/settings", headers = "X-Requested-With=XMLHttpRequest")
  public ResponseEntity<Object> updateSettings(
      @RequestBody @NotNull ExchangeSettingsUpdateRequest request) {
    return relay(
        log,
        "switch the exchange (ajax)",
        () ->
            ResponseEntity.ok(backendApiClient.put(SETTINGS, request, ExchangeSettingsDto.class)));
  }

  /**
   * Counts the members and entries a bulk undo of the client would reach; writes nothing.
   *
   * @param id the registry id
   * @param request the scope
   * @return the counts, or the relayed backend error
   */
  @PostMapping(value = "/{id}/undo/preview", headers = "X-Requested-With=XMLHttpRequest")
  public ResponseEntity<Object> previewUndo(
      @PathVariable @NotNull UUID id, @RequestBody @NotNull ExchangeBulkUndoRequest request) {
    return relay(
        log,
        "preview bulk undo of exchange client " + id + " (ajax)",
        () ->
            ResponseEntity.ok(
                backendApiClient.post(
                    CLIENTS + "/{id}/undo/preview",
                    request,
                    ExchangeBulkUndoPreviewDto.class,
                    id)));
  }

  /**
   * Suspends the client and starts undoing its writes for every member.
   *
   * @param id the registry id
   * @param request the scope
   * @return the started run, or the relayed backend error
   */
  @PostMapping(value = "/{id}/undo", headers = "X-Requested-With=XMLHttpRequest")
  public ResponseEntity<Object> startUndo(
      @PathVariable @NotNull UUID id, @RequestBody @NotNull ExchangeBulkUndoRequest request) {
    return relay(
        log,
        "start bulk undo of exchange client " + id + " (ajax)",
        () ->
            ResponseEntity.ok(
                backendApiClient.post(
                    CLIENTS + "/{id}/undo", request, ExchangeBulkUndoRunDto.class, id)));
  }

  /**
   * Lists the client's installations a bulk undo could be limited to.
   *
   * @param id the registry id
   * @param since the start of the span
   * @return the installations, or the relayed backend error
   */
  @GetMapping(value = "/{id}/undo/installations", headers = "X-Requested-With=XMLHttpRequest")
  public ResponseEntity<Object> undoInstallations(
      @PathVariable @NotNull UUID id, @RequestParam @NotNull Instant since) {
    return relay(
        log,
        "list installations for a bulk undo of exchange client " + id + " (ajax)",
        () ->
            ResponseEntity.ok(
                backendApiClient.get(
                    CLIENTS + "/" + id + "/undo/installations?since={since}",
                    INSTALLATIONS_TYPE,
                    since.toString())));
  }

  /**
   * Loads one bulk undo run with the entries it left alone.
   *
   * @param runId the run
   * @return the run, or the relayed backend error
   */
  @GetMapping(value = "/undo-runs/{runId}", headers = "X-Requested-With=XMLHttpRequest")
  public ResponseEntity<Object> undoRun(@PathVariable @NotNull UUID runId) {
    return relay(
        log,
        "load bulk undo run " + runId + " (ajax)",
        () ->
            ResponseEntity.ok(
                backendApiClient.get(UNDO_RUNS + "/" + runId, ExchangeBulkUndoRunDetailDto.class)));
  }
}
