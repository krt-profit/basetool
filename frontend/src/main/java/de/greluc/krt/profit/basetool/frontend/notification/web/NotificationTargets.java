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

package de.greluc.krt.profit.basetool.frontend.notification.web;

import de.greluc.krt.profit.basetool.frontend.kernel.security.Roles;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.springframework.security.core.GrantedAuthority;

/**
 * Resolves the page a notification opens: the subject's detail page, or the list page with the
 * subject focused (REQ-NOTIF-022, REQ-NOTIF-032).
 *
 * <p>Every notification type is either mapped in {@link #TARGETS} or listed in {@link
 * #UNLINKED_TYPES}; a target that depends on the viewer's roles links only what the viewer can
 * open.
 */
final class NotificationTargets {

  private static final String JOB_ORDER = "JOB_ORDER";
  private static final String MISSION = "MISSION";
  private static final String OPERATION = "OPERATION";
  private static final String BANK_BOOKING_REQUEST = "BANK_BOOKING_REQUEST";
  private static final String EXCHANGE_CLIENT = "EXCHANGE_CLIENT";
  private static final String SUPPLY_DONATION = "SUPPLY_DONATION";
  private static final String PURCHASING_DEAL = "PURCHASING_DEAL";
  private static final String PURCHASING_DELEGATE = "PURCHASING_DELEGATE";
  private static final String REFINERY_ORDER = "REFINERY_ORDER";

  private static final String ORDERS = "/orders/";
  private static final String MISSIONS = "/missions/";
  private static final String OPERATIONS = "/operations/";
  private static final String BANK_REQUEST_QUEUE = "/bank/requests";
  private static final String CONNECTED_APPS = "/connected-apps";
  private static final String MY_LAGER = "/inventory/my";
  private static final String STOCK_LEVELS = "/inventory/stock-levels";
  private static final String HANGAR = "/hangar";
  private static final String BLUEPRINTS = "/personal-inventory/blueprints";
  private static final String MY_DONATIONS = "/supply/donations/mine";
  private static final String PURCHASING_DEALS = "/purchasing/deals";
  private static final String PURCHASING_DELEGATES = "/purchasing/delegates";
  private static final String MATERIALBOERSE = "/materialboerse";
  private static final String MATERIALBOERSE_REQUESTS = "/materialboerse?view=requests";

  private static final String TAB_CREW = "crew";
  private static final String TAB_PAYOUT = "payout";
  private static final String TAB_OWN_REQUESTS = "antraege";
  private static final String TAB_FOREIGN_REQUESTS = "fremde";

  /** The query parameter {@code krt-focus.js} reads to bring one list item into view. */
  private static final String FOCUS_PARAM = "focus=";

  /** The types whose subject is gone or has no page its recipient can open. */
  @Unmodifiable
  static final Set<String> UNLINKED_TYPES =
      Set.of(
          "MISSION_DELETED",
          "BANK_GRANT_REVOKED",
          "BANK_PAYOUT_RECEIVED",
          "BANK_HOLDER_TRANSFER_RECEIVED");

