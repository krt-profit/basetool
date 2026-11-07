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

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * Ties a catalogue ship that belongs to a variant group to the GUID of the group's base, so its
 * class name resolves to the base's ship type when it has none of its own (REQ-P4K-032, ADR-0341).
 *
 * <p>Written by the P4K import only; no version column, as no request writes it.
 */
@Entity
@Table(name = "ship_type_p4k_variant")
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class ShipTypeP4kVariant {

  /** The P4K GUID of the variant; the natural primary key. */
  @Id
  @Column(name = "variant_uuid", nullable = false)
  private UUID variantUuid;

  /** The variant's class name, unique ignoring case. */
  @Column(name = "class_name", nullable = false)
  private String className;

  /** The P4K GUID of the group's base ({@code variantOf}). */
  @Column(name = "base_uuid", nullable = false)
  private UUID baseUuid;
}
