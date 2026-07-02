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

import de.greluc.krt.profit.basetool.frontend.model.dto.AuditEventDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.AuditRowView;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankAuditEventDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.support.Roles;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Spring MVC controller for the unified admin audit-log page ({@code /admin/audit-log},
 * REQ-AUDIT-001, ADR-0037). One page, a nine-way tab switcher: the existing bank trail plus the
 * eight generic areas (Lager / Aufträge / Raffinerie / Mein Inventar / Missionen / Operationen /
 * Rollen / Beförderung). The bank tab reads the existing {@code /api/v1/bank/admin/audit} endpoint,
 * the others read {@code /api/v1/audit/{domain}}; both DTO shapes are adapted into the uniform
 * {@link AuditRowView} so a single template renders every tab.
 *
 * <p>Admin-only — the class-level {@code @PreAuthorize} matches the backend's admin URL gates;
 * filtering and paging swap in place via {@code krtFetch} (epic #571 pattern).
 */
@Controller
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("hasRole('" + Roles.ADMIN + "')")
public class AdminAuditLogPageController {

  /** Audit-log page size; the table is dense and read-only, so a large page is fine. */
  private static final int AUDIT_PAGE_SIZE = 50;

  /** The bank tab is the default landing tab (the old {@code /admin/bank-audit} redirects here). */
  private static final String DEFAULT_DOMAIN = "BANK";

  /** The tabs, in display order. */
  private static final List<String> DOMAINS =
      List.of(
          "BANK",
          "INVENTORY",
          "JOB_ORDER",
          "REFINERY",
          "PERSONAL_INVENTORY",
          "MISSION",
          "OPERATION",
          "ROLE",
          "PROMOTION");

  /** Message-bundle key prefix for the bank event-type labels (their own namespace). */
  private static final String BANK_EVENT_PREFIX = "admin.bank.audit.event.";

  /** Message-bundle key prefix for the generic-area event-type labels. */
  private static final String GENERIC_EVENT_PREFIX = "admin.audit.event.";

  /** The event types offered in the per-tab filter dropdown, by domain (in a sensible order). */
  private static final Map<String, List<String>> EVENT_TYPES_BY_DOMAIN =
      Map.of(
          "BANK",
          List.of(
              "ACCOUNT_CREATED",
              "ACCOUNT_RENAMED",
              "ACCOUNT_CLOSED",
              "ACCOUNT_REOPENED",
              "HOLDER_REGISTERED",
              "HOLDER_DEACTIVATED",
              "HOLDER_REACTIVATED",
              "GRANT_CREATED",
              "GRANT_UPDATED",
              "GRANT_REVOKED",
              "DEPOSIT_BOOKED",
              "DEPOSIT_SPLIT_BOOKED",
              "WITHDRAWAL_BOOKED",
              "TRANSFER_BOOKED",
              "HOLDER_TRANSFER",
              "HOLDER_REBOOKED",
              "TRANSACTION_REVERSED",
              "WIPE_RESET_EXECUTED",
              "STATEMENT_EXPORTED",
              "MANAGEMENT_REPORT_EXPORTED",
              "BOOKING_REQUEST_CREATED",
              "BOOKING_REQUEST_CONFIRMED",
              "BOOKING_REQUEST_REJECTED",
              "BOOKING_REQUEST_CANCELLED",
              "AUDIT_LOG_EXPORTED",
              "AUDIT_LOG_PURGED",
              "BALANCE_TARGET_SET",
              "BALANCE_TARGET_CLEARED",
              "BALANCE_VISIBILITY_GRANTED",
              "BALANCE_VISIBILITY_REVOKED",
              "APPROVAL_LIMIT_SET",
              "APPROVAL_LIMIT_CLEARED",
              "BOOKING_REQUEST_OWNER_APPROVAL_GRANTED",
              "BOOKING_REQUEST_OWNER_APPROVAL_REVOKED",
              "BOOKING_REQUEST_OWNER_APPROVAL_CONFIRMED",
              "CARTEL_APPROVAL_TIERS_SET",
              "CARTEL_APPROVAL_TIERS_CLEARED"),
          "INVENTORY",
          List.of(
              "INVENTORY_ITEM_CREATED",
              "INVENTORY_ITEM_UPDATED",
              "INVENTORY_ITEM_NOTE_UPDATED",
              "INVENTORY_ITEM_CONSUMED",
              "INVENTORY_ITEM_TRANSFERRED",
              "INVENTORY_ITEM_SOLD",
              "INVENTORY_ITEM_DEPERSONALIZED",
              "INVENTORY_ITEM_PERSONALIZED",
              "INVENTORY_ITEM_DELIVERY_TOGGLED",
              "INVENTORY_BULK_CHECKED_OUT",
              "INVENTORY_WIPED",
              "INVENTORY_RECEIVED_FROM_REFINERY",
              "INVENTORY_HANDED_OVER",
              "INVENTORY_ORG_RESTAMPED",
              "INVENTORY_OWNER_REASSIGNED",
              "INVENTORY_AUDIT_EXPORTED",
              "INVENTORY_AUDIT_PURGED"),
          "JOB_ORDER",
          List.of(
              "JOB_ORDER_CREATED",
              "JOB_ORDER_ITEM_CREATED",
              "JOB_ORDER_UPDATED",
              "JOB_ORDER_ITEM_UPDATED",
              "JOB_ORDER_STATUS_CHANGED",
              "JOB_ORDER_PRIORITY_CHANGED",
              "JOB_ORDER_BLUEPRINT_COUNTING_CHANGED",
              "JOB_ORDER_DELETED",
              "JOB_ORDER_COMPLETED",
              "JOB_ORDER_REASSIGNED",
              "JOB_ORDER_ASSIGNEE_ADDED",
              "JOB_ORDER_ASSIGNEE_REMOVED",
              "JOB_ORDER_ASSIGNEE_NOTE_SET",
              "JOB_ORDER_ASSIGNEE_NOTE_CLEARED",
              "JOB_ORDER_MATERIAL_UNLINKED",
              "JOB_ORDER_INVENTORY_UNLINKED",
              "JOB_ORDER_HANDOVER_CREATED",
              "JOB_ORDER_ITEM_HANDOVER_CREATED",
              "JOB_ORDER_CLAIM_UPSERTED",
              "JOB_ORDER_CLAIM_WITHDRAWN",
              "JOB_ORDER_AUDIT_EXPORTED",
              "JOB_ORDER_AUDIT_PURGED"),
          "REFINERY",
          List.of(
              "REFINERY_ORDER_CREATED",
              "REFINERY_ORDER_UPDATED",
              "REFINERY_ORDER_CANCELED",
              "REFINERY_ORDER_STORED",
              "REFINERY_METHOD_CREATED",
              "REFINERY_METHOD_UPDATED",
              "REFINERY_METHOD_DELETED",
              "REFINERY_METHODS_SYNCED",
              "REFINERY_YIELDS_SYNCED",
              "REFINERY_ORDERS_REASSIGNED",
              "REFINERY_AUDIT_EXPORTED",
              "REFINERY_AUDIT_PURGED"),
          "PERSONAL_INVENTORY",
          List.of(
              "PERSONAL_INVENTORY_CREATED",
              "PERSONAL_INVENTORY_UPDATED",
              "PERSONAL_INVENTORY_DELETED",
              "PERSONAL_INVENTORY_AUDIT_EXPORTED",
              "PERSONAL_INVENTORY_AUDIT_PURGED"),
          "MISSION",
          List.of(
              "MISSION_CREATED",
              "MISSION_UPDATED",
              "MISSION_DELETED",
              "MISSION_PARTICIPANT_ADDED",
              "MISSION_PARTICIPANT_REMOVED",
              "MISSION_PARTICIPANT_UPDATED",
              "MISSION_PARTICIPANT_CHECKED_IN",
              "MISSION_PARTICIPANT_CHECKED_OUT",
              "MISSION_UNIT_ADDED",
              "MISSION_UNIT_UPDATED",
              "MISSION_UNIT_REMOVED",
              "MISSION_CREW_ADDED",
              "MISSION_CREW_UPDATED",
              "MISSION_CREW_REMOVED",
              "MISSION_FREQUENCY_CHANGED",
              "MISSION_FREQUENCY_REMOVED",
              "MISSION_OWNER_CHANGED",
              "MISSION_OWNING_ORG_UNIT_CHANGED",
              "MISSION_PARTY_LEAD_CHANGED",
              "MISSION_MANAGER_ADDED",
              "MISSION_MANAGER_REMOVED",
              "MISSION_FINANCE_ENTRY_CREATED",
              "MISSION_FINANCE_ENTRY_UPDATED",
              "MISSION_FINANCE_ENTRY_DELETED",
              "MISSION_STEP_ADDED",
              "MISSION_STEP_UPDATED",
              "MISSION_STEP_REMOVED",
              "MISSION_STEP_REORDERED",
              "MISSION_STEP_DONE_CHANGED",
              "MISSION_OBJECTIVE_ADDED",
              "MISSION_OBJECTIVE_UPDATED",
              "MISSION_OBJECTIVE_REMOVED",
              "MISSION_OBJECTIVE_REORDERED",
              "MISSION_AUDIT_EXPORTED",
              "MISSION_AUDIT_PURGED"),
          "OPERATION",
          List.of(
              "OPERATION_CREATED",
              "OPERATION_UPDATED",
              "OPERATION_DELETED",
              "OPERATION_PAYOUT_TOGGLED",
              "OPERATION_AUDIT_EXPORTED",
              "OPERATION_AUDIT_PURGED"),
          "ROLE",
          List.of(
              "MEMBERSHIP_GRANTED",
              "MEMBERSHIP_REVOKED",
              "ROLE_GRANTED",
              "ROLE_CHANGED",
              "ROLE_REVOKED",
              "CAPABILITY_FLAGS_CHANGED",
              "KOMMANDO_GROUP_CREATED",
              "KOMMANDO_GROUP_UPDATED",
              "KOMMANDO_GROUP_DELETED",
              "ROLE_AUDIT_EXPORTED",
              "ROLE_AUDIT_PURGED"),
          "PROMOTION",
          List.of(
              "PROMOTION_TOPIC_CREATED",
              "PROMOTION_TOPIC_UPDATED",
              "PROMOTION_TOPIC_DELETED",
              "PROMOTION_CATEGORY_CREATED",
              "PROMOTION_CATEGORY_UPDATED",
              "PROMOTION_CATEGORY_DELETED",
              "PROMOTION_LEVEL_CONTENT_CREATED",
              "PROMOTION_LEVEL_CONTENT_UPDATED",
              "PROMOTION_LEVEL_CONTENT_DELETED",
              "PROMOTION_RANK_REQUIREMENT_CREATED",
              "PROMOTION_RANK_REQUIREMENT_UPDATED",
              "PROMOTION_RANK_REQUIREMENT_DELETED",
              "PROMOTION_EVALUATION_CREATED",
              "PROMOTION_EVALUATION_UPDATED",
              "PROMOTION_EVALUATION_DELETED",
              "PROMOTION_AUDIT_EXPORTED",
              "PROMOTION_AUDIT_PURGED"));

  private final BackendApiClient backendApiClient;

  /**
   * Renders the paged, filterable unified audit-log viewer for one selected area tab.
   *
   * @param domain the selected tab (one of {@link #DOMAINS}); defaults to and falls back to {@code
   *     BANK}
   * @param from period start filter (ISO instant), or absent
   * @param to period end filter (ISO instant), or absent
   * @param actorUserId actor filter (user id), or absent
   * @param eventType event-type filter, or absent
   * @param page zero-based page index
   * @param fragment when {@code "results"}, only the results+pagination fragment is rendered for an
   *     in-place AJAX swap; otherwise the full page
   * @param model Thymeleaf model
   * @return the {@code admin/audit-log} view name, or its {@code auditResults} fragment selector
   */
  @GetMapping("/admin/audit-log")
  public String auditLog(
      @RequestParam(required = false) String domain,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(required = false) String actorUserId,
      @RequestParam(required = false) String eventType,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false) String fragment,
      Model model) {
    String activeDomain = domain != null && DOMAINS.contains(domain) ? domain : DEFAULT_DOMAIN;
    boolean isBank = "BANK".equals(activeDomain);
    final String eventKeyPrefix = isBank ? BANK_EVENT_PREFIX : GENERIC_EVENT_PREFIX;

    String backendPath = isBank ? "/api/v1/bank/admin/audit" : "/api/v1/audit/" + activeDomain;
    UriComponentsBuilder uri =
        UriComponentsBuilder.fromPath(backendPath)
            .queryParam("page", Math.max(page, 0))
            .queryParam("size", AUDIT_PAGE_SIZE);
    appendIfPresent(uri, "from", from);
    appendIfPresent(uri, "to", to);
    appendIfPresent(uri, "actorUserId", actorUserId);
    appendIfPresent(uri, "eventType", eventType);

    PageResponse<AuditRowView> events = null;
    try {
      events =
          isBank
              ? adaptBank(
                  backendApiClient.get(
                      uri.toUriString(),
                      new ParameterizedTypeReference<PageResponse<BankAuditEventDto>>() {}),
                  eventKeyPrefix)
              : adaptGeneric(
                  backendApiClient.get(
                      uri.toUriString(),
                      new ParameterizedTypeReference<PageResponse<AuditEventDto>>() {}),
                  eventKeyPrefix);
    } catch (Exception e) {
      log.error("Failed to load audit log for domain {}", activeDomain, e);
      model.addAttribute("error", "admin.audit.error.load");
    }

    model.addAttribute("activeDomain", activeDomain);
    model.addAttribute("domains", DOMAINS);
    model.addAttribute("events", events);
    model.addAttribute("eventTypes", EVENT_TYPES_BY_DOMAIN.getOrDefault(activeDomain, List.of()));
    model.addAttribute("eventKeyPrefix", eventKeyPrefix);
    model.addAttribute("filterFrom", from);
    model.addAttribute("filterTo", to);
    model.addAttribute("filterActorUserId", actorUserId);
    model.addAttribute("filterEventType", eventType);
    model.addAttribute(
        "paginationBaseUrl", buildBaseUrl(activeDomain, from, to, actorUserId, eventType));
    model.addAttribute("exportEndpoint", "/api/proxy/audit/" + activeDomain + "/export");
    model.addAttribute("purgeEndpoint", "/api/proxy/audit/" + activeDomain);
    if (fragment != null && "results".equalsIgnoreCase(fragment)) {
      return "admin/audit-log :: auditResults";
    }
    return "admin/audit-log";
  }

  /**
   * Adapts a page of bank audit rows into the uniform view model (subject = account number).
   *
   * @param page the bank audit page, or {@code null}
   * @param prefix the bank event-label key prefix
   * @return the adapted page, or {@code null} when the source was {@code null}
   */
  private static PageResponse<AuditRowView> adaptBank(
      PageResponse<BankAuditEventDto> page, String prefix) {
    if (page == null) {
      return null;
    }
    List<AuditRowView> rows =
        page.content().stream()
            .map(
                e ->
                    new AuditRowView(
                        e.occurredAt(),
                        e.actorHandle(),
                        prefix + e.eventType(),
                        e.accountNo() != null ? e.accountNo() : "—",
                        e.details()))
            .toList();
    return new PageResponse<>(
        rows, page.page(), page.size(), page.totalElements(), page.totalPages(), page.sort());
  }

  /**
   * Adapts a page of generic audit rows into the uniform view model (subject = subject label).
   *
   * @param page the generic audit page, or {@code null}
   * @param prefix the generic event-label key prefix
   * @return the adapted page, or {@code null} when the source was {@code null}
   */
  private static PageResponse<AuditRowView> adaptGeneric(
      PageResponse<AuditEventDto> page, String prefix) {
    if (page == null) {
      return null;
    }
    List<AuditRowView> rows =
        page.content().stream()
            .map(
                e ->
                    new AuditRowView(
                        e.occurredAt(),
                        e.actorHandle(),
                        prefix + e.eventType(),
                        e.subjectLabel() != null ? e.subjectLabel() : "—",
                        e.details()))
            .toList();
    return new PageResponse<>(
        rows, page.page(), page.size(), page.totalElements(), page.totalPages(), page.sort());
  }

  /**
   * Appends a query parameter to the backend URI when the value is present and non-blank.
   *
   * @param uri the builder
   * @param name the parameter name
   * @param value the value, or {@code null}/blank to skip
   */
  private static void appendIfPresent(UriComponentsBuilder uri, String name, String value) {
    if (value != null && !value.isBlank()) {
      uri.queryParam(name, value);
    }
  }

  /**
   * Builds the pagination base URL preserving the active tab + filters so paging keeps both.
   *
   * @param domain the active tab
   * @param from period start filter, or {@code null}
   * @param to period end filter, or {@code null}
   * @param actorUserId actor filter, or {@code null}
   * @param eventType event-type filter, or {@code null}
   * @return the base URL with the domain + filter query parameters (no page/size)
   */
  private static String buildBaseUrl(
      String domain, String from, String to, String actorUserId, String eventType) {
    UriComponentsBuilder base =
        UriComponentsBuilder.fromPath("/admin/audit-log").queryParam("domain", domain);
    appendIfPresent(base, "from", from);
    appendIfPresent(base, "to", to);
    appendIfPresent(base, "actorUserId", actorUserId);
    appendIfPresent(base, "eventType", eventType);
    return base.toUriString();
  }
}
