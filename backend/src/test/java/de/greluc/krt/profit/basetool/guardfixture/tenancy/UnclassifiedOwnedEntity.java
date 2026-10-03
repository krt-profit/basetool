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

import de.greluc.krt.profit.basetool.backend.model.OrgUnit;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import java.util.UUID;

/**
 * Planted violation of the tenancy marker rule: an entity with an owning org unit and a raw
 * org-unit key column, neither marked nor listed. It lives outside the scanned backend package, so
 * no persistence unit ever maps it.
 */
@Entity
public class UnclassifiedOwnedEntity {

  /** Primary key. */
  @Id private UUID id;

  /** The owning org unit the marker rule must notice. */
  @ManyToOne private OrgUnit owningOrgUnit;

  /** A raw key column the marker rule must notice by its column name. */
  @Column(name = "handover_squadron_id")
  private UUID handoverUnit;
}
