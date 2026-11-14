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

package de.greluc.krt.profit.basetool.backend.identity.internal;

import de.greluc.krt.profit.basetool.backend.kernel.AbstractEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.jetbrains.annotations.Nullable;

/**
 * A member's profile setting "my home location" (REQ-PATCH-030). The row's absence means the member
 * made no choice. It is read through this entity and written only by the conditional statements of
 * {@link MemberHomeLocationRepository}, which start its own optimistic-lock version at 1.
 */
@Entity
@Immutable
@Table(name = "member_home_location")
@Getter
@NoArgsConstructor
public class MemberHomeLocation extends AbstractEntity<UUID> {

  /** The member's id; the row is deleted with the member. */
  @Getter(onMethod_ = @__(@Override))
  @Id
  @Column(name = "user_id")
  private UUID id;

  /** The chosen home location, or {@code null} once that location was deleted. */
  @Nullable
  @Column(name = "location_id")
  private UUID locationId;
}
