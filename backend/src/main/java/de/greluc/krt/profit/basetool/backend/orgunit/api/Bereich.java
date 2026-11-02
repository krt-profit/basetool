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
 * Bereich (area) org unit, one level above Staffeln and Spezialkommandos (REQ-ORG-014, ADR-0025).
 *
 * <p>Groups Staffeln and SKs as children and sits below the {@link Organisationsleitung}; its
 * leadership gets a cascading, officer-equivalent scope over the children, no admin rights.
 */
@Entity
@DiscriminatorValue("BEREICH")
@ToString(callSuper = true)
public class Bereich extends OrgUnit {

  /**
   * Returns {@link OrgUnitKind#BEREICH} so the abstract base contract is satisfied without an
   * {@code instanceof} check. The value is a compile-time constant that must stay in lockstep with
   * the {@code @DiscriminatorValue("BEREICH")} marker on this class.
   *
   * @return always {@link OrgUnitKind#BEREICH}, never {@code null}.
   */
  @NotNull
  @Override
  public OrgUnitKind getKind() {
    return OrgUnitKind.BEREICH;
  }
}
