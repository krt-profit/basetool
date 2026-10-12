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

package de.greluc.krt.profit.basetool.backend.livesync.api;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The topic rooms the app's live-sync bridge admits and the sections each may carry (ADR-0143).
 *
 * <p>Must stay in step with the frontend's {@code LiveSyncTopicClass}; {@code
 * LiveSyncTopicRegistryParityTest} enforces it.
 */
@RequiredArgsConstructor
public enum LiveSyncTopicClass {

  /**
   * Per-Einsatz room, the Einsatz detail. Presence-bearing on the web (editor dots); the bridge
   * relays only {@code changed} for it and never a presence frame, which is why it may be
   * subscribed here at all — ADR-0094 fails this class closed precisely because a web subscribe
   * emits a presence snapshot, and this one cannot.
   */
  MISSION("mission", true, "mission", LiveSyncAuthorization.MISSION),

  /** Global Einsatz-list room: a peer's create, core edit or delete invalidates the list. */
  MISSIONS_LIST("missions", false, "missions_list", LiveSyncAuthorization.MEMBER),

  /**
   * Per-Operation room. {@code missions} and {@code finance} are cross-published from an Einsatz.
   */
  OPERATION("operation", true, "operation", LiveSyncAuthorization.OPERATION),

  /** Per-Auftrag room, the job-order detail. */
  ORDER("order", true, "order_detail", LiveSyncAuthorization.JOB_ORDER),

  /**
   * Global Auftrags-queue room. Distinct from {@link #ORDER} despite the shared wire stem: the two
   * are told apart by whether the topic carries an id, and they keep separate metric labels so the
   * ops dashboard never folds them into one series.
   */
  ORDERS_QUEUE("orders", false, "orders_queue", LiveSyncAuthorization.JOB_ORDER_QUEUE),

  /** Per-Raffinerie-Order room, the refinery-order detail and its Einlagern dialog. */
  REFINERY_ORDER("refinery-order", true, "refinery_order", LiveSyncAuthorization.REFINERY_ORDER),

  /** Global Raffinerie-queue room: the member's own order list. */
  REFINERY("refinery", false, "refinery_queue", LiveSyncAuthorization.MEMBER),

  /**
   * Global shared-Lager room: one opaque key, because every viewer re-reads its own scoped view.
   */
  INVENTORY_ALL("inventory", false, "inventory_all", LiveSyncAuthorization.MEMBER),

  /** A member's own hangar, keyed by the member's id; only that member may subscribe. */
  HANGAR_OWN("hangar", true, "hangar_own", LiveSyncAuthorization.SELF),

  /** A member's own blueprints, keyed by the member's id; only that member may subscribe. */
  BLUEPRINTS_OWN("blueprints", true, "blueprints_own", LiveSyncAuthorization.SELF),

  /**
   * Global Materialbörse room: {@code board} for the Angebote, {@code requests} for the Gesuche.
   */
  MATERIALBOARD("materialboard", false, "materialboard", LiveSyncAuthorization.MEMBER),

  /**
   * Per-account Kartellbank room. The bare {@code bank} prefix — the frontend's staff room — is
   * absent here, so a topic of {@code bank} with no id resolves to nothing and is refused.
   */
  BANK_ACCOUNT("bank", true, "bank_account", LiveSyncAuthorization.BANK_ACCOUNT),

  /** Global org-unit bank room: the member-facing account overview and its settings region. */
  ORGUNIT_BANK("orgunit-bank", false, "orgunit_bank", LiveSyncAuthorization.MEMBER);

  /**
   * Upper bound on the sections a single frame may name, before the class whitelist is applied.
   *
   * <p>Sits above every class's whitelist, so it never clips a legitimate frame; it exists to bound
   * the parse of a crafted oversized array. Same value and same reasoning as ADR-0094's {@code
   * MAX_CHANGED_SECTIONS}.
   */
  public static final int MAX_SECTIONS_PER_FRAME = 16;

