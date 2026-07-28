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
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/** Terminal JPA entity. */
@Entity
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class Terminal extends AbstractEntity<UUID> {

  /**
   * Value of {@link #type} that marks a terminal as a refinery kiosk. This is the terminal-level
   * ground truth for "a refinery exists here" — UEX's parent-level {@code has_refinery} flag on
   * city / space-station / outpost disagrees with it in both directions (REQ-REFINERY-020).
   */
  public static final String TYPE_REFINERY = "refinery";

  @Getter(onMethod_ = @__(@Override))
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "id_terminal", unique = true)
  private Integer idTerminal;

  private String name;
  private String code;

  /**
   * UEX terminal kind — one of {@code item}, {@code commodity}, {@code commodity_raw}, {@code
   * fuel}, {@code refinery}, {@code vehicle_buy}, {@code vehicle_rent}. Mirrored verbatim from the
   * upstream {@code /terminals} feed; {@code null} for rows synced before V226 until the next UEX
   * sweep refreshes them. Compare against {@link #TYPE_REFINERY} rather than a string literal.
   */
  @Column(name = "type")
  private String type;

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

  @Column(name = "space_station_name")
  private String spaceStationName;

  @Column(name = "outpost_name")
  private String outpostName;

  @Column(name = "city_name")
  private String cityName;

  @Column(name = "faction_name")
  private String factionName;

  @Column(name = "company_name")
  private String companyName;

  private Boolean isAvailable;
  private Boolean isVisible;
  private Boolean isJumpPoint;
  private Boolean hasLoadingDock;
  private Boolean hasDockingPort;
  private Boolean hasFreightElevator;
  private Boolean isAutoLoad;
  private Boolean hidden = false;

  /**
   * If {@code true}, the UEX sync skips writing {@link #hasLoadingDock} from the upstream feed. Set
   * by admins/officers when UEX reports the wrong value; cleared by the same UI to hand control
   * back to UEX on the next sweep.
   */
  @Column(nullable = false)
  private Boolean hasLoadingDockOverridden = false;

  /**
   * If {@code true}, the UEX sync skips writing {@link #isAutoLoad} from the upstream feed. Set by
   * admins/officers when UEX reports the wrong value; cleared by the same UI to hand control back
   * to UEX on the next sweep.
   */
  @Column(nullable = false)
  private Boolean isAutoLoadOverridden = false;

  /**
   * Raw {@code has_loading_dock} value that the most recent UEX sweep reported for this terminal.
   * Always written by {@code UexUniverseSyncService.syncTerminals()} regardless of {@link
   * #hasLoadingDockOverridden}, so the admin UI can show what UEX currently claims even while an
   * officer's pin is active. {@code null} until the first sweep touches this row (or for legacy
   * rows that were admin-pinned before the column existed and never re-synced).
   */
  @Column(name = "uex_has_loading_dock")
  private Boolean uexHasLoadingDock;

  /**
   * Raw {@code is_auto_load} value that the most recent UEX sweep reported. Same semantics as
   * {@link #uexHasLoadingDock} — always written by the sync, decoupled from the admin override
   * flag, {@code null} until the first sweep populates it.
   */
  @Column(name = "uex_is_auto_load")
  private Boolean uexIsAutoLoad;

  /**
   * UTC instant of the most recent UEX sweep that touched this terminal. Stamped unconditionally by
   * {@code UexUniverseSyncService.syncTerminals()} on every visit; a value markedly older than the
   * latest sync timestamp on neighbouring rows means UEX has stopped emitting this terminal. {@code
   * null} until the first sweep.
   */
  @Column(name = "uex_synced_at")
  private Instant uexSyncedAt;
}
