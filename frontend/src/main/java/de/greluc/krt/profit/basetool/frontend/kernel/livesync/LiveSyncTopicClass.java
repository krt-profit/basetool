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

package de.greluc.krt.profit.basetool.frontend.kernel.livesync;

import de.greluc.krt.profit.basetool.frontend.kernel.security.Roles;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The bounded set of live-sync topic classes for the peer-sync relay (REQ-FE-015, ADR-0094).
 *
 * <p>Each class fixes its wire {@link #prefix}, whether it is {@link #scoped}, the {@link
 * #allowedSections} whitelist (kept in lockstep with the page's JS seam map, REQ-FE-010), whether
 * {@link #presenceEnabled}, its {@link #metricLabel}, and how a subscribe is authorized ({@link
 * #authProbePath}, {@link #capabilityField}).
 */
@RequiredArgsConstructor
public enum LiveSyncTopicClass {

  /** Per-mission room: the mission detail page. Carries editor-presence dots. */
  MISSION(
      "mission",
      true,
      Set.of(
          "crew",
          "finance",
          "mgmt",
          "overview",
          "steps",
          "objectives",
          "frequencies",
          "organisation"),
      true,
      "mission",
      "/api/v1/missions/{id}",
      null),

  /**
   * Per-operation room for the operation detail page, authorized by {@code GET
   * /api/v1/operations/{id}}; sections {@code overview}, {@code payout}, {@code missions} and
   * {@code finance}.
   */
  OPERATION(
      "operation",
      true,
      Set.of("overview", "missions", "payout", "finance"),
      false,
      "operation",
      "/api/v1/operations/{id}",
      null),

  /**
   * Per-order room for the job-order detail page, authorized by {@code GET /api/v1/orders/{id}};
   * distinct from {@link #ORDERS_QUEUE}, with metric label {@code order_detail}.
   */
  ORDER(
      "order",
      true,
      Set.of(
          "header",
          "kpi",
          "materials",
          "aggregated",
          "items",
          "item-stock",
          "handovers",
          "item-handovers",
          "item-handover-lines",
          "blueprint-owners",
          "assignees"),
      false,
      "order_detail",
      "/api/v1/orders/{id}",
      null),

  /**
   * Global job-order queue room, authorized by the {@code canViewJobOrders} or the {@code
   * canViewAssignedJobOrders} capability; its frames carry only a section key, and each viewer
   * refreshes their own scoped list, so an assignee learns of no order beyond their own. Sections
   * {@code queue} (the order list) and {@code demand} (the material-demand overview,
   * REQ-ORDERS-034).
   */
  ORDERS_QUEUE(
      "orders",
      false,
      Set.of("queue", "demand"),
      false,
      "orders_queue",
      "/api/v1/me/capabilities",
      "canViewJobOrders|canViewAssignedJobOrders"),

  /**
   * Per-refinery-order room for {@code /refinery-orders/{id}}, authorized by {@code GET
   * /api/v1/refinery-orders/{id}}; sections {@code order} and {@code store}.
   */
  REFINERY_ORDER(
      "refinery-order",
      true,
      Set.of("order", "store"),
      false,
      "refinery_order",
      "/api/v1/refinery-orders/{id}",
      null),

  /**
   * Per-account bank room for the staff and org-unit account detail pages; a subscribe is denied
   * only when both the staff read and the org-unit {@link #fallbackProbePath} refuse. Shares the
   * {@code bank} prefix with {@link #BANK_STAFF}.
   */
  BANK_ACCOUNT(
      "bank",
      true,
      Set.of("account", "bookings", "chart"),
      false,
      "bank_account",
      "/api/v1/bank/accounts/{id}",
      null,
      "/api/v1/bank/org-units/accounts/{id}",
      null),

  /**
   * Global bank-staff room for {@code /bank}, {@code /bank/requests}, {@code /bank/manage} and
   * {@code /bank/grants}, authorized locally by {@code ROLE_BANK_EMPLOYEE} or {@code
   * ROLE_BANK_MANAGEMENT} ({@link #requiredAnyRole}).
   */
  BANK_STAFF(
      "bank",
      false,
      Set.of("grid", "requestQueue", "manage", "grants"),
      false,
      "bank_staff",
      null,
      null,
      null,
      Set.of(Roles.authority(Roles.BANK_EMPLOYEE), Roles.authority(Roles.BANK_MANAGEMENT))),

  /**
   * Global org-unit bank room for the {@code /org-unit-bank} overview and account settings,
   * authorized locally by a member-or-above role ({@link Roles#MEMBER_AUTHORITIES}).
   */
  ORGUNIT_BANK(
      "orgunit-bank",
      false,
      Set.of("orgUnitBank", "orgUnitBankSettings"),
      false,
      "orgunit_bank",
      null,
      null,
      null,
      Roles.MEMBER_AUTHORITIES),

  /**
   * Global Materialbörse room (REQ-MARKET-010/018) with sections {@code board} and {@code
   * requests}, authorized by the socket's authentication alone.
   */
  MATERIALBOARD(
      "materialboard", false, Set.of("board", "requests"), false, "materialboard", null, null),

  /**
   * Global shared-Lager room for {@code /inventory/all} (REQ-INV-027) with the {@code stock}
   * section and the minimum stock levels' {@code stock-levels} section (REQ-INV-068), authorized by
   * the socket's authentication alone.
   */
  INVENTORY_ALL(
      "inventory", false, Set.of("stock", "stock-levels"), false, "inventory_all", null, null),

  /**
   * A member's own hangar room for {@code /hangar} and the admin member hangar, keyed by the
   * member's id, with the {@code ships} section; that member or an admin may subscribe ({@link
   * #personal}, {@link #requiredAnyRole}).
   */
  HANGAR_OWN(
      "hangar",
      true,
      Set.of("ships"),
      false,
      "hangar_own",
      null,
      null,
      null,
      Set.of(Roles.authority(Roles.ADMIN)),
      true),

  /**
   * One org unit's hangar overview room for {@code /hangar/squadron} with a pinned unit, keyed by
   * the unit's id, with the {@code ships} section; authorized by the backend's unit-overview gate
   * (ADR-0260).
   */
  HANGAR_UNIT(
      "hangar-unit",
      true,
      Set.of("ships"),
      false,
      "hangar_unit",
      "/api/v1/hangar/units/{id}/overview-access",
      null),

  /**
   * The hangar overview room across every unit the viewer sees, for {@code /hangar/squadron}
   * without a pinned unit, with the {@code ships} section; authorized by the socket's
   * authentication alone, each refresh reading the viewer's own scope (ADR-0260).
   */
  HANGAR_UNITS("hangar-units", false, Set.of("ships"), false, "hangar_units", null, null),

  /**
   * A member's own blueprints room for „Meine Blueprints", keyed by the member's id, with the
   * {@code list} section; only that member may subscribe ({@link #personal}).
   */
  BLUEPRINTS_OWN(
      "blueprints", true, Set.of("list"), false, "blueprints_own", null, null, null, null, true),

  /**
   * Global mission-list room for {@code /missions} with the {@code list} section, authorized by the
   * socket's authentication alone; distinct from the per-mission {@link #MISSION} room.
   */
  MISSIONS_LIST("missions", false, Set.of("list"), false, "missions_list", null, null),

  /**
   * Global refinery-order queue room for {@code /refinery-orders} with the {@code queue} section,
   * authorized by the socket's authentication alone.
   */
  REFINERY("refinery", false, Set.of("queue"), false, "refinery_queue", null, null),

  /**
   * Global member-roster room for {@code /members} with the {@code roster} section, authorized
   * locally by {@code ROLE_ADMIN} ({@link #requiredAnyRole}).
   */
  MEMBERS(
      "members",
      false,
      Set.of("roster"),
      false,
      "members_roster",
      null,
      null,
      null,
      Set.of(Roles.authority(Roles.ADMIN))),

  /**
   * Global org-structure room shared by {@code /admin/org-structure} and {@code /org-chart}, with
   * sections {@code units}, {@code forms} and {@code chart}; authorized by the socket's
   * authentication alone, the admin sections staying protected per fragment.
   */
  ORG_STRUCTURE(
      "org-structure",
      false,
      Set.of("units", "forms", "chart"),
      false,
      "org_structure",
      null,
      null),

  /**
   * Global admin room for {@code /admin/exchange-clients} with sections {@code registry} (the
   * switch and the client table) and {@code undoRuns} (the bulk undo runs, REQ-XCH-034), authorized
   * locally by {@code ROLE_ADMIN} ({@link #requiredAnyRole}).
   */
  EXCHANGE_CLIENTS(
      "exchange-clients",
      false,
      Set.of("registry", "undoRuns"),
      false,
      "exchange_clients",
      null,
      null,
      null,
      Set.of(Roles.authority(Roles.ADMIN))),

  /**
   * Global supply room for {@code /supply/needs}, {@code /supply/donations/mine} and the statistics
   * with the sections {@code needs}, {@code mine} and {@code stats}, authorized by the socket's
   * authentication alone.
   */
  SUPPLY_NEEDS(
      "supply-needs", false, Set.of("needs", "mine", "stats"), false, "supply_needs", null, null),

  /**
   * A receiving unit's donation room for {@code /supply/donations/incoming}, keyed by the unit's
   * id, with the {@code donations} section; authorized by the donation list read of that unit,
   * which only the unit's managers and the governing leadership pass.
   */
  SUPPLY_DONATIONS(
      "supply-donations",
      true,
      Set.of("donations"),
      false,
      "supply_donations",
      "/api/v1/supply/donations?orgUnitId={id}&size=1",
      null),

  /**
   * Global purchasing room for {@code /purchasing/deals}, the deal page, the partners and the
   * delegates with the section {@code deals}, authorized by the socket's authentication alone; each
   * refresh reads through the backend's own gates.
   */
  PURCHASING_DEALS(
      "purchasing-deals", false, Set.of("deals"), false, "purchasing_deals", null, null);

  /** The wire prefix identifying the class. */
  private final @NotNull String prefix;

  /**
   * {@code true} if a concrete topic carries a resource UUID, {@code false} for a bare-prefix
   * global room.
   */
  private final boolean scoped;

  /** The section-key whitelist the relay forwards for this class. */
  private final @NotNull Set<String> allowedSections;

  /** Whether this class carries editor-presence dots. */
  private final boolean presenceEnabled;

  /** The bounded {@code topic_class} metric label value. */
  private final @NotNull String metricLabel;

  /**
   * The authenticated backend read that authorizes a subscribe, or {@code null} when the socket
   * authentication (or a {@link #requiredAnyRole} local check) authorizes it.
   */
  private final @Nullable String authProbePath;

  /**
   * For a global class authorized by a capability, the boolean field of the {@link #authProbePath}
   * response that must be {@code true}, or several such fields joined by {@link
   * #CAPABILITY_ANY_SEPARATOR} of which one must be; {@code null} otherwise.
   */
  private final @Nullable String capabilityField;

  /** Separates alternative capability fields of which one grants the subscribe. */
  static final String CAPABILITY_ANY_SEPARATOR = "\\|";

  /**
   * A second per-resource read tried when the {@link #authProbePath} explicitly refuses (403/404),
   * so a subscribe is denied only when both refuse; {@code null} when the class has no fallback
   * read.
   */
  private final @Nullable String fallbackProbePath;

  /**
   * For a global class authorized by a <b>local</b> role check, the set of authorities of which the
   * caller must hold at least one (matched against the authorities captured at handshake, no
   * backend call); {@code null} when the class is not locally role-gated.
   */
  private final @Nullable Set<String> requiredAnyRole;

  /**
   * Whether the topic is a member's personal room: the member whose id it carries may subscribe,
   * and otherwise only a holder of {@link #requiredAnyRole}, checked against the socket's subject
   * and authorities with no backend call and failing closed.
   */
  private final boolean personal;

  /**
   * Defines one topic class that is not a member's personal room.
   *
   * @param prefix the wire prefix identifying the class
   * @param scoped {@code true} if a concrete topic carries a resource UUID
   * @param allowedSections the section-key whitelist the relay forwards
   * @param presenceEnabled whether this class carries editor-presence dots
   * @param metricLabel the bounded {@code topic_class} metric label value
   * @param authProbePath the backend read that authorizes a subscribe, or {@code null}
   * @param capabilityField the capability flag of the {@link #authProbePath} response that must be
   *     {@code true}, or {@code null}
   * @param fallbackProbePath the second per-resource read, or {@code null}
   * @param requiredAnyRole the authorities of which the caller must hold one, or {@code null}
   */
  LiveSyncTopicClass(
      @NotNull String prefix,
      boolean scoped,
      @NotNull Set<String> allowedSections,
      boolean presenceEnabled,
      @NotNull String metricLabel,
      @Nullable String authProbePath,
      @Nullable String capabilityField,
      @Nullable String fallbackProbePath,
      @Nullable Set<String> requiredAnyRole) {
    this(
        prefix,
        scoped,
        allowedSections,
        presenceEnabled,
        metricLabel,
        authProbePath,
        capabilityField,
        fallbackProbePath,
        requiredAnyRole,
        false);
  }

  /**
   * Defines one topic class.
   *
   * @param prefix the wire prefix identifying the class
   * @param scoped {@code true} if a concrete topic carries a resource UUID
   * @param allowedSections the section-key whitelist the relay forwards
   * @param presenceEnabled whether this class carries editor-presence dots
   * @param metricLabel the bounded {@code topic_class} metric label value
   * @param authProbePath the backend read that authorizes a subscribe (per-resource with an {@code
   *     {id}} placeholder, or the capabilities endpoint), or {@code null} when authentication alone
   *     suffices
   * @param capabilityField the capability flag of the {@link #authProbePath} response that must be
   *     {@code true}, or {@code null}
   */
  LiveSyncTopicClass(
      @NotNull String prefix,
      boolean scoped,
      @NotNull Set<String> allowedSections,
      boolean presenceEnabled,
      @NotNull String metricLabel,
      @Nullable String authProbePath,
      @Nullable String capabilityField) {
    this(
        prefix,
        scoped,
        allowedSections,
        presenceEnabled,
        metricLabel,
        authProbePath,
        capabilityField,
        null,
        null);
  }

  /**
   * Returns the wire prefix identifying this class (e.g. {@code mission}).
   *
   * @return the prefix
   */
  @NotNull
  public String prefix() {
    return prefix;
  }

  /**
   * Names the room of one resource of this class.
   *
   * @param resourceId the resource's id text
   * @return {@code <prefix>:<resourceId>}
   */
  @NotNull
  public String topic(@NotNull String resourceId) {
    return prefix + ":" + resourceId;
  }

  /**
   * Reports whether a concrete topic of this class carries a resource UUID (a resource room) rather
   * than being the bare prefix (a global room).
   *
   * @return {@code true} for a resource-scoped class, {@code false} for a global one
   */
  public boolean scoped() {
    return scoped;
  }

  /**
   * Returns the whitelist of section keys the relay accepts and forwards for this class.
   *
   * @return the immutable section-key whitelist
   */
  @NotNull
  public Set<String> allowedSections() {
    return allowedSections;
  }

  /**
   * Reports whether this class carries editor-presence (focus/blur/heartbeat) dots.
   *
   * @return {@code true} if presence frames are accepted for this class
   */
  public boolean presenceEnabled() {
    return presenceEnabled;
  }

  /**
   * Returns the bounded {@code topic_class} metric label for this class (REQ-OBS-011).
   *
   * @return the metric label value
   */
  @NotNull
  public String metricLabel() {
    return metricLabel;
  }

  /**
   * Returns the authenticated backend read that authorizes a {@code /ws/sync} subscribe to a
   * concrete topic of this class, with a {@code {id}} placeholder for the resource UUID, or {@code
   * null} for a global room (authorized by the socket authentication alone).
   *
   * @return the subscribe-authorization probe path template, or {@code null}
   */
  @Nullable
  public String authProbePath() {
    return authProbePath;
  }

  /**
   * For a global class authorized by a capability, the boolean field of the {@link #authProbePath}
   * response that must be {@code true} for a subscribe to be allowed; {@code null} for a scoped
   * class or a global class authorized by authentication alone.
   *
   * @return the required capability field name, or {@code null}
   */
  @Nullable
  public String capabilityField() {
    return capabilityField;
  }

  /**
   * Returns a second authorization read tried only when {@link #authProbePath} refuses with
   * 403/404; used by {@link #BANK_ACCOUNT}.
   *
   * @return the fallback probe path template, or {@code null} when the class has none
   */
  @Nullable
  public String fallbackProbePath() {
    return fallbackProbePath;
  }

  /**
   * Returns the authorities of which the caller must hold one for a local, backend-free subscribe
   * authorization.
   *
   * @return the any-of required authority set, or {@code null} when the class is not role-gated
   */
  @Nullable
  public Set<String> requiredAnyRole() {
    return requiredAnyRole;
  }

  /**
   * Reports whether the topic is a member's personal room, admitting that member and, when {@link
   * #requiredAnyRole} is set, a holder of one of those authorities.
   *
   * @return {@code true} for a member's personal room
   */
  public boolean personal() {
    return personal;
  }

  /**
   * Reports whether a subscribe needs an authorization check beyond the socket's own
   * authentication: a backend probe, a local role check or the personal-room subject check.
   *
   * @return {@code false} only for a class authorized by the socket's authentication alone
   */
  public boolean needsCheck() {
    return authProbePath != null || requiredAnyRole != null || personal;
  }
}
