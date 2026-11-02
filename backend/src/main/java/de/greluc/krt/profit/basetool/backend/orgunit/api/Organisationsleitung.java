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
import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The Organisationsleitung (OL) tenant at the top of the Kartell hierarchy (REQ-ORG-014).
 *
 * <p>OL members reach every org unit as a concrete scope union, without admin rights (REQ-ORG-015).
 * The OL has no parent.
 */
@Entity
@DiscriminatorValue("ORGANISATIONSLEITUNG")
@ToString(callSuper = true)
public class Organisationsleitung extends OrgUnit {

  /**
   * The account id of the OL member holding the Grand Admiral post (REQ-ORG-021), or {@code null}
   * when vacant. A title only: it grants no extra rights, and deleting the account vacates the
   * post.
   */
  @Getter
  @Setter
  @Column(name = "grand_admiral_user_id")
  @Nullable
  private UUID grandAdmiralUserId;

  /**
   * Returns {@link OrgUnitKind#ORGANISATIONSLEITUNG} so the abstract base contract is satisfied
   * without an {@code instanceof} check. Must stay in lockstep with the
   * {@code @DiscriminatorValue("ORGANISATIONSLEITUNG")} marker on this class.
   *
   * @return always {@link OrgUnitKind#ORGANISATIONSLEITUNG}, never {@code null}.
   */
  @NotNull
  @Override
  public OrgUnitKind getKind() {
    return OrgUnitKind.ORGANISATIONSLEITUNG;
  }
}
