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
import de.greluc.krt.profit.basetool.frontend.notification.client.NotificationBackendClient;
import de.greluc.krt.profit.basetool.frontend.support.Roles;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
 * Admin page and AJAX relay for the data-driven notification rules (REQ-NOTIF-007), proxied through
 * {@link NotificationBackendClient}. Backend failures are relayed as {@code
 * application/problem+json}; after a write the rules table is re-fetched in place (REQ-FE-001).
 */
@Controller
@UsesLayoutModel
@RequestMapping("/admin/notification-rules")
@RequiredArgsConstructor
@PreAuthorize("hasRole('" + Roles.ADMIN + "')")
@Slf4j
public class AdminNotificationRulePageController {

  /** The {@code fragment} value that renders only the rules table, for the in-place swap. */
  static final String RULES_FRAGMENT = "rules";

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
          "ACCOUNT_DELETION_REQUEST_RESOLVED",
          "EXCHANGE_INSTALLATION_CONNECTED",
          "EXCHANGE_BULK_UNDO_APPLIED",
          "INVENTORY_TRANSFERRED_TO_USER",
          "INVENTORY_TRANSFERRED_FROM_USER",
          "DISCORD_REGISTRATION_DECIDED",
          "JOB_ORDER_CLOSED",
          "BANK_BOOKING_REQUEST_UPDATED_BY_REQUESTER",
          "BANK_ACCOUNT_RESPONSIBLE_ASSIGNED",
          "MISSION_RESCHEDULED",
          "MISSION_CANCELLED",
          "MISSION_DELETED",
          "MISSION_REMINDER_DUE",
          "MISSION_STARTED",
          "MISSION_CHECKED_IN",
          "MISSION_PARTICIPANT_ADDED",
          "MISSION_PARTICIPANT_REMOVED",
          "MISSION_PARTICIPANT_LEFT",
          "MISSION_NEVER_ENDED",
          "MISSION_END_RECORDED",
          "MISSION_RESPONSIBILITY_ASSIGNED",
          "OPERATION_PAYOUT_MARKED",
          "OPERATION_PAYOUT_UNMARKED",
          "OPERATION_COMPLETED",
          "OPERATION_COMPLETED_UNOWNED",
          "JOB_ORDER_REASSIGNED",
          "JOB_ORDER_FINISHED",
          "JOB_ORDER_ASSIGNEE_ADDED",
          "JOB_ORDER_ASSIGNEE_REMOVED",
          "JOB_ORDER_CLAIM_WITHDRAWN",
          "REFINERY_ORDER_READY",
          "REFINERY_ORDER_CHANGED_BY_OTHER",
          "REFINERY_ORDER_READY_CLEARED",
          "MATERIAL_EXCHANGE_OFFER_UNAVAILABLE",
          "MATERIAL_REQUEST_UNAVAILABLE",
          "INVENTORY_BOOKED_OUT_BY_OTHER",
          "BANK_BOOKING_REQUEST_APPROVED",
          "BANK_BOOKING_REQUEST_APPROVAL_REVOKED",
          "BANK_GRANT_CHANGED",
          "BANK_GRANT_REVOKED",
          "BANK_PAYOUT_BOOKED",
          "BANK_HOLDER_TRANSFER_BOOKED",
          "BANK_ACCOUNT_DEBITED",
          "BANK_HOLDER_DEACTIVATED_WITH_BALANCE",
          "BANK_HOLDER_NOTICE_CLEARED");

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
          "ACCOUNT_DELETION_REQUEST_DECLINED",
          "EXCHANGE_INSTALLATION_CONNECTED",
          "EXCHANGE_BULK_UNDO_APPLIED",
          "INVENTORY_TRANSFERRED_TO_USER",
          "INVENTORY_TRANSFERRED_FROM_USER",
          "BANK_BOOKING_REQUEST_UPDATED",
          "BANK_ACCOUNT_RESPONSIBLE_ASSIGNED",
          "MISSION_RESCHEDULED",
          "MISSION_CANCELLED",
          "MISSION_DELETED",
          "MISSION_REMINDER",
          "MISSION_CHECKIN_OPEN",
          "MISSION_PARTICIPANT_ADDED_BY_OTHER",
          "MISSION_PARTICIPANT_REMOVED_BY_OTHER",
          "MISSION_PARTICIPANT_LEFT",
          "MISSION_NEVER_ENDED",
          "MISSION_RESPONSIBILITY_ASSIGNED",
          "OPERATION_PAYOUT_PAID_OUT",
          "OPERATION_COMPLETED",
          "JOB_ORDER_REASSIGNED",
          "JOB_ORDER_FINISHED",
          "JOB_ORDER_ASSIGNED",
          "JOB_ORDER_CLAIM_WITHDRAWN",
          "REFINERY_ORDER_READY",
          "REFINERY_ORDER_CHANGED_BY_OTHER",
          "MATERIAL_EXCHANGE_OFFER_UNAVAILABLE",
          "MATERIAL_REQUEST_UNAVAILABLE",
          "INVENTORY_BOOKED_OUT_BY_OTHER",
          "BANK_BOOKING_REQUEST_APPROVED",
          "BANK_GRANT_CHANGED",
          "BANK_GRANT_REVOKED",
          "BANK_PAYOUT_RECEIVED",
          "BANK_HOLDER_TRANSFER_RECEIVED",
          "BANK_ACCOUNT_DEBITED",
          "BANK_HOLDER_DEACTIVATED_WITH_BALANCE");

  /**
   * {@code SelectorKind} codes, labelled via {@code admin.notificationRules.selector.kind}. All but
   * the first three read no selector field — the recipients come from the event.
   */
  static final List<String> SELECTOR_KINDS =
      List.of(
          "SPECIFIC_USER",
          "ROLE",
          "ORG_RELATIVE_ROLE",
          "ACCOUNT_GRANT",
          "EVENT_RECIPIENT",
          "ACCOUNT_RESPONSIBLE",
          "MISSION_PARTICIPANTS",
          "MISSION_LEADERSHIP",
          "EXCHANGE_CLIENT_HOLDERS",
          "EVENT_RECIPIENTS");

  /**
   * {@code OrgRelativeRole} codes, labelled via {@code
   * admin.notificationRules.selector.orgRelativeRole}.
   */
  static final List<String> ORG_RELATIVE_ROLES =
      List.of("OFFICER", "LEAD", "LOGISTICIAN", "MISSION_MANAGER", "UNIT_LEADERSHIP");

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

  private final NotificationBackendClient notificationClient;

  /**
   * Renders the rules admin page, or with {@code fragment=rules} only the rules table for the
   * in-place swap (REQ-FE-001). A failed load renders an empty list with {@code error} set.
   *
   * @param fragment {@code "rules"} for the table fragment; anything else renders the page
   * @param model the view model, filled with {@code rules}, the six option lists and, on a failed
   *     load, {@code error}
   * @return {@code admin/notification-rules}, or its {@code rules} fragment
   */
  @org.jetbrains.annotations.NotNull
  @GetMapping
  public String page(@RequestParam(required = false) String fragment, Model model) {
    try {
      List<NotificationRuleDto> rules = notificationClient.rules();
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
          return ResponseEntity.ok(notificationClient.rule(id));
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
          return ResponseEntity.ok(notificationClient.createRule(request));
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
          return ResponseEntity.ok(notificationClient.updateRule(id, request));
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
          notificationClient.deleteRule(id);
          return ResponseEntity.noContent().build();
        });
  }
}
