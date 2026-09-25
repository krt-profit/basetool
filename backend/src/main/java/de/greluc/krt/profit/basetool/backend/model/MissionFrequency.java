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
 * One radio channel value scoped to a single mission.
 *
 * <p>A row is either <b>typed</b> ({@link #frequencyType} set, {@link #name} {@code null}; at most
 * one per type and mission) or <b>custom</b> ({@link #name} holds a free-text label, {@link
 * #frequencyType} {@code null}; any number per mission, REQ-MISSION-014). A database check
 * constraint enforces exactly one of the two.
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
   * The global frequency type this row supplies a value for, or {@code null} for a custom channel;
   * mutually exclusive with {@link #name}.
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
