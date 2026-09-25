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

import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.jetbrains.annotations.NotNull;

/**
 * The Organisationsleitung (OL) tenant at the top of the Kartell hierarchy (REQ-ORG-014).
 *
 * <p>OL members reach every org unit as a concrete scope union, without admin rights (REQ-ORG-015).
 * The OL has no parent and promotion is always disabled.
 */
@Entity
@DiscriminatorValue("ORGANISATIONSLEITUNG")
@ToString(callSuper = true)
public class Organisationsleitung extends OrgUnit {

  /**
   * The account id of the OL member holding the Grand Admiral post (REQ-ORG-021), or {@code null}
   * when vacant. Records only who the org chart renders at the top; grants no extra rights, and
   * deleting the account vacates the post.
   */
  @Getter
  @Setter
  @Column(name = "grand_admiral_user_id")
  private UUID grandAdmiralUserId;

  /**
   * Free-text name of a Grand Admiral without a Basetool account (REQ-ORG-021), or {@code null}.
   * Mutually exclusive with {@link #grandAdmiralUserId}; grants nothing.
   */
  @Getter
  @Setter
  @Column(name = "grand_admiral_display_name", columnDefinition = "TEXT")
  private String grandAdmiralDisplayName;

  /**
   * No-arg constructor required by JPA. Forces the inherited {@link OrgUnit#isPromotionEnabled}
   * flag to {@code false} before Hibernate flushes the row, keeping the transient state aligned
   * with the {@code chk_org_unit_promotion_only_squadron} CHECK.
   */
  public Organisationsleitung() {
    super.setPromotionEnabled(false);
  }

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

  /**
   * Returns {@code false} unconditionally, overriding {@link OrgUnit#isPromotionEnabled()} so
   * callers reading the flag on an OL instance always see the DB invariant — promotion is
   * permanently off.
   *
   * @return always {@code false}.
   */
  @Override
  public boolean isPromotionEnabled() {
    return false;
  }

  /**
   * Refuses to enable promotion on the Organisationsleitung.
   *
   * @param value the requested flag value; must be {@code false}.
   * @throws IllegalArgumentException when {@code value} is {@code true}.
   */
  @Override
  public void setPromotionEnabled(boolean value) {
    if (value) {
      throw new IllegalArgumentException(
          "Promotion cannot be enabled on the Organisationsleitung — kind = 'ORGANISATIONSLEITUNG'"
              + " rows are barred from the promotion subsystem by the"
              + " chk_org_unit_promotion_only_squadron CHECK constraint");
    }
    super.setPromotionEnabled(false);
  }
}
