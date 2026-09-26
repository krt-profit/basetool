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
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A personal inventory entry owned by exactly one user ({@link #ownerUserId}); distinct from the
 * squadron stock in {@link InventoryItem}.
 *
 * <p>The location is referenced by its UEX numeric id plus a {@link PersonalInventoryLocationType}
 * discriminator; its display name is denormalized into {@link #locationNameSnapshot} so the entry
 * still renders if the location disappears from UEX.
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

  @Column(name = "location_uex_id", nullable = false)
  private Integer locationUexId;

  @Enumerated(EnumType.STRING)
  @Column(name = "location_type", nullable = false, length = 20)
  private PersonalInventoryLocationType locationType;

  @Column(name = "location_name_snapshot", nullable = false, length = 255)
  private String locationNameSnapshot;

  @Column(nullable = false)
  private Integer quantity;
}
