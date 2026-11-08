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

import de.greluc.krt.profit.basetool.backend.kernel.AbstractEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.OptimisticLock;
import org.jetbrains.annotations.Nullable;

/**
 * The single row holding the global exchange switch (REQ-XCH-003) and the bank switch
 * (REQ-BXCH-006); created by the migration, never by the application.
 *
 * <p>The row's {@code version} guards the global switch. The bank switch carries its own counter,
 * {@code bankVersion}, and its columns are excluded from the row's optimistic lock, so flipping one
 * switch never makes the other's echoed version stale. Every write holds the row lock first.
 */
@Entity
@Table(name = "exchange_settings")
@DynamicUpdate
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

  /** Whether the bank route family serves requests while the exchange is on. */
  @Column(nullable = false)
  @OptimisticLock(excluded = true)
  private boolean bankEnabled;

  /** The bank switch's own optimistic-lock counter, bumped on every change of it. */
  @Column(nullable = false)
  @OptimisticLock(excluded = true)
  private long bankVersion;

  /** When the bank switch last changed, or {@code null} before its first change. */
  @OptimisticLock(excluded = true)
  @Nullable
  private Instant bankUpdatedAt;
}
