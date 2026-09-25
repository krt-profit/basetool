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

import java.time.Duration;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Invalidation domain of a cached backend catalogue, each with its own named Caffeine cache so a
 * mutation evicts only its own catalogue.
 *
 * <p>Each domain's TTL is only a backstop, since every mutation evicts (REQ-DATA-007): 6 hours for
 * pure reference catalogues, 2 hours for the org-structure catalogues and global settings. The
 * cache name is the bounded Prometheus {@code cache} label (REQ-OBS-006).
 */
@Getter
@RequiredArgsConstructor
public enum CacheDomain {
  /** Squadron + Spezialkommando catalogues (the admin switcher's org-unit lists). */
  SQUADRON("squadronCatalogue", Duration.ofHours(2)),
  /** Active-org-unit owner pickers (Staffel/SK and the all-kinds Staffel/SK/Bereich/OL variant). */
  ORG_UNIT("orgUnitCatalogue", Duration.ofHours(2)),
  /** Material catalogues (full list, lookup, job-order subset, price matrix). */
  MATERIAL("materialCatalogue", Duration.ofHours(6)),
  /** Location catalogues (full list, lookup, home locations, refineries). */
  LOCATION("locationCatalogue", Duration.ofHours(6)),
  /** Ship-type catalogues. */
  SHIP_TYPE("shipTypeCatalogue", Duration.ofHours(6)),
  /** Refining-method catalogue. */
  REFINING_METHOD("refiningMethodCatalogue", Duration.ofHours(6)),
  /** Job-type catalogues (mission / crew archetypes). */
  JOB_TYPE("jobTypeCatalogue", Duration.ofHours(6)),
  /** Frequency-type catalogue. */
  FREQUENCY_TYPE("frequencyTypeCatalogue", Duration.ofHours(6)),
  /** Terminal catalogue (the large price-matrix source). */
  TERMINAL("terminalCatalogue", Duration.ofHours(6)),
  /** Manufacturer catalogue. */
  MANUFACTURER("manufacturerCatalogue", Duration.ofHours(6)),
  /** Orderable-item reference catalogue. */
  ITEM_CATALOG("itemCatalogue", Duration.ofHours(6)),
  /** Global system settings surfaced on the orders pages (job-order age thresholds). */
  SETTINGS("settingsCatalogue", Duration.ofHours(2));

  /**
   * The registered Caffeine cache name backing this domain; also the Prometheus {@code cache}
   * label.
   */
  private final String cacheName;

  /** The {@code expireAfterWrite} TTL (backstop) for this domain's cache. */
  private final Duration ttl;
}
