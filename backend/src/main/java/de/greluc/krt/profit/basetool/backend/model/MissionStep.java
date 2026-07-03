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
 * One step of a mission's "Ablauf" (procedure timeline). Steps form an ordered, reorderable
 * checklist shown read-only on the mission overview and authored in the Verwaltung tab. Each step
 * carries a required {@link #title}, an optional free-text {@link #meta} (a "Zeit / Ort" hint), a
 * shared {@link #done} flag that every viewer sees, and an explicit {@link #orderIndex} that pins
 * the position independently of insertion order. The whole collection is guarded by the mission's
 * manual {@code stepsVersion} section counter, so editing the Ablauf never collides with a
 * concurrent core / schedule / flags edit of the same mission.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@ToString(exclude = {"mission"})
public class MissionStep extends AbstractEntity<UUID> {

  @Getter(onMethod_ = @__(@Override))
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  /** Owning mission. Never serialised to avoid a back-reference cycle in the JSON payload. */
  @ManyToOne(optional = false)
  @JoinColumn(name = "mission_id", nullable = false)
  @JsonIgnore
  private Mission mission;

  /** Required short step title (e.g. "Briefing", "Mining"); the checklist line label. */
  @Column(nullable = false, length = 500)
  private String title;

  /** Optional free-text "Zeit / Ort" hint shown next to the title (e.g. "TS 19:30"). Nullable. */
  @Column(length = 200)
  private String meta;

  /**
   * Shared completion flag toggled by edit-authorised users on the overview. Persisted so every
   * viewer sees the same progress; the single "current phase" is derived (the first not-done step),
   * never stored.
   */
  @Column(nullable = false)
  private boolean done = false;

  /** Zero-based position within the mission's Ablauf; the {@code @OrderBy} sort key. */
  @Column(name = "order_index", nullable = false)
  private int orderIndex;
}
