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

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * Mission Frequency JPA entity — one radio channel value scoped to a single mission.
 *
 * <p>A row is <strong>dual-mode</strong> and carries its label in exactly one of two ways
 * (DB-enforced by the {@code frequency_type_id XOR name} check constraint added in V201):
 *
 * <ul>
 *   <li><b>Typed</b> — {@link #frequencyType} references a global {@link FrequencyType} (e.g.
 *       "Einsatzleitung", "Umschlagplatz") and {@link #name} is {@code null}. The mission supplies
 *       only the per-mission {@link #value} for that shared type; the {@code (mission_id,
 *       frequency_type_id)} unique constraint keeps at most one typed row per type.
 *   <li><b>Custom</b> — {@link #name} holds a free-text, mission-specific label and {@link
 *       #frequencyType} is {@code null}. These are the "weitere Frequenzen" a mission planner adds
 *       ad hoc (REQ-MISSION-014). The unique constraint does not apply (PostgreSQL treats each NULL
 *       {@code frequency_type_id} as distinct), so a mission may carry several custom channels.
 * </ul>
 */
@Entity
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"mission_id", "frequency_type_id"}))
public class MissionFrequency extends AbstractEntity<UUID> {

  @Getter(onMethod_ = @__(@Override))
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "mission_id", nullable = false)
  @JsonIgnore
  @ToString.Exclude
  private Mission mission;

  /**
   * The global frequency type this row supplies a value for, or {@code null} for a custom
   * (mission-specific) channel. Nullable since V201; mutually exclusive with {@link #name}.
   */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "frequency_type_id")
  @ToString.Exclude
  private FrequencyType frequencyType;

  /**
   * The free-text, mission-specific label for a custom channel, or {@code null} when this row is
   * bound to a global {@link #frequencyType}. Mutually exclusive with {@link #frequencyType}.
   */
  @Column(name = "name", length = 100)
  private String name;

  @Column(name = "frequency_value", nullable = false, precision = 5, scale = 2)
  private BigDecimal value;
}
