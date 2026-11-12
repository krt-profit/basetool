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
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A reviewed P4K manufacturer GUID that resolves to a brand without leading its fields. */
@Entity
@Table(name = "manufacturer_p4k_alias")
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class ManufacturerP4kAlias {

  /** The reviewed manufacturer's GUID in the P4K. */
  @Id
  @Column(name = "p4k_uuid", nullable = false)
  private UUID p4kUuid;

  /** The brand whose item and ship links the GUID resolves to. */
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "manufacturer_id", nullable = false)
  @Setter
  private Manufacturer manufacturer;
}
