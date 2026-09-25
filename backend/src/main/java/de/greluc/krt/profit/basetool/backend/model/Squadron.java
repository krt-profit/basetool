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

import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import java.util.UUID;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.jetbrains.annotations.NotNull;

/**
 * Staffel tenant: an {@link OrgUnit} with {@code kind = 'SQUADRON'} in the single-table {@code
 * org_unit} hierarchy. Squadron-typed queries such as {@link SquadronRepository} are narrowed to
 * this kind by the discriminator; all fields and promotion-flag handling live on {@link OrgUnit}.
 */
@Entity
@DiscriminatorValue("SQUADRON")
@ToString(callSuper = true)
@NoArgsConstructor
public class Squadron extends OrgUnit {

  /**
   * Canonical UUID of the IRIDIUM Staffel, seeded by Flyway with this exact value so code, tests
   * and migrations can reference it without a lookup.
   */
  public static final UUID IRIDIUM_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

  /**
   * Returns {@link OrgUnitKind#SQUADRON}; must match this class's {@code @DiscriminatorValue}.
   *
   * @return always {@link OrgUnitKind#SQUADRON}, never {@code null}
   */
  @NotNull
  @Override
  public OrgUnitKind getKind() {
    return OrgUnitKind.SQUADRON;
  }
}
