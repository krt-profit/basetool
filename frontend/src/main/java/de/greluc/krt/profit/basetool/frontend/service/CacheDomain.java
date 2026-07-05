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

package de.greluc.krt.profit.basetool.frontend.service;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Invalidation domain of a cached backend catalogue (FE-CACHE-2). Each domain owns its own named
 * Caffeine cache so a mutation of one catalogue evicts only that catalogue — a squadron toggle no
 * longer cold-starts the (10&nbsp;000-row) terminal list, the material lists or the location lists
 * it never touched. The previous design kept every catalogue in one {@code STATIC_DATA_CACHE} that
 * any admin mutation dropped wholesale.
 *
 * <p>The cache name is the {@code Prometheus} {@code cache} label (REQ-OBS-006 — bounded), so it is
 * a fixed literal, never derived from user input. Every domain's cache is registered in {@code
 * CacheConfig} with {@code recordStats()} so the hit-ratio / eviction / size panels light up per
 * domain automatically.
 */
@Getter
@RequiredArgsConstructor
public enum CacheDomain {
  /** Squadron + Spezialkommando catalogues (the admin switcher's org-unit lists). */
  SQUADRON("squadronCatalogue"),
  /** Active-org-unit owner pickers (Staffel/SK and the all-kinds Staffel/SK/Bereich/OL variant). */
  ORG_UNIT("orgUnitCatalogue"),
  /** Material catalogues (full list, lookup, job-order subset, price matrix). */
  MATERIAL("materialCatalogue"),
  /** Location catalogues (full list, lookup, home locations, refineries). */
  LOCATION("locationCatalogue"),
  /** Ship-type catalogues. */
  SHIP_TYPE("shipTypeCatalogue"),
  /** Refining-method catalogue. */
  REFINING_METHOD("refiningMethodCatalogue"),
  /** Job-type catalogues (mission / crew archetypes). */
  JOB_TYPE("jobTypeCatalogue"),
  /** Frequency-type catalogue. */
  FREQUENCY_TYPE("frequencyTypeCatalogue"),
  /** Terminal catalogue (the large price-matrix source). */
  TERMINAL("terminalCatalogue"),
  /** Manufacturer catalogue. */
  MANUFACTURER("manufacturerCatalogue"),
  /** Orderable-item reference catalogue. */
  ITEM_CATALOG("itemCatalogue"),
  /** Global system settings surfaced on the orders pages (job-order age thresholds). */
  SETTINGS("settingsCatalogue");

  /**
   * The registered Caffeine cache name backing this domain; also the Prometheus {@code cache}
   * label.
   */
  private final String cacheName;
}
