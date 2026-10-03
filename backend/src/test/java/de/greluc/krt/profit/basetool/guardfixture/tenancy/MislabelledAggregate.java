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

package de.greluc.krt.profit.basetool.guardfixture.tenancy;

import de.greluc.krt.profit.basetool.backend.annotation.TenantScoped;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.util.UUID;

/** Planted violation: a marker naming a field that is no org-unit association. */
@TenantScoped("name")
@Entity
public class MislabelledAggregate {

  /** Primary key. */
  @Id private UUID id;

  /** A plain column the marker wrongly names. */
  private String name;
}
