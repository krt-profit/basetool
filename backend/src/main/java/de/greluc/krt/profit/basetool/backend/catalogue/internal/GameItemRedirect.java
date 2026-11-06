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

package de.greluc.krt.profit.basetool.backend.catalogue.internal;

import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.kernel.AbstractEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Preserves a retired local catalogue id as a direct alias of the surviving item. */
@Entity
@Table(name = "game_item_redirect")
@Getter
@Setter
@NoArgsConstructor
public class GameItemRedirect extends AbstractEntity<UUID> {

  @Getter(onMethod_ = @__(@Override))
  @Id
  private UUID id;

  /** The surviving item; redirects always point directly to a live catalogue row. */
  @ManyToOne(optional = false, fetch = FetchType.LAZY)
  @JoinColumn(name = "canonical_id", nullable = false)
  private GameItem canonicalItem;

  /** Lossless database-generated JSON evidence of both catalogue rows, prices and stats. */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "retired_snapshot", nullable = false, columnDefinition = "jsonb")
  private String retiredSnapshot;
}
