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

package de.greluc.krt.profit.basetool.backend.patchchange.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.jetbrains.annotations.Nullable;

/**
 * One member's home-location choice in one home-location phase and what its relocation moved
 * (REQ-PATCH-021, REQ-PATCH-022). The row is written once, by {@link
 * PatchChangeRelocationRepository#claim}, and never changes; its key keeps the choice to once per
 * member and phase.
 */
@Entity
@Table(name = "patch_change_relocation")
@IdClass(PatchChangeRelocation.Key.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PatchChangeRelocation {

  /** The patch change whose phase the choice answers. */
  @Id
  @Column(name = "patch_change_id", nullable = false, updatable = false)
  private UUID patchChangeId;

  /** The member who relocated. */
  @Id
  @Column(name = "user_id", nullable = false, updatable = false)
  private UUID userId;

  /** The chosen home location, or {@code null} once the location was deleted. */
  @Column(name = "home_location_id", updatable = false)
  @Nullable
  private UUID homeLocationId;

  /** Who made the choice. */
  @Enumerated(EnumType.STRING)
  @Column(name = "chosen_by", nullable = false, updatable = false, length = 8)
  private RelocationChooser chosenBy;

  /** When the relocation ran. */
  @Column(name = "relocated_at", nullable = false, updatable = false)
  private Instant relocatedAt;

  /** How many stock rows moved. */
  @Column(name = "rows_moved", nullable = false)
  private int rowsMoved;

  /** How many ships moved. */
  @Column(name = "ships_moved", nullable = false)
  private int shipsMoved;

  /** The composite key: one choice per patch change and member. */
  @Getter
  @NoArgsConstructor
  @AllArgsConstructor
  @EqualsAndHashCode
  public static class Key implements Serializable {

    private static final long serialVersionUID = 1L;

    /** The patch change. */
    private UUID patchChangeId;

    /** The member. */
    private UUID userId;
  }
}
