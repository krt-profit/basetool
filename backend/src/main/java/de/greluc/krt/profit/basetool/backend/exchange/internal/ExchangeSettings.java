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

package de.greluc.krt.profit.basetool.backend.exchange.internal;

import de.greluc.krt.profit.basetool.backend.model.AbstractEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * The single row holding the global exchange switch (REQ-XCH-003); created by the migration, never
 * by the application.
 */
@Entity
@Table(name = "exchange_settings")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class ExchangeSettings extends AbstractEntity<Short> {

  /** The id of the only row. */
  public static final short SINGLETON_ID = 1;

  @Getter(onMethod_ = @__(@Override))
  @Id
  private Short id;

  /** Whether the exchange serves any request at all. */
  @Column(nullable = false)
  private boolean enabled;
}
