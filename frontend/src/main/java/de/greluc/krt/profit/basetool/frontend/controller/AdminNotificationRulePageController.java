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
import de.greluc.krt.profit.basetool.frontend.model.dto.NotificationRuleDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.NotificationRuleWriteRequest;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.support.Roles;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Admin page + AJAX relay for the data-driven notification rules (REQ-NOTIF-007). Admin-only; the
 * page lists the rules and the form / selector editor builds the JSON the backend admin API
 * expects, proxied through {@link BackendApiClient}. Backend failures are relayed as {@code
 * application/problem+json} so {@code krtFetch} branches on conflicts exactly as elsewhere. After a
 * successful write the page re-fetches the {@code rules} table fragment in place instead of
 * reloading (REQ-FE-001).
 */
@Controller
@UsesLayoutModel
@RequestMapping("/admin/notification-rules")
@RequiredArgsConstructor
@PreAuthorize("hasRole('" + Roles.ADMIN + "')")
@Slf4j
public class AdminNotificationRulePageController {

  private static final String BACKEND_BASE = "/api/v1/notification-rules";
  private static final ParameterizedTypeReference<List<NotificationRuleDto>> LIST_TYPE =
      new ParameterizedTypeReference<>() {};

  /** The {@code fragment} value that renders only the rules table, for the in-place swap. */
  static final String RULES_FRAGMENT = "rules";

  // The option lists the editor offers, in the backend enums' declaration order. The frontend holds
  // no copy of the backend enums (its DTOs carry them as @BackendEnumAsString strings), so these
  // lists are the single source the template renders every <option> from, each labelled through
  // `<prefix>.<CODE>`. AdminNotificationRulePageControllerTest pins them against the enum values
  // in the committed openapi.json, so a backend enum that gains a value fails the build here
  // instead of leaving a rule the editor cannot represent.

  /**
   * {@code NotificationEventType} codes, labelled via {@code admin.notificationRules.eventType}.
   */
  static final List<String> EVENT_TYPES =
      List.of(
          "JOB_ORDER_CREATED",
          "JOB_ORDER_UPDATED_BY_REQUESTER",
          "BANK_BOOKING_REQUEST_CREATED",
          "BANK_BOOKING_REQUEST_CONFIRMED",
          "BANK_BOOKING_REQUEST_REJECTED",
          "BANK_BOOKING_REQUEST_CANCELLED",
          "DISCORD_REGISTRATION_PENDING",
          "MATERIAL_EXCHANGE_INTEREST_REGISTERED",
          "MATERIAL_REQUEST_FULFILLMENT_SIGNALLED",
          "ACCOUNT_DELETION_REQUESTED",
          "ACCOUNT_DELETION_REQUEST_DECLINED",
          "ACCOUNT_DELETION_REQUEST_RESOLVED");

  /**
   * {@code NotificationType} codes, labelled via {@code admin.notificationRules.notificationType}.
   */
  static final List<String> NOTIFICATION_TYPES =
      List.of(
          "JOB_ORDER_CREATED",
          "JOB_ORDER_UPDATED_BY_REQUESTER",
          "BANK_BOOKING_REQUEST_CREATED",
          "BANK_BOOKING_REQUEST_CONFIRMED",
          "BANK_BOOKING_REQUEST_REJECTED",
          "BANK_BOOKING_REQUEST_RESPONSIBLE_CONFIRMED",
          "BANK_BOOKING_REQUEST_RESPONSIBLE_REJECTED",
          "DISCORD_REGISTRATION_PENDING",
          "MATERIAL_EXCHANGE_INTEREST_REGISTERED",
          "MATERIAL_REQUEST_FULFILLMENT_SIGNALLED",
          "ACCOUNT_DELETION_REQUESTED",
          "ACCOUNT_DELETION_REQUEST_DECLINED");

  /**
   * {@code SelectorKind} codes, labelled via {@code admin.notificationRules.selector.kind}. The
   * last three read no selector field — the account or recipient comes from the event.
   */
  static final List<String> SELECTOR_KINDS =
      List.of(
          "SPECIFIC_USER",
          "ROLE",
          "ORG_RELATIVE_ROLE",
          "ACCOUNT_GRANT",
          "EVENT_RECIPIENT",
          "ACCOUNT_RESPONSIBLE");