  /** The target of every linked notification type. */
  @Unmodifiable
  static final Map<String, Target> TARGETS =
      Map.ofEntries(
          Map.entry("JOB_ORDER_CREATED", detail(JOB_ORDER, ORDERS)),
          Map.entry("JOB_ORDER_UPDATED_BY_REQUESTER", detail(JOB_ORDER, ORDERS)),
          Map.entry("JOB_ORDER_REASSIGNED", detail(JOB_ORDER, ORDERS)),
          Map.entry("JOB_ORDER_FINISHED", detail(JOB_ORDER, ORDERS)),
          Map.entry("JOB_ORDER_ASSIGNED", detailTab(JOB_ORDER, ORDERS, "assignees")),
          Map.entry("JOB_ORDER_CLAIM_WITHDRAWN", detailTab(JOB_ORDER, ORDERS, "materials")),
          Map.entry("BANK_BOOKING_REQUEST_CREATED", bankRequest(TAB_FOREIGN_REQUESTS)),
          Map.entry("BANK_BOOKING_REQUEST_UPDATED", bankRequest(TAB_FOREIGN_REQUESTS)),
          Map.entry("BANK_BOOKING_REQUEST_APPROVED", bankRequest(TAB_OWN_REQUESTS)),
          Map.entry("BANK_BOOKING_REQUEST_CONFIRMED", orgUnitBankRequest(TAB_OWN_REQUESTS)),
          Map.entry("BANK_BOOKING_REQUEST_REJECTED", orgUnitBankRequest(TAB_OWN_REQUESTS)),
          Map.entry(
              "BANK_BOOKING_REQUEST_RESPONSIBLE_CONFIRMED",
              orgUnitBankRequest(TAB_FOREIGN_REQUESTS)),
          Map.entry(
              "BANK_BOOKING_REQUEST_RESPONSIBLE_REJECTED",
              orgUnitBankRequest(TAB_FOREIGN_REQUESTS)),
          Map.entry(
              "BANK_ACCOUNT_RESPONSIBLE_ASSIGNED",
              detail("BANK_ACCOUNT", "/org-unit-bank/accounts/")),
          Map.entry("BANK_GRANT_CHANGED", detail("BANK_ACCOUNT_GRANT", "/bank/accounts/")),
          Map.entry("BANK_ACCOUNT_DEBITED", page("/org-unit-bank")),
          Map.entry(
              "BANK_HOLDER_DEACTIVATED_WITH_BALANCE", detail("BANK_HOLDER", "/bank/holders/")),
          Map.entry(
              "DISCORD_REGISTRATION_PENDING",
              forViewer(
                  Viewer::admin, focus("DISCORD_REGISTRATION", "/admin/discord-registrations"))),
          Map.entry(
              "ACCOUNT_DELETION_REQUESTED",
              forViewer(Viewer::admin, focus("DELETION_REQUEST", "/admin/deletion-requests"))),
          Map.entry("ACCOUNT_DELETION_REQUEST_DECLINED", page("/profile#profile-deletion-host")),
          Map.entry(
              "MATERIAL_EXCHANGE_INTEREST_REGISTERED",
              item(
                  "MATERIAL_EXCHANGE_OFFER",
                  MATERIALBOERSE + "?view=offers&scope=mine&selected=",
                  "",
                  MATERIALBOERSE)),
          Map.entry(
              "MATERIAL_REQUEST_FULFILLMENT_SIGNALLED",
              item(
                  "MATERIAL_EXCHANGE_REQUEST",
                  MATERIALBOERSE_REQUESTS + "&scope=mine&selected=",
                  "",
                  MATERIALBOERSE_REQUESTS)),
          Map.entry("MATERIAL_EXCHANGE_OFFER_UNAVAILABLE", page(MATERIALBOERSE)),
          Map.entry("MATERIAL_REQUEST_UNAVAILABLE", page(MATERIALBOERSE_REQUESTS)),
          Map.entry(
              "EXCHANGE_INSTALLATION_CONNECTED", focus("EXCHANGE_INSTALLATION", CONNECTED_APPS)),
          Map.entry("EXCHANGE_BULK_UNDO_APPLIED", page(CONNECTED_APPS)),
          Map.entry("EXCHANGE_CLIENT_SUSPENDED", focus(EXCHANGE_CLIENT, CONNECTED_APPS)),
          Map.entry("EXCHANGE_CLIENT_ACTIVATED", focus(EXCHANGE_CLIENT, CONNECTED_APPS)),
          Map.entry("EXCHANGE_CLIENT_UPDATE_REQUIRED", focus(EXCHANGE_CLIENT, CONNECTED_APPS)),
          Map.entry("EXCHANGE_CLIENT_CAPABILITY_REMOVED", focus(EXCHANGE_CLIENT, CONNECTED_APPS)),
          Map.entry("EXCHANGE_SWITCHED_OFF", page(CONNECTED_APPS)),
          Map.entry("INVENTORY_TRANSFERRED_TO_USER", page(MY_LAGER)),
          Map.entry("INVENTORY_TRANSFERRED_FROM_USER", page(MY_LAGER)),
          Map.entry("INVENTORY_BOOKED_OUT_BY_OTHER", page(MY_LAGER)),
          Map.entry("INVENTORY_POOL_CHANGED_BY_OTHER", page(MY_LAGER)),
          Map.entry("INVENTORY_STOCK_LEVEL_LOW", page(STOCK_LEVELS)),
          Map.entry("MISSION_RESCHEDULED", detail(MISSION, MISSIONS)),
          Map.entry("MISSION_CANCELLED", detail(MISSION, MISSIONS)),
          Map.entry("MISSION_PARTICIPANT_REMOVED_BY_OTHER", detail(MISSION, MISSIONS)),
          Map.entry("MISSION_RESPONSIBILITY_ASSIGNED", detail(MISSION, MISSIONS)),
          Map.entry("MISSION_REMINDER", detailTab(MISSION, MISSIONS, TAB_CREW)),
          Map.entry("MISSION_CHECKIN_OPEN", detailTab(MISSION, MISSIONS, TAB_CREW)),
          Map.entry("MISSION_PARTICIPANT_ADDED_BY_OTHER", detailTab(MISSION, MISSIONS, TAB_CREW)),
          Map.entry("MISSION_PARTICIPANT_LEFT", detailTab(MISSION, MISSIONS, TAB_CREW)),
          Map.entry("MISSION_NEVER_ENDED", detailTab(MISSION, MISSIONS, "verw")),
          Map.entry("HANGAR_SHIP_REMOVED_FROM_UNIT", detailTab(MISSION, MISSIONS, TAB_CREW)),
          Map.entry("OPERATION_PAYOUT_PAID_OUT", detailTab(OPERATION, OPERATIONS, TAB_PAYOUT)),
          Map.entry("OPERATION_COMPLETED", detailTab(OPERATION, OPERATIONS, TAB_PAYOUT)),
          Map.entry("REFINERY_ORDER_READY", detail(REFINERY_ORDER, "/refinery-orders/")),
          Map.entry("REFINERY_ORDER_CHANGED_BY_OTHER", detail(REFINERY_ORDER, "/refinery-orders/")),
          Map.entry(
              "ORG_LEADERSHIP_ROLE_MISMATCH",
              forViewer(Viewer::admin, item("ORG_UNIT_MEMBER", "/members/", "/edit", null))),
          Map.entry("ORG_MEMBER_DEPARTED", page("/organisation/leitung")),
          Map.entry("HANGAR_SHIP_ASSIGNED", page(HANGAR)),
          Map.entry("HANGAR_FITTED_RESET", page(HANGAR)),
          Map.entry("HANGAR_CHANGED_BY_ADMIN", page(HANGAR)),
          Map.entry("HANGAR_SHIPS_ORG_UNIT_CHANGED_BY_ADMIN", page(HANGAR)),
          Map.entry("BLUEPRINT_CHANGED_BY_ADMIN", page(BLUEPRINTS)),
          Map.entry("BLUEPRINT_PURGED_BY_ADMIN", page(BLUEPRINTS)),
          Map.entry(
              "SUPPLY_DONATION_PLEDGED", focus(SUPPLY_DONATION, "/supply/donations/incoming")),
          Map.entry("SUPPLY_DONATION_RECEIVED", focus(SUPPLY_DONATION, MY_DONATIONS)),
          Map.entry("SUPPLY_DONATION_DECLINED", focus(SUPPLY_DONATION, MY_DONATIONS)),
          Map.entry("SUPPLY_NEED_ORDER_COVERED", focus("SUPPLY_NEED", "/supply/needs")),
          Map.entry("PURCHASING_NEED_RELEASED", page(PURCHASING_DEALS)),
          Map.entry(
              "PURCHASING_DELIVERY_RECORDED", detail(PURCHASING_DEAL, PURCHASING_DEALS + "/")),
          Map.entry("PURCHASING_DELIVERY_DECIDED", detail(PURCHASING_DEAL, PURCHASING_DEALS + "/")),
          Map.entry(
              "PURCHASING_SETTLEMENT_OVERDUE", detail(PURCHASING_DEAL, PURCHASING_DEALS + "/")),
          Map.entry("PURCHASING_PAUSED", page(PURCHASING_DEALS)),
          Map.entry("PURCHASING_RESUMED", page(PURCHASING_DEALS)),
          Map.entry(
              "PURCHASING_DELEGATE_APPOINTED", focus(PURCHASING_DELEGATE, PURCHASING_DELEGATES)),
          Map.entry(
              "PURCHASING_DELEGATE_REVOKED", focus(PURCHASING_DELEGATE, PURCHASING_DELEGATES)));

