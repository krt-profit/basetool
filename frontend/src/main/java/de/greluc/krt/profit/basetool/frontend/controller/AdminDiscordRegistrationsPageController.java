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

import static de.greluc.krt.profit.basetool.frontend.support.BackendErrorResponses.propagateBackendError;

import de.greluc.krt.profit.basetool.frontend.model.dto.ApproveRegistrationRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.LinkRegistrationRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.MergeAccountRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.PendingRegistrationDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.RejectRegistrationRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.ReopenRegistrationRequest;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.support.Roles;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Admin queue page for Discord registration approvals (epic #720, Track 1). Lists the pending
 * registrations and approves/rejects them in place ({@code krtFetch}, no reload, no native
 * dialogs), round-tripping through the backend {@code /api/v1/admin/registrations} surface.
 * Admin-only — class-level {@code @PreAuthorize("hasRole('ADMIN')")} mirrors the backend gate.
 *
 * <p>Approval grants no Basetool roles — after approval the admin seats roles/units via the
 * existing tooling (Track 1 keeps role assignment manual).
 *
 * <p>The page also renders the rejected registrations and can reopen one back into the queue
 * (REQ-SEC-034), so an erroneous rejection is recoverable from the UI instead of by a manual
 * database write.
 */
@Controller
@RequestMapping("/admin/discord-registrations")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("hasRole('" + Roles.ADMIN + "')")
public class AdminDiscordRegistrationsPageController {

  private static final String BACKEND_BASE = "/api/v1/admin/registrations";

  /** Query suffix selecting the rejected rows off the shared queue endpoint (REQ-SEC-034). */
  private static final String REJECTED_QUERY = "?status=REJECTED";

  /**
   * Response type for the pending-registration queue read. A shared static {@link
   * ParameterizedTypeReference} is behaviourally identical to a fresh anonymous instance per call
   * (Q10).
   */
  private static final ParameterizedTypeReference<List<PendingRegistrationDto>>
      PENDING_REGISTRATION_LIST_TYPE = new ParameterizedTypeReference<>() {};

  private final BackendApiClient backendApiClient;

  /**
   * Renders the pending-registration queue.
   *
   * @param model Thymeleaf model populated with the pending registrations
   * @return the {@code admin/discord-registrations} view name
   */
  @GetMapping
  public String list(Model model) {
    try {
      List<PendingRegistrationDto> registrations =
          backendApiClient.get(BACKEND_BASE, PENDING_REGISTRATION_LIST_TYPE);
      model.addAttribute("registrations", registrations == null ? List.of() : registrations);
    } catch (BackendServiceException e) {
      log.debug("Failed to load the Discord registration queue", e);
      model.addAttribute("error", "error.admin.discordRegistrations.load");
      model.addAttribute("registrations", List.of());
    } catch (Exception e) {
      log.error("Failed to load the Discord registration queue", e);
      model.addAttribute("error", "error.admin.discordRegistrations.load");
      model.addAttribute("registrations", List.of());
    }
    // Read the rejected list under its own guard rather than inside the block above: it is the
    // secondary surface, and a failure there (a backend that predates ?status=, say, during a
    // rolling deploy) must not blank out the pending queue that is this page's primary job.
    model.addAttribute("rejected", loadRejected());
    return "admin/discord-registrations";
  }

  /**
   * Reads the rejected registrations (REQ-SEC-034), degrading to an empty list on any failure so
   * the pending queue still renders.
   *
   * @return the rejected registrations, or an empty list when the read failed
   */
  private List<PendingRegistrationDto> loadRejected() {
    try {
      List<PendingRegistrationDto> rejected =
          backendApiClient.get(BACKEND_BASE + REJECTED_QUERY, PENDING_REGISTRATION_LIST_TYPE);
      return rejected == null ? List.of() : rejected;
    } catch (BackendServiceException e) {
      log.debug("Failed to load the rejected registrations", e);
      return List.of();
    } catch (Exception e) {
      log.error("Failed to load the rejected registrations", e);
      return List.of();
    }
  }

  /**
   * Approves a pending registration in place (krtFetch). Relays a backend conflict as {@code
   * problem+json} so the client surfaces the reload-confirm instead of silently overwriting.
   *
   * @param id the registration to approve
   * @param body the JSON-bound optimistic-lock version
   * @return the updated registration on success, the relayed backend status on conflict/failure
   */
  @ResponseBody
  @PostMapping(value = "/{id}/approve", headers = "X-Requested-With=XMLHttpRequest")
  public ResponseEntity<Object> approveAjax(
      @PathVariable @NotNull UUID id,
      @Nullable @RequestBody(required = false) ApproveRegistrationRequest body) {
    try {
      return ResponseEntity.ok(
          backendApiClient.post(
              BACKEND_BASE + "/" + id + "/approve", body, PendingRegistrationDto.class));
    } catch (BackendServiceException e) {
      log.debug("Approve registration {} failed", id, e);
      return propagateBackendError(e);
    } catch (Exception e) {
      log.error("Approve registration {} failed", id, e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }
  }

  /**
   * Rejects a pending registration in place (krtFetch), carrying the optional reason.
   *
   * @param id the registration to reject
   * @param body the JSON-bound reason + optimistic-lock version
   * @return the updated registration on success, the relayed backend status on conflict/failure
   */
  @ResponseBody
  @PostMapping(value = "/{id}/reject", headers = "X-Requested-With=XMLHttpRequest")
  public ResponseEntity<Object> rejectAjax(
      @PathVariable @NotNull UUID id,
      @Nullable @RequestBody(required = false) RejectRegistrationRequest body) {
    try {
      return ResponseEntity.ok(
          backendApiClient.post(
              BACKEND_BASE + "/" + id + "/reject", body, PendingRegistrationDto.class));
    } catch (BackendServiceException e) {
      log.debug("Reject registration {} failed", id, e);
      return propagateBackendError(e);
    } catch (Exception e) {
      log.error("Reject registration {} failed", id, e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }
  }

  /**
   * Reopens a rejected registration in place (krtFetch): the backend moves it back to {@code
   * PENDING} and the row migrates from the rejected table into the queue without a reload
   * (REQ-SEC-034).
   *
   * @param id the rejected registration to reopen
   * @param body the JSON-bound note + optimistic-lock version
   * @return the now-pending registration on success, the relayed backend status on conflict/failure
   */
  @ResponseBody
  @PostMapping(value = "/{id}/reopen", headers = "X-Requested-With=XMLHttpRequest")
  public ResponseEntity<Object> reopenAjax(
      @PathVariable @NotNull UUID id,
      @Nullable @RequestBody(required = false) ReopenRegistrationRequest body) {
    try {
      return ResponseEntity.ok(
          backendApiClient.post(
              BACKEND_BASE + "/" + id + "/reopen", body, PendingRegistrationDto.class));
    } catch (BackendServiceException e) {
      log.debug("Reopen registration {} failed", id, e);
      return propagateBackendError(e);
    } catch (Exception e) {
      log.error("Reopen registration {} failed", id, e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }
  }

  /**
   * Relays the account merge (REQ-SEC-045): move an older account's own data onto this
   * registration.
   *
   * <p>The remedy for the queue's duplicate-callsign marker. Unlike the link relay beside it, a
   * successful merge does <b>not</b> retire the row: the registration still has to be approved, and
   * folding the two decisions together would make repairing the data imply admitting the member.
   *
   * @param id the surviving registration
   * @param body the JSON-bound source account id + optimistic-lock version
   * @return the surviving account on success, the relayed backend status on conflict/failure
   */
  @ResponseBody
  @PostMapping(value = "/{id}/merge", headers = "X-Requested-With=XMLHttpRequest")
  public ResponseEntity<Object> mergeAjax(
      @PathVariable @NotNull UUID id,
      @Nullable @RequestBody(required = false) MergeAccountRequest body) {
    try {
      return ResponseEntity.ok(
          backendApiClient.post(
              BACKEND_BASE + "/" + id + "/merge", body, PendingRegistrationDto.class));
    } catch (BackendServiceException e) {
      log.debug("Merge into registration {} failed", id, e);
      return propagateBackendError(e);
    } catch (Exception e) {
      log.error("Merge into registration {} failed", id, e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }
  }

  /**
   * Links a pending Discord registration onto an existing account in place (krtFetch), relaying a
   * backend conflict as {@code problem+json} so the client surfaces the reload-confirm instead of
   * silently overwriting (REQ-SEC-026).
   *
   * @param id the registration to link away
   * @param body the JSON-bound target account id + optimistic-lock version
   * @return the surviving account on success, the relayed backend status on conflict/failure
   */
  @ResponseBody
  @PostMapping(value = "/{id}/link", headers = "X-Requested-With=XMLHttpRequest")
  public ResponseEntity<Object> linkAjax(
      @PathVariable @NotNull UUID id,
      @Nullable @RequestBody(required = false) LinkRegistrationRequest body) {
    try {
      return ResponseEntity.ok(
          backendApiClient.post(
              BACKEND_BASE + "/" + id + "/link", body, PendingRegistrationDto.class));
    } catch (BackendServiceException e) {
      log.debug("Link registration {} failed", id, e);
      return propagateBackendError(e);
    } catch (Exception e) {
      log.error("Link registration {} failed", id, e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }
  }
}