  /**
   * {@code OrgRelativeRole} codes, labelled via {@code
   * admin.notificationRules.selector.orgRelativeRole}.
   */
  static final List<String> ORG_RELATIVE_ROLES =
      List.of("OFFICER", "LEAD", "LOGISTICIAN", "MISSION_MANAGER");

  /**
   * {@code NotificationContextRole} codes, labelled via {@code
   * admin.notificationRules.selector.contextRole}.
   */
  static final List<String> CONTEXT_ROLES = List.of("RESPONSIBLE", "REQUESTING");

  /**
   * Global role codes a {@code ROLE} selector may name, labelled via {@code
   * admin.notificationRules.selector.roleCode}. Not an enum: the backend validates the code against
   * its role catalogue (REQ-SEC-053), so a code offered here that the catalogue lacks is refused on
   * save rather than stored as a rule that addresses nobody.
   */
  static final List<String> ROLE_CODES =
      List.of(
          Roles.ADMIN, Roles.OFFICER, Roles.KRT_MEMBER, Roles.BANK_EMPLOYEE, Roles.BANK_MANAGEMENT);

  private final BackendApiClient backendApiClient;

  /**
   * Renders the rules admin page (list + create/edit form), fail-soft to an empty list with the
   * {@code error} key set. With {@code fragment=rules} only the rules-table fragment is rendered —
   * the target of the in-place {@code krtFetch.swap} after a create, update or delete (REQ-FE-001);
   * any other value renders the whole page, so a stray parameter cannot blank it.
   *
   * @param fragment {@code "rules"} for the table fragment, anything else (or absent) for the page
   * @param model the view model, filled with {@code rules}, the six option lists ({@code
   *     eventTypes}, {@code notificationTypes}, {@code selectorKinds}, {@code orgRelativeRoles},
   *     {@code contextRoles}, {@code roleCodes}) and, on a failed load, {@code error}
   * @return {@code admin/notification-rules}, or its {@code rules} fragment for a swap
   */
  @org.jetbrains.annotations.NotNull
  @GetMapping
  public String page(@RequestParam(required = false) String fragment, Model model) {
    try {
      List<NotificationRuleDto> rules = backendApiClient.get(BACKEND_BASE, LIST_TYPE);
      model.addAttribute("rules", rules == null ? List.of() : rules);
    } catch (Exception e) {
      log.debug("Failed to load notification rules", e);
      model.addAttribute("rules", List.of());
      model.addAttribute("error", "admin.notificationRules.error.load");
    }
    model.addAttribute("eventTypes", EVENT_TYPES);
    model.addAttribute("notificationTypes", NOTIFICATION_TYPES);
    model.addAttribute("selectorKinds", SELECTOR_KINDS);
    model.addAttribute("orgRelativeRoles", ORG_RELATIVE_ROLES);
    model.addAttribute("contextRoles", CONTEXT_ROLES);
    model.addAttribute("roleCodes", ROLE_CODES);
    // The SPECIFIC_USER selector is a server-side searchable combobox (remote-users, #1193): the
    // roster is searched on demand via /users/search, and notification-rules.js seeds an existing
    // rule's chosen user in edit mode by resolving its name through /users/{id}. No roster preload.
    return RULES_FRAGMENT.equals(fragment)
        ? "admin/notification-rules :: " + RULES_FRAGMENT
        : "admin/notification-rules";
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
    return relay(
        log,
        "load notification rule " + id + " (ajax)",
        () -> {
          return ResponseEntity.ok(
              backendApiClient.get(BACKEND_BASE + "/" + id, NotificationRuleDto.class));
        });
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
    return relay(
        log,
        "create notification rule (ajax)",
        () -> {
          return ResponseEntity.ok(
              backendApiClient.post(BACKEND_BASE, request, NotificationRuleDto.class));
        });
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
    return relay(
        log,
        "update notification rule " + id + " (ajax)",
        () -> {
          return ResponseEntity.ok(
              backendApiClient.put(BACKEND_BASE + "/" + id, request, NotificationRuleDto.class));
        });
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
    return relay(
        log,
        "delete notification rule " + id + " (ajax)",
        () -> {
          backendApiClient.delete(BACKEND_BASE + "/" + id, Void.class);
          return ResponseEntity.noContent().build();
        });
  }
}
