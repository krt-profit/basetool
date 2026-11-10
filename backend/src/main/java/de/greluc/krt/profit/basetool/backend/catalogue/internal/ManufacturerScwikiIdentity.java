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

import de.greluc.krt.profit.basetool.backend.catalogue.api.Manufacturer;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.jetbrains.annotations.Nullable;

/**
 * One SC Wiki manufacturer row that resolves to a local {@link Manufacturer}.
 *
 * <p>The row whose UUID equals {@code manufacturer.scwiki_uuid} is the primary link; every other
 * row of the same manufacturer is a Wiki duplicate kept as an alias, so Wiki items naming either
 * UUID resolve to the same manufacturer (ADR-0361). Only the Wiki manufacturer sync and the admin
 * primary choice write it; the sync is serialised, so there is no version column.
 */
@Entity
@Table(name = "manufacturer_scwiki_identity")
@Getter
@Setter
@ToString(exclude = "manufacturer")
@NoArgsConstructor
@AllArgsConstructor
public class ManufacturerScwikiIdentity {

  /** The Wiki manufacturer UUID; the natural primary key. */
  @Id
  @Column(name = "scwiki_uuid", nullable = false)
  private UUID scwikiUuid;

  /** The local manufacturer this Wiki row resolves to. */
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "manufacturer_id", nullable = false)
  private Manufacturer manufacturer;

  /** The Wiki's own name for this row, shown so an admin can tell the duplicates apart. */
  @Column(name = "wiki_name", nullable = false)
  private String wikiName;

  /** The Wiki's engine code for this row, or {@code null} when the Wiki sends none. */
  @Nullable
  @Column(name = "wiki_code", length = 64)
  private String wikiCode;

  /** When the Wiki manufacturer sync last saw this row. */
  @Column(name = "synced_at", nullable = false)
  private Instant syncedAt;
}
