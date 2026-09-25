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
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * Defines the requirements for a promotion from one rank to another. Both {@link #topic} and {@link
 * #category} are optional: a topic- or category-scoped requirement narrows the rule to part of the
 * catalog, while a requirement with neither set is "global within its Staffel" (any {@link
 * #requiredCount} categories of the owning Staffel must reach {@link #minimumLevel}). Because a
 * global requirement has no topic/category to derive its squadron from, every requirement carries
 * its own {@link #owningSquadron} — unlike the rest of the promotion tree, which inherits the scope
 * through its topic reference (Plan §3.2).
 */
@Entity
@Table(name = "rank_requirement")
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RankRequirement extends AbstractEntity<UUID> {

  @Getter(onMethod_ = @__(@Override))
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "from_rank", nullable = false)
  private int fromRank;

  @Column(name = "to_rank", nullable = false)
  private int toRank;

  /**
   * Staffel that owns this rank requirement and the only scope that may see or edit it. Stamped at
   * creation from the caller's active squadron context and immutable afterwards. Carried directly
   * (rather than derived through {@link #topic}/{@link #category}) so that a global requirement —
   * one with neither topic nor category — still belongs to exactly one Staffel. Kept typed {@link
   * Squadron} (never {@link OrgUnit}) and DB-guarded by the V135 {@code kind='SQUADRON'} trigger so
   * promotion data can never be owned by a Spezialkommando (Plan §3.3).
   */
  @ToString.Exclude
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "owning_squadron_id", nullable = false)
  private Squadron owningSquadron;

  @ToString.Exclude
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "topic_id")
  private PromotionTopic topic;

  @ToString.Exclude
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "category_id")
  private PromotionCategory category;

  @Enumerated(EnumType.STRING)
  @Column(name = "minimum_level", nullable = false, length = 10)
  private PromotionLevel minimumLevel;

  @Column(name = "required_count", nullable = false)
  private int requiredCount;

  @Column(length = 2000)
  private String description;
}
