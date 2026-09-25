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

import de.greluc.krt.profit.basetool.frontend.config.UsesLayoutModel;
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
 * Admin-only queue page for Discord registration approvals: lists pending registrations and
 * approves, rejects, links or merges them in place via {@code /api/v1/admin/registrations}.
 *
 * <p>Approval grants no Basetool roles. Rejected registrations are listed too and can be reopened
 * (REQ-SEC-034).
 */
@Controller
@UsesLayoutModel
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
  @NotNull
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
   * Approves a pending registration in place; a backend conflict is relayed as {@code
   * problem+json}.
   *
   * @param id the registration to approve
   * @param body the optimistic-lock version
   * @return the updated registration, or the relayed backend status on conflict or failure
   */
  @ResponseBody
  @PostMapping(value = "/{id}/approve", headers = "X-Requested-With=XMLHttpRequest")
  public ResponseEntity<Object> approveAjax(
      @PathVariable @NotNull UUID id,
      @Nullable @RequestBody(required = false) ApproveRegistrationRequest body) {
    return relay(
        log,
        "approve registration " + id,
        () -> {
          return ResponseEntity.ok(
              backendApiClient.post(
                  BACKEND_BASE + "/" + id + "/approve", body, PendingRegistrationDto.class));
        });
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
    return relay(
        log,
        "reject registration " + id,
        () -> {
          return ResponseEntity.ok(
              backendApiClient.post(
                  BACKEND_BASE + "/" + id + "/reject", body, PendingRegistrationDto.class));
        });
  }

  /**
   * Reopens a rejected registration in place, moving it back to {@code PENDING} (REQ-SEC-034).
   *
   * @param id the rejected registration to reopen
   * @param body the note and optimistic-lock version
   * @return the now-pending registration, or the relayed backend status on conflict or failure
   */
  @ResponseBody
  @PostMapping(value = "/{id}/reopen", headers = "X-Requested-With=XMLHttpRequest")
  public ResponseEntity<Object> reopenAjax(
      @PathVariable @NotNull UUID id,
      @Nullable @RequestBody(required = false) ReopenRegistrationRequest body) {
    return relay(
        log,
        "reopen registration " + id,
        () -> {
          return ResponseEntity.ok(
              backendApiClient.post(
                  BACKEND_BASE + "/" + id + "/reopen", body, PendingRegistrationDto.class));
        });
  }

  /**
   * Relays the account merge (REQ-SEC-045), moving an older account's data onto this registration.
   * The registration stays in the queue and still needs approval.
   *
   * @param id the surviving registration
   * @param body the source account id and optimistic-lock version
   * @return the surviving account, or the relayed backend status on conflict or failure
   */
  @ResponseBody
  @PostMapping(value = "/{id}/merge", headers = "X-Requested-With=XMLHttpRequest")
  public ResponseEntity<Object> mergeAjax(
      @PathVariable @NotNull UUID id,
      @Nullable @RequestBody(required = false) MergeAccountRequest body) {
    return relay(
        log,
        "merge into registration " + id,
        () -> {
          return ResponseEntity.ok(
              backendApiClient.post(
                  BACKEND_BASE + "/" + id + "/merge", body, PendingRegistrationDto.class));
        });
  }

  /**
   * Links a pending registration onto an existing account in place (REQ-SEC-026); a backend
   * conflict is relayed as {@code problem+json}.
   *
   * @param id the registration to link away
   * @param body the target account id and optimistic-lock version
   * @return the surviving account, or the relayed backend status on conflict or failure
   */
  @ResponseBody
  @PostMapping(value = "/{id}/link", headers = "X-Requested-With=XMLHttpRequest")
  public ResponseEntity<Object> linkAjax(
      @PathVariable @NotNull UUID id,
      @Nullable @RequestBody(required = false) LinkRegistrationRequest body) {
    return relay(
        log,
        "link registration " + id,
        () -> {
          return ResponseEntity.ok(
              backendApiClient.post(
                  BACKEND_BASE + "/" + id + "/link", body, PendingRegistrationDto.class));
        });
  }
}
