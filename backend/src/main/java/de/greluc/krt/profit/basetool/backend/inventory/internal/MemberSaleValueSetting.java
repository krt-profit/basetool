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

package de.greluc.krt.profit.basetool.backend.inventory.internal;

import com.fasterxml.jackson.annotation.JsonIgnore;
import de.greluc.krt.profit.basetool.backend.kernel.AbstractEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.jetbrains.annotations.Nullable;

/**
 * The terminal price a member's "Mein Lager" values the own materials at (REQ-INV-072). The row's
 * absence means the average price. It carries its own optimistic-lock version, so saving it never
 * collides with an edit of the member's other settings.
 */
@Entity
@Table(name = "member_sale_value_setting")
@Getter
@Setter
@NoArgsConstructor
public class MemberSaleValueSetting extends AbstractEntity<UUID> {

  /** The member's id; the row is deleted with the member. */
  @Getter(onMethod_ = @__(@Override))
  @Id
  @Column(name = "user_id")
  @Setter(onParam_ = @__(@Nullable))
  private UUID id;

  /** The price basis the member chose. */
  @Enumerated(EnumType.STRING)
  @Column(name = "sale_price_basis", nullable = false, length = 20)
  private SalePriceBasis salePriceBasis;

  /**
   * Tells whether the row is new: the id is assigned by the caller, so the version decides.
   *
   * @return {@code true} while the row has never been persisted
   */
  @JsonIgnore
  @Override
  public boolean isNew() {
    return getVersion() == null;
  }
}
