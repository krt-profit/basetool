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

package de.greluc.krt.profit.basetool.backend.support;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The topic rooms the app's live-sync bridge admits, and what each one is allowed to carry
 * (ADR-0143).
 *
 * <p>This is the backend's half of a registry the frontend also holds ({@code
 * frontend/…/websocket/LiveSyncTopicClass}, ADR-0094). The two are deliberately <em>not</em> shared
 * code — the modules have no dependency on one another — and they are deliberately not independent
 * either: both ends publish onto the same Redis channel with the same payload, so a prefix or a
 * section key that exists on one side and not the other silently strands a screen on the old data.
 * {@code LiveSyncTopicRegistryParityTest} reads the frontend's source and fails the build when this
 * enum names a prefix or section the frontend does not.
 *
 * <p>Only the classes the app actually subscribes to are listed. The frontend's staff-only rooms
 * ({@code bank} without an id, {@code members}, {@code org-structure}) are absent on purpose: the
 * admin area is web-only permanently (app plan Q7), so admitting them here would open a room with
 * no reader.
 *
 * @see LiveSyncTopic the parsed form, which resolves a wire string onto one of these
 */
public enum LiveSyncTopicClass {

  /**
   * Per-Einsatz room, the Einsatz detail. Presence-bearing on the web (editor dots); the bridge
   * relays only {@code changed} for it and never a presence frame, which is why it may be
   * subscribed here at all — ADR-0094 fails this class closed precisely because a web subscribe
   * emits a presence snapshot, and this one cannot.
   */
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
      "mission",
      LiveSyncAuthorization.MISSION),

  /** Global Einsatz-list room: a peer's create, core edit or delete invalidates the list. */
  MISSIONS_LIST("missions", false, Set.of("list"), "missions_list", LiveSyncAuthorization.MEMBER),

  /**
   * Per-Operation room. {@code missions} and {@code finance} are cross-published from an Einsatz.
   */
  OPERATION(
      "operation",
      true,
      Set.of("overview", "missions", "payout", "finance"),
      "operation",
      LiveSyncAuthorization.OPERATION),

  /** Per-Auftrag room, the job-order detail. */
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
      "order_detail",
      LiveSyncAuthorization.JOB_ORDER),

  /**
   * Global Auftrags-queue room. Distinct from {@link #ORDER} despite the shared wire stem: the two
   * are told apart by whether the topic carries an id, and they keep separate metric labels so the
   * ops dashboard never folds them into one series.
   */
  ORDERS_QUEUE(
      "orders",
      false,
      Set.of("queue", "demand"),
      "orders_queue",
      LiveSyncAuthorization.JOB_ORDER_QUEUE),

  /** Per-Raffinerie-Order room, the refinery-order detail and its Einlagern dialog. */
  REFINERY_ORDER(
      "refinery-order",
      true,
      Set.of("order", "store"),
      "refinery_order",
      LiveSyncAuthorization.REFINERY_ORDER),

  /** Global Raffinerie-queue room: the member's own order list. */
  REFINERY("refinery", false, Set.of("queue"), "refinery_queue", LiveSyncAuthorization.MEMBER),

  /**
   * Global shared-Lager room: one opaque key, because every viewer re-reads its own scoped view.
   */
  INVENTORY_ALL("inventory", false, Set.of("stock"), "inventory_all", LiveSyncAuthorization.MEMBER),

  /**
   * Global Materialbörse room: {@code board} for the Angebote, {@code requests} for the Gesuche.
   */
  MATERIALBOARD(
      "materialboard",
      false,
      Set.of("board", "requests"),
      "materialboard",
      LiveSyncAuthorization.MEMBER),

  /**
   * Per-account Kartellbank room. The bare {@code bank} prefix — the frontend's staff room — is
   * absent here, so a topic of {@code bank} with no id resolves to nothing and is refused.
   */
  BANK_ACCOUNT(
      "bank",
      true,
      Set.of("account", "bookings", "chart"),
      "bank_account",
      LiveSyncAuthorization.BANK_ACCOUNT),

  /** Global org-unit bank room: the member-facing account overview and its settings region. */
  ORGUNIT_BANK(
      "orgunit-bank",
      false,
      Set.of("orgUnitBank", "orgUnitBankSettings"),
      "orgunit_bank",
      LiveSyncAuthorization.MEMBER);

  /**
   * Upper bound on the sections a single frame may name, before the class whitelist is applied.
   *
   * <p>Sits above every class's whitelist, so it never clips a legitimate frame; it exists to bound
   * the parse of a crafted oversized array. Same value and same reasoning as ADR-0094's {@code
   * MAX_CHANGED_SECTIONS}.
   */
  public static final int MAX_SECTIONS_PER_FRAME = 16;

  private final String prefix;
  private final boolean perResource;
  private final Set<String> allowedSections;
  private final String metricLabel;
  private final LiveSyncAuthorization authorization;

  LiveSyncTopicClass(
      @NotNull String prefix,
      boolean perResource,
      @NotNull Set<String> allowedSections,
      @NotNull String metricLabel,
      @NotNull LiveSyncAuthorization authorization) {
    this.prefix = prefix;
    this.perResource = perResource;
    this.allowedSections = allowedSections;
    this.metricLabel = metricLabel;
    this.authorization = authorization;
  }

  /**
   * Resolves a wire prefix onto a class.
   *
   * <p>Both the prefix and whether an id is present have to match, which is what keeps the four
   * colliding stems apart: {@code mission}/{@code missions}, {@code order}/{@code orders}, {@code
   * refinery-order}/{@code refinery}, and {@code bank} with an id versus without one.
   *
   * @param prefix the wire prefix, the part before the first colon
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
   * <p>Anything outside the set is dropped from an incoming frame rather than rejecting it: a newer
   * peer naming a section this build does not know must not cost the receiver the sections it does
   * know.
   *
   * @return the immutable whitelist
   */
  @NotNull
  public Set<String> allowedSections() {
    return allowedSections;
  }

  /**
   * Reduces a frame's raw section list to the keys this class admits.
   *
   * <p>Applied on every path a frame can arrive by — a client publish, a peer replica's Redis
   * message — and in that order: the raw list is first bounded by {@link #MAX_SECTIONS_PER_FRAME}
   * so a crafted array cannot make the filter itself expensive, then filtered, then de-duplicated
   * with its order kept so the wire form of one frame is stable.
   *
   * <p>Unknown keys are <em>dropped</em>, never fatal. A peer running a newer build naming a
   * section this one has not heard of must still deliver the sections it does know: the failure
   * mode of rejecting the frame is a screen that stays stale with nothing logged, which is strictly
   * worse than refreshing one region too few.
   *
   * @param raw the sections as they arrived, untrusted and possibly null-bearing
   * @return the admitted keys, possibly empty
   */
  @NotNull
  public List<String> clipSections(@Nullable List<String> raw) {
    if (raw == null || raw.isEmpty()) {
      return List.of();
    }
    LinkedHashSet<String> kept = new java.util.LinkedHashSet<>();
    int examined = 0;
    for (String section : raw) {
      if (examined++ >= MAX_SECTIONS_PER_FRAME) {
        break;
      }
      if (section != null && allowedSections.contains(section)) {
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
