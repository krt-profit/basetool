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
 *   <li>{@link #metricLabel} — the bounded {@code topic_class} metric label value (REQ-OBS-011).
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
      "mission");

  private final String prefix;
  private final boolean scoped;
  private final Set<String> allowedSections;
  private final boolean presenceEnabled;
  private final String metricLabel;

  /**
   * Defines one topic class.
   *
   * @param prefix the wire prefix identifying the class
   * @param scoped {@code true} if a concrete topic carries a resource UUID, {@code false} for a
   *     bare-prefix global room
   * @param allowedSections the section-key whitelist the relay forwards for this class
   * @param presenceEnabled whether this class carries editor-presence dots
   * @param metricLabel the bounded {@code topic_class} metric label value
   */
  LiveSyncTopicClass(
      @NotNull String prefix,
      boolean scoped,
      @NotNull Set<String> allowedSections,
      boolean presenceEnabled,
      @NotNull String metricLabel) {
    this.prefix = prefix;
    this.scoped = scoped;
    this.allowedSections = allowedSections;
    this.presenceEnabled = presenceEnabled;
    this.metricLabel = metricLabel;
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
}
