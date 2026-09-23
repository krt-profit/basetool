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
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/** Mission Unit JPA entity. */
@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@ToString(exclude = {"mission", "crew", "shipType", "ship", "responsibleUser"})
public class MissionUnit extends AbstractEntity<UUID> {

  @Getter(onMethod_ = @__(@Override))
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "mission_id", nullable = false)
  @JsonIgnore
  private Mission mission;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "ship_type_id", nullable = true)
  private ShipType shipType;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "ship_id", nullable = true)
  @OnDelete(action = OnDeleteAction.SET_NULL)
  private Ship ship;

  @Column private Double frequency;

  @Column(nullable = false)
  private boolean highValueUnit = false;

  @Column(nullable = false)
  private String name;

  /**
   * Explicit responsible person for this unit (the mock's "Verantwortlich" select). Nullable — when
   * unset the UI falls back to the assigned ship's owner. {@code ON DELETE SET NULL} mirrors the
   * {@code ship} reference so deleting a user never cascades into mission planning data.
   */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "responsible_user_id", nullable = true)
  @OnDelete(action = OnDeleteAction.SET_NULL)
  private User responsibleUser;

  /** Free-text planning note for the unit (e.g. "Eskorte, Gruppe 1"). Nullable. */
  @Column(columnDefinition = "TEXT")
  private String note;

  @OneToMany(mappedBy = "missionUnit", cascade = CascadeType.ALL, orphanRemoval = true)
  private Set<MissionCrew> crew = new HashSet<>();
}
