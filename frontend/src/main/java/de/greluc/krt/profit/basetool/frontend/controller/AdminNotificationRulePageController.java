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

import de.greluc.krt.profit.basetool.frontend.model.dto.NotificationRuleDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.NotificationRuleWriteRequest;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.support.Roles;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Admin page + AJAX relay for the data-driven notification rules (REQ-NOTIF-007). Admin-only; the
 * page lists the rules and the form / selector editor builds the JSON the backend admin API
 * expects, proxied through {@link BackendApiClient}. Backend failures are relayed as {@code
 * application/problem+json} so {@code krtFetch} branches on conflicts exactly as elsewhere.
 */
@Controller
@RequestMapping("/admin/notification-rules")
@RequiredArgsConstructor
@PreAuthorize("hasRole('" + Roles.ADMIN + "')")
@Slf4j
public class AdminNotificationRulePageController {

  private static final String BACKEND_BASE = "/api/v1/notification-rules";
  private static final ParameterizedTypeReference<List<NotificationRuleDto>> LIST_TYPE =
      new ParameterizedTypeReference<>() {};

  private final BackendApiClient backendApiClient;

  /**
   * Renders the rules admin page (list + create/edit form), fail-soft to an empty list.
   *
   * @param model the view model
   * @return the template name
   */
  @GetMapping
  public String page(Model model) {
    try {
      List<NotificationRuleDto> rules = backendApiClient.get(BACKEND_BASE, LIST_TYPE);
      model.addAttribute("rules", rules == null ? List.of() : rules);
    } catch (Exception e) {
      log.debug("Failed to load notification rules", e);
      model.addAttribute("rules", List.of());
      model.addAttribute("error", "admin.notificationRules.error.load");
    }
    // The SPECIFIC_USER selector is now a server-side searchable combobox (remote-users, #1193):
    // the
    // roster is searched on demand via /users/search, and notification-rules.js seeds an existing
    // rule's chosen user in edit mode by resolving its name through /users/{id}. No roster preload.
    return "admin/notification-rules";
  }

  /**
   * Returns one rule as JSON so the edit form can prefill (AJAX).
   *
   * @param id rule id
   * @return the rule, or the relayed backend error
   */
  @ResponseBody
  @GetMapping(value = "/{id}", headers = "X-Requested-With=XMLHttpRequest")
  public ResponseEntity<Object> get(@PathVariable @NotNull UUID id) {
    try {
      return ResponseEntity.ok(
          backendApiClient.get(BACKEND_BASE + "/" + id, NotificationRuleDto.class));
    } catch (BackendServiceException e) {
      return propagateBackendError(e);
    } catch (Exception e) {
      log.error("Load notification rule {} (ajax) failed", id, e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }
  }

  /**
   * Creates a rule (AJAX relay).
   *
   * @param request the create payload
   * @return the created rule, or the relayed backend error
   */
  @ResponseBody
  @PostMapping(headers = "X-Requested-With=XMLHttpRequest")
  public ResponseEntity<Object> create(@RequestBody NotificationRuleWriteRequest request) {
    try {
      return ResponseEntity.ok(
          backendApiClient.post(BACKEND_BASE, request, NotificationRuleDto.class));
    } catch (BackendServiceException e) {
      return propagateBackendError(e);
    } catch (Exception e) {
      log.error("Create notification rule (ajax) failed", e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }
  }

  /**
   * Updates a rule (AJAX relay), forwarding the optimistic-lock version.
   *
   * @param id rule id
   * @param request the update payload (incl. {@code version})
   * @return the updated rule, or the relayed backend error
   */
  @ResponseBody
  @PutMapping(value = "/{id}", headers = "X-Requested-With=XMLHttpRequest")
  public ResponseEntity<Object> update(
      @PathVariable @NotNull UUID id, @RequestBody NotificationRuleWriteRequest request) {
    try {
      return ResponseEntity.ok(
          backendApiClient.put(BACKEND_BASE + "/" + id, request, NotificationRuleDto.class));
    } catch (BackendServiceException e) {
      return propagateBackendError(e);
    } catch (Exception e) {
      log.error("Update notification rule {} (ajax) failed", id, e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }
  }

  /**
   * Deletes a rule (AJAX relay).
   *
   * @param id rule id
   * @return 204 on success, or the relayed backend error
   */
  @ResponseBody
  @DeleteMapping(value = "/{id}", headers = "X-Requested-With=XMLHttpRequest")
  public ResponseEntity<Object> delete(@PathVariable @NotNull UUID id) {
    try {
      backendApiClient.delete(BACKEND_BASE + "/" + id, Void.class);
      return ResponseEntity.noContent().build();
    } catch (BackendServiceException e) {
      return propagateBackendError(e);
    } catch (Exception e) {
      log.error("Delete notification rule {} (ajax) failed", id, e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }
  }
}
