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
import de.greluc.krt.profit.basetool.frontend.model.dto.PendingRegistrationDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.RejectRegistrationRequest;
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
 */
@Controller
@RequestMapping("/admin/discord-registrations")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("hasRole('" + Roles.ADMIN + "')")
public class AdminDiscordRegistrationsPageController {

  private static final String BACKEND_BASE = "/api/v1/admin/registrations";

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
    return "admin/discord-registrations";
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
}
