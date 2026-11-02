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

package de.greluc.krt.profit.basetool.backend.orgunit.api;

import de.greluc.krt.profit.basetool.backend.kernel.OrgUnitKind;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import lombok.ToString;
import org.jetbrains.annotations.NotNull;

/**
 * Spezialkommando tenant: an {@link OrgUnit} with {@code kind = 'SPECIAL_COMMAND'} that a user may
 * join in addition to, or instead of, a Squadron.
 *
 * <p>The subclass adds no fields; it exists for type-safe references and discriminator dispatch.
 */
@Entity
@DiscriminatorValue("SPECIAL_COMMAND")
@ToString(callSuper = true)
public class SpecialCommand extends OrgUnit {

  /**
   * Returns {@link OrgUnitKind#SPECIAL_COMMAND}; must match this class's
   * {@code @DiscriminatorValue}.
   *
   * @return always {@link OrgUnitKind#SPECIAL_COMMAND}, never {@code null}
   */
  @NotNull
  @Override
  public OrgUnitKind getKind() {
    return OrgUnitKind.SPECIAL_COMMAND;
  }
}