  private NotificationTargets() {}

  /**
   * Resolves the page a notification opens for the given viewer.
   *
   * @param type the notification type
   * @param entityType the subject's type tag
   * @param entityId the subject's id
   * @param viewer the roles of the member looking at the notification
   * @return a same-origin path, or {@code null} when the notification has nothing the viewer can
   *     open
   */
  static @Nullable String targetOf(
      @Nullable String type,
      @Nullable String entityType,
      @Nullable UUID entityId,
      @NotNull Viewer viewer) {
    Target target = type == null ? null : TARGETS.get(type);
    return target == null ? null : target.resolve(entityType, entityId, viewer);
  }

  /**
   * A fixed page.
   *
   * @param path the page
   * @return the target
   */
  private static Target page(String path) {
    return (entityType, entityId, viewer) -> path;
  }

  /**
   * The subject's detail page.
   *
   * @param entityType the subject's type tag the notification must carry
   * @param prefix the detail path before the id
   * @return the target, linking nothing for another tag or a missing id
   */
  private static Target detail(String entityType, String prefix) {
    return item(entityType, prefix, "", null);
  }

  /**
   * The subject's detail page, opened on one tab.
   *
   * @param entityType the subject's type tag the notification must carry
   * @param prefix the detail path before the id
   * @param tab the tab key the detail page reads from {@code ?tab=}
   * @return the target, linking nothing for another tag or a missing id
   */
  private static Target detailTab(String entityType, String prefix, String tab) {
    return item(entityType, prefix, "?tab=" + tab, null);
  }