  private static final Set<String> MISSION_SECTIONS =
      Set.of(
          "crew",
          "finance",
          "mgmt",
          "overview",
          "steps",
          "objectives",
          "frequencies",
          "organisation");
  private static final Set<String> MISSIONS_LIST_SECTIONS = Set.of("list");
  private static final Set<String> OPERATION_SECTIONS =
      Set.of("overview", "missions", "payout", "finance");
  private static final Set<String> ORDER_SECTIONS =
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
          "assignees");
  private static final Set<String> ORDERS_QUEUE_SECTIONS = Set.of("queue", "demand");
  private static final Set<String> REFINERY_ORDER_SECTIONS = Set.of("order", "store");
  private static final Set<String> REFINERY_SECTIONS = Set.of("queue");
  private static final Set<String> INVENTORY_ALL_SECTIONS = Set.of("stock");
  private static final Set<String> HANGAR_OWN_SECTIONS = Set.of("ships");
  private static final Set<String> BLUEPRINTS_OWN_SECTIONS = Set.of("list");
  private static final Set<String> MATERIALBOARD_SECTIONS = Set.of("board", "requests");
  private static final Set<String> BANK_ACCOUNT_SECTIONS = Set.of("account", "bookings", "chart");
  private static final Set<String> ORGUNIT_BANK_SECTIONS =
      Set.of("orgUnitBank", "orgUnitBankSettings");

  private final @NotNull String prefix;
  private final boolean perResource;
  private final @NotNull String metricLabel;
  private final @NotNull LiveSyncAuthorization authorization;

  /**
   * Resolves a wire prefix and the presence of an id onto a class.
   *
   * @param prefix the wire prefix before the first colon
   * @param withId whether the topic carried a resource id
   * @return the matching class, or {@code null} if no class admits that combination
   */
  @Nullable
  public static LiveSyncTopicClass resolve(@NotNull String prefix, boolean withId) {
    for (LiveSyncTopicClass candidate : values()) {
      if (candidate.perResource == withId && candidate.prefix.equals(prefix)) {
        return candidate;
      }
    }
    return null;
  }

  /**
   * Returns the wire prefix, the part of a topic string before the first colon.
   *
   * @return the prefix, e.g. {@code refinery-order}
   */
  @NotNull
  public String prefix() {
    return prefix;
  }

  /**
   * Tells whether a topic of this class names one resource by id.
   *
   * @return {@code true} for a per-resource room such as {@code mission:{id}}, {@code false} for a
   *     global one such as {@code materialboard}
   */
  public boolean perResource() {
    return perResource;
  }

  /**
   * Returns the section keys a frame on this class may carry.
   *
   * @return the immutable whitelist
   */
  @NotNull
  public Set<String> allowedSections() {
    return switch (this) {
      case MISSION -> MISSION_SECTIONS;
      case MISSIONS_LIST -> MISSIONS_LIST_SECTIONS;
      case OPERATION -> OPERATION_SECTIONS;
      case ORDER -> ORDER_SECTIONS;
      case ORDERS_QUEUE -> ORDERS_QUEUE_SECTIONS;
      case REFINERY_ORDER -> REFINERY_ORDER_SECTIONS;
      case REFINERY -> REFINERY_SECTIONS;
      case INVENTORY_ALL -> INVENTORY_ALL_SECTIONS;
      case HANGAR_OWN -> HANGAR_OWN_SECTIONS;
      case BLUEPRINTS_OWN -> BLUEPRINTS_OWN_SECTIONS;
      case MATERIALBOARD -> MATERIALBOARD_SECTIONS;
      case BANK_ACCOUNT -> BANK_ACCOUNT_SECTIONS;
      case ORGUNIT_BANK -> ORGUNIT_BANK_SECTIONS;
    };
  }

  /**
   * Reduces a frame's raw section list to the admitted keys: bounded by {@link
   * #MAX_SECTIONS_PER_FRAME}, filtered and de-duplicated in order. Unknown keys are dropped.
   *
   * @param raw the sections as they arrived, untrusted and possibly containing nulls
   * @return the admitted keys, possibly empty
   */
  @NotNull
  public List<String> clipSections(@Nullable List<String> raw) {
    if (raw == null || raw.isEmpty()) {
      return List.of();
    }
    LinkedHashSet<String> kept = new LinkedHashSet<>();
    int examined = 0;
    for (String section : raw) {
      if (examined++ >= MAX_SECTIONS_PER_FRAME) {
        break;
      }
      if (section != null && allowedSections().contains(section)) {
        kept.add(section);
      }
    }
    return List.copyOf(kept);
  }

  /**
   * Returns the bounded value used for the {@code topic_class} metric tag.
   *
   * <p>Distinct per class even where two classes share a wire prefix, so a dashboard never reads
   * the Auftrags-queue and one Auftrag as the same series (REQ-OBS-011).
   *
   * @return the tag value, e.g. {@code order_detail}
   */
  @NotNull
  public String metricLabel() {
    return metricLabel;
  }

  /**
   * Returns the check a caller must pass to subscribe to a room of this class.
   *
   * @return the authorization kind, resolved against the caller by {@code
   *     LiveSyncSubscriptionAuthorizer}
   */
  @NotNull
  public LiveSyncAuthorization authorization() {
    return authorization;
  }
}
