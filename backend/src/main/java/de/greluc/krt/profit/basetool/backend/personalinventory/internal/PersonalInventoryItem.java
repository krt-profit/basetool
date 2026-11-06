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

package de.greluc.krt.profit.basetool.backend.personalinventory.internal;

import de.greluc.krt.profit.basetool.backend.catalogue.api.Location;
import de.greluc.krt.profit.basetool.backend.kernel.AbstractEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
import org.jetbrains.annotations.Nullable;

/**
 * A personal inventory entry owned by exactly one user ({@link #ownerUserId}); distinct from the
 * squadron stock in {@code InventoryItem}.
 *
 * <p>The place is a {@code location} row (REQ-P4K-039); its display name is denormalized into
 * {@link #locationNameSnapshot} so the entry still renders when the place no longer resolves.
 */
@Entity
@Table(name = "personal_inventory_item")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PersonalInventoryItem extends AbstractEntity<UUID> {

  @Getter(onMethod_ = @__(@Override))
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  /**
   * {@code app_user.id} of the owning user; never exposed to clients. A plain id rather than an
   * association so the {@code ON DELETE CASCADE} foreign key can remove rows without managed
   * references to the deleted user (REQ-DATA-008).
   */
  @Column(name = "owner_user_id", nullable = false)
  private UUID ownerUserId;

  @Column(nullable = false, length = 120)
  private String name;

  @Column(length = 2000)
  private String note;

  /**
   * The storage place, or {@code null} when the place no longer resolves; the foreign key clears it
   * when the location is deleted.
   */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "location_id")
  @Nullable
  private Location location;

  @Column(name = "location_name_snapshot", nullable = false, length = 255)
  private String locationNameSnapshot;

  @Column(nullable = false)
  private Integer quantity;
}
