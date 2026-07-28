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

package de.greluc.krt.profit.basetool.backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/** Space Station JPA entity. */
@Entity
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class SpaceStation extends AbstractEntity<UUID> {
  @Getter(onMethod_ = @__(@Override))
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "id_space_station", unique = true)
  private Integer idSpaceStation;

  private String name;
  private String code;

  @Column(name = "is_available_live")
  private Boolean isAvailableLive;

  @Column(name = "nickname")
  private String nickname;

  @Column(name = "star_system_name")
  private String starSystemName;

  @Column(name = "planet_name")
  private String planetName;

  @Column(name = "orbit_name")
  private String orbitName;

  @Column(name = "moon_name")
  private String moonName;

  @Column(name = "city_name")
  private String cityName;

  @Column(name = "faction_name")
  private String factionName;

  @Column(name = "jurisdiction_name")
  private String jurisdictionName;

  private Boolean isAvailable;
  private Boolean isVisible;
  private Boolean isDefault;
  private Boolean isMonitored;
  private Boolean isArmistice;
  private Boolean isLandable;
  private Boolean isDecommissioned;
  private Boolean isLagrange;
  private Boolean isJumpPoint;
  private Boolean hasQuantumMarker;
  private Boolean hasTradeTerminal;
  private Boolean hasHabitation;
  private Boolean hasRefinery;

  /**
   * Derived truth for "a refinery exists here", recomputed by {@code
   * UexUniverseSyncService.reconcileRefineryTerminalFlags()} from the presence of a live {@code
   * type = 'refinery'} terminal at this space station (REQ-REFINERY-020).
   *
   * <p>Distinct from {@link #hasRefinery}, which mirrors UEX's parent-level {@code has_refinery}
   * claim verbatim and is wrong in both directions — it misses MIC-L5, ARC-L4 and Patch City, and
   * invents four People's Service Stations. The raw claim is kept for diagnostics; this flag is
   * what the refinery-order picker and its create/update gate key on.
   */
  @Column(name = "has_refinery_terminal", nullable = false)
  private Boolean hasRefineryTerminal = false;

  private Boolean hasCargoCenter;
  private Boolean hasClinic;
  private Boolean hasFood;
  private Boolean hasShops;
  private Boolean hasRefuel;
  private Boolean hasRepair;
  private Boolean hasGravity;
  private Boolean hasLoadingDock;
  private Boolean hasDockingPort;
  private Boolean hasFreightElevator;
  private String padTypes;

  /**
   * If {@code true}, the UEX sync skips writing {@link #hasLoadingDock} from the upstream feed. Set
   * by admins/officers when UEX reports the wrong value; cleared by the same UI to hand control
   * back to UEX on the next sweep.
   */
  @Column(nullable = false)
  private Boolean hasLoadingDockOverridden = false;
}
