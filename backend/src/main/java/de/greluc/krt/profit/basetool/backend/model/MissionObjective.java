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
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * One classified goal (Ziel) of a mission. Goals form an ordered, reorderable list authored in the
 * Verwaltung tab and shown read-only on the mission overview, grouped by {@link #kind} (Hauptziel →
 * Nebenziel → Nicht-Ziel). Each goal carries a required {@link #title}, a {@link #kind}
 * classification, and an explicit {@link #orderIndex} that pins the position independently of
 * insertion order. The whole collection is guarded by the mission's manual {@code
 * objectivesVersion} section counter, so editing the goals never collides with a concurrent core /
 * schedule / flags / Ablauf edit of the same mission. Unlike an Ablauf step a goal carries no
 * {@code done} flag — it is a scope statement, not a progress item.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@ToString(exclude = {"mission"})
public class MissionObjective extends AbstractEntity<UUID> {

  @Getter(onMethod_ = @__(@Override))
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  /** Owning mission. Never serialised to avoid a back-reference cycle in the JSON payload. */
  @ManyToOne(optional = false)
  @JoinColumn(name = "mission_id", nullable = false)
  @JsonIgnore
  private Mission mission;

  /** Required short goal text (e.g. "Erz-Quote 30k aUEC"); the bullet label on the overview. */
  @Column(nullable = false, length = 500)
  private String title;

  /**
   * Classification of the goal (primary / secondary / non-goal). Drives the grouped overview
   * display and is persisted as its enum name.
   */
  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  private MissionObjectiveKind kind;

  /** Zero-based position within the mission's goal list; the {@code @OrderBy} sort key. */
  @Column(name = "order_index", nullable = false)
  private int orderIndex;
}
