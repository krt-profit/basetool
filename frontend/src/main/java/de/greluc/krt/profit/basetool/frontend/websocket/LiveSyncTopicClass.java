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

package de.greluc.krt.profit.basetool.frontend.websocket;

import java.util.Set;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The bounded set of live-sync topic <em>classes</em> — the single source of truth for the
 * tool-wide peer-sync relay (REQ-FE-015, ADR-0092).
 *
 * <p>A concrete topic string is either the bare {@link #prefix} (a global room, e.g. {@code
 * orders}) or {@code prefix:{uuid}} (a per-resource room, e.g. {@code mission:5f…}). Each class
 * fixes:
 *
 * <ul>
 *   <li>{@link #prefix} — the wire prefix that identifies the class;
 *   <li>{@link #scoped} — whether a concrete topic of this class carries a resource UUID (a
 *       resource room) or is the bare prefix (a global room);
 *   <li>{@link #allowedSections} — the whitelist of section keys the relay accepts and forwards for
 *       this class. Anything else in an inbound {@code changed} frame is dropped, so a client can
 *       never make peers re-fetch an arbitrary URL. This set MUST stay in lockstep with the page's
 *       JS seam map (the REQ-FE-010 three-mirror-points rule, build-enforced by {@code
 *       LiveSyncSectionMapParityTest});
 *   <li>{@link #presenceEnabled} — whether this class carries editor-presence (focus/blur/heartbeat
 *       dots). Only the mission surface does today;
 *   <li>{@link #metricLabel} — the bounded {@code topic_class} metric label value (REQ-OBS-011);
 *   <li>{@link #authProbePath} — for a resource-scoped class, the authenticated backend read whose
 *       success authorizes a {@code /ws/sync} <em>subscribe</em> to a concrete topic of this class
 *       (the {@code {id}} placeholder is replaced with the topic's resource UUID). {@code null} for
 *       a global room, which is authorized by the socket's authentication alone (a per-role local
 *       check is layered on when such a class ships). See {@code LiveSyncSubscriptionAuthorizer}.
 * </ul>
 *
 * <p>Note that {@code bank} and {@code bank:{accountId}} deliberately share the {@link #prefix}
 * {@code bank}: the bare prefix resolves to {@link #BANK_STAFF} (the staff-wide room) and the
 * prefixed form to {@link #BANK_ACCOUNT} (a per-account room). {@link LiveSyncTopic#parse(String)}
 * disambiguates them by the presence of the id segment.
 */
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
      "/api/v1/missions/{id}"),

  /**
   * Per-operation room: the operation detail page (#1115). No editor-presence dots; a subscribe is
   * authorized by the same authenticated {@code GET /api/v1/operations/{id}} the page performs.
   */
  OPERATION(
      "operation",
      true,
      Set.of("overview", "missions", "payout", "finance"),
      false,
      "operation",
      "/api/v1/operations/{id}");

  private final String prefix;
  private final boolean scoped;
  private final Set<String> allowedSections;
  private final boolean presenceEnabled;
  private final String metricLabel;
  private final String authProbePath;

  /**
   * Defines one topic class.
   *
   * @param prefix the wire prefix identifying the class
   * @param scoped {@code true} if a concrete topic carries a resource UUID, {@code false} for a
   *     bare-prefix global room
   * @param allowedSections the section-key whitelist the relay forwards for this class
   * @param presenceEnabled whether this class carries editor-presence dots
   * @param metricLabel the bounded {@code topic_class} metric label value
   * @param authProbePath the authenticated backend read that authorizes a subscribe to a concrete
   *     topic of this class ({@code {id}} → resource UUID), or {@code null} for a global room
   */
  LiveSyncTopicClass(
      @NotNull String prefix,
      boolean scoped,
      @NotNull Set<String> allowedSections,
      boolean presenceEnabled,
      @NotNull String metricLabel,
      @Nullable String authProbePath) {
    this.prefix = prefix;
    this.scoped = scoped;
    this.allowedSections = allowedSections;
    this.presenceEnabled = presenceEnabled;
    this.metricLabel = metricLabel;
    this.authProbePath = authProbePath;
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
}
