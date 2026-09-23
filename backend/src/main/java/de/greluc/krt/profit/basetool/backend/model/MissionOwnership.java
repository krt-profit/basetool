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

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * Dedicated aggregate for tracking ownership of a {@link Mission} with its own optimistic lock
 * version.
 *
 * <p>Rationale (Option A / multi-user concurrency on the mission detail page):
 *
 * <ul>
 *   <li>{@code Mission.owner} itself is marked with {@code @OptimisticLock(excluded = true)} so
 *       that changing the owner does NOT bump the parent {@code Mission.version} and therefore does
 *       not invalidate other users' open forms on the same mission.
 *   <li>To still prevent lost updates on concurrent owner changes, this entity maintains an own
 *       {@code @Version} counter on a 1:1 companion row keyed by {@code mission_id}.
 *   <li>Callers (service layer) change the owner transactionally via this entity and mirror the
 *       result into {@code Mission.owner} for backward-compatible reads.
 * </ul>
 */
@Entity
@Table(
    name = "mission_ownership",
    uniqueConstraints =
        @UniqueConstraint(name = "uk_mission_ownership_mission", columnNames = "mission_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@ToString(exclude = {"mission", "owner"})
public class MissionOwnership extends AbstractEntity<UUID> {

  @Getter(onMethod_ = @__(@Override))
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @OneToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "mission_id", nullable = false, unique = true)
  private Mission mission;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "owner_id")
  private User owner;
}
