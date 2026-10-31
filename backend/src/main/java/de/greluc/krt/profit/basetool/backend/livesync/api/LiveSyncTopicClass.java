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
 * The rooms the backend itself publishes into after a committed write, and the sections each may
 * carry (ADR-0274).
 *
 * <p>Every constant must name a room the frontend's {@code LiveSyncTopicClass} also knows, with the
 * same prefix, arity and section keys; {@code LiveSyncTopicRegistryParityTest} enforces that. A
 * room only this side knows is a room whose frames the browser clips away on arrival.
 */
@RequiredArgsConstructor
public enum LiveSyncTopicClass {

  /**
   * Global shared-Lager room: one opaque key, because every viewer re-reads its own scoped view.
   */
  INVENTORY_ALL("inventory", false, "inventory_all"),

  /** A member's own hangar, keyed by the member's id. */
  HANGAR_OWN("hangar", true, "hangar_own"),

  /** One org unit's hangar overview, keyed by the unit's id (ADR-0260). */
  HANGAR_UNIT("hangar-unit", true, "hangar_unit"),

  /**
   * The hangar overview across every unit the viewer can see: one opaque key that every ship change
   * pokes, because each viewer re-reads its own scoped view (ADR-0260).
   */
  HANGAR_UNITS("hangar-units", false, "hangar_units"),

  /** A member's own blueprints, keyed by the member's id. */
  BLUEPRINTS_OWN("blueprints", true, "blueprints_own"),

  /** Global Materialbörse room; the backend pokes {@code board} when a book-out changed offers. */
  MATERIALBOARD("materialboard", false, "materialboard");

  private static final Set<String> INVENTORY_ALL_SECTIONS = Set.of("stock", "stock-levels");
  private static final Set<String> SHIPS_SECTIONS = Set.of("ships");
  private static final Set<String> BLUEPRINTS_OWN_SECTIONS = Set.of("list");
  private static final Set<String> MATERIALBOARD_SECTIONS = Set.of("board");

  private final @NotNull String prefix;
  private final boolean perResource;
  private final @NotNull String metricLabel;

  /**
   * Returns the wire prefix, the part of a topic string before the first colon.
   *
   * @return the prefix, e.g. {@code hangar-unit}
   */
  @NotNull
  public String prefix() {
    return prefix;
  }

  /**
   * Tells whether a topic of this class names one resource by id.
   *
   * @return {@code true} for a per-resource room such as {@code hangar:{id}}, {@code false} for a
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
      case INVENTORY_ALL -> INVENTORY_ALL_SECTIONS;
      case HANGAR_OWN, HANGAR_UNIT, HANGAR_UNITS -> SHIPS_SECTIONS;
      case BLUEPRINTS_OWN -> BLUEPRINTS_OWN_SECTIONS;
      case MATERIALBOARD -> MATERIALBOARD_SECTIONS;
    };
  }

  /**
   * Reduces a frame's raw section list to the admitted keys, filtered and de-duplicated in order.
   * An unknown key is dropped, so a section renamed on one side cannot travel as a key the other
   * side ignores.
   *
   * @param raw the sections as the caller named them, possibly containing nulls
   * @return the admitted keys, possibly empty
   */
  @NotNull
  public List<String> clipSections(@Nullable List<String> raw) {
    if (raw == null || raw.isEmpty()) {
      return List.of();
    }
    LinkedHashSet<String> kept = new LinkedHashSet<>();
    for (String section : raw) {
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
   * two surfaces as the same series (REQ-OBS-011).
   *
   * @return the tag value, e.g. {@code hangar_unit}
   */
  @NotNull
  public String metricLabel() {
    return metricLabel;
  }
}