  /**
   * A list page with the subject brought into view by {@code krt-focus.js}.
   *
   * @param entityType the subject's type tag the notification must carry
   * @param path the list page, without a query
   * @return the target, the plain list for another tag or a missing id
   */
  private static Target focus(String entityType, String path) {
    return item(entityType, path + "?" + FOCUS_PARAM, "", path);
  }

  /**
   * A booking request: bank staff see it in the request queue, every other recipient on the
   * org-unit bank page.
   *
   * @param memberTab the org-unit bank tab that lists the request for a member
   * @return the target
   */
  private static Target bankRequest(String memberTab) {
    Target staff = focus(BANK_BOOKING_REQUEST, BANK_REQUEST_QUEUE);
    Target member = orgUnitBankRequest(memberTab);
    return (entityType, entityId, viewer) ->
        (viewer.bankStaff() ? staff : member).resolve(entityType, entityId, viewer);
  }

  /**
   * A booking request on the org-unit bank page, on the tab that lists it.
   *
   * @param tab the tab key the page reads from {@code #tab=}
   * @return the target, the plain tab for another tag or a missing id
   */
  private static Target orgUnitBankRequest(String tab) {
    return item(
        BANK_BOOKING_REQUEST,
        "/org-unit-bank?" + FOCUS_PARAM,
        "#tab=" + tab,
        "/org-unit-bank#tab=" + tab);
  }

  /**
   * A target that links only for viewers matching a condition.
   *
   * @param allowed the condition on the viewer
   * @param target the target for an allowed viewer
   * @return the target, linking nothing for any other viewer
   */
  private static Target forViewer(Predicate<Viewer> allowed, Target target) {
    return (entityType, entityId, viewer) ->
        allowed.test(viewer) ? target.resolve(entityType, entityId, viewer) : null;
  }

  /**
   * A path built around the subject's id.
   *
   * @param entityType the subject's type tag the notification must carry
   * @param prefix the path before the id
   * @param suffix the path after the id
   * @param fallback the path for another tag or a missing id, or {@code null} for no link
   * @return the target
   */
  private static Target item(
      String entityType, String prefix, String suffix, @Nullable String fallback) {
    return (actualType, entityId, viewer) ->
        entityId != null && entityType.equals(actualType) ? prefix + entityId + suffix : fallback;
  }

  /** Resolves one notification type's target. */
  @FunctionalInterface
  interface Target {

    /**
     * Resolves the target of one notification.
     *
     * @param entityType the subject's type tag
     * @param entityId the subject's id
     * @param viewer the roles of the member looking at the notification
     * @return a same-origin path, or {@code null} for no link
     */
    @Nullable
    String resolve(@Nullable String entityType, @Nullable UUID entityId, @NotNull Viewer viewer);
  }

  /**
   * The roles of the member looking at a notification that decide which pages they can open.
   *
   * @param admin whether the member is an admin
   * @param bankStaff whether the member is a bank employee or above
   */
  record Viewer(boolean admin, boolean bankStaff) {

    /**
     * Derives the viewer from the member's reachable authorities, the role hierarchy applied.
     *
     * @param authorities the reachable granted authorities
     * @return the viewer
     */
    static @NotNull Viewer of(@NotNull Collection<? extends GrantedAuthority> authorities) {
      Set<String> names =
          authorities.stream()
              .map(GrantedAuthority::getAuthority)
              .collect(Collectors.toUnmodifiableSet());
      return new Viewer(
          names.contains(Roles.authority(Roles.ADMIN)),
          names.contains(Roles.authority(Roles.BANK_EMPLOYEE)));
    }
  }
}
