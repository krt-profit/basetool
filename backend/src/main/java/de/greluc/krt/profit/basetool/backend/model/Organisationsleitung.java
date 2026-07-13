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

/**
 * Organisationsleitung (OL) tenant — the top of the Kartell hierarchy, above every {@link Bereich}
 * (epic #692, REQ-ORG-014, ADR-0025). Concrete {@link OrgUnit} subclass discriminated by {@code
 * kind = 'ORGANISATIONSLEITUNG'} on the {@code org_unit} table.
 *
 * <p>The OL consists of several people, modelled as memberships carrying the {@code is_ol_member}
 * flag on {@link OrgUnitMembership}. Per REQ-ORG-015 an OL member's reach cascades over
 * <em>every</em> org unit (all Bereiche and their Staffeln/SKs) as a concrete scope union computed
 * in {@code OwnerScopeService} — explicitly <b>not</b> via the admin all-scope branch, so OL
 * membership grants officer-equivalent reach but no admin rights. The OL row has no parent ({@code
 * chk_org_unit_ol_has_no_parent}).
 *
 * <p><b>Promotion is permanently disabled for the OL row</b>, exactly as for {@link Bereich} and
 * {@link SpecialCommand}: the {@code chk_org_unit_promotion_only_squadron} CHECK forces {@code
 * is_promotion_enabled = false} on every non-{@code SQUADRON} row, so the no-arg constructor sets
 * the inherited flag to {@code false} up front. The entity adds no fields beyond {@link OrgUnit}.
 */
@Entity
@DiscriminatorValue("ORGANISATIONSLEITUNG")
@ToString(callSuper = true)
public class Organisationsleitung extends OrgUnit {

  /**
   * The account id of the OL member currently holding the <b>Grand Admiral</b> post (REQ-ORG-021),
   * or {@code null} when the post is vacant. The Grand Admiral is one of the OL members — the
   * holder keeps the {@code OL_MEMBER} rank and its rights are unchanged; this pointer only records
   * who the org chart renders at the very top of the Organisationsleitung. Because the OL is a
   * singleton tier, this single column is the org-wide "at most one Grand Admiral" guarantee. A
   * subclass-only column on the single-table {@code org_unit} hierarchy (nullable for every non-OL
   * row, enforced by {@code chk_org_unit_grand_admiral_only_ol}, V215), with a {@code SET NULL} FK
   * to {@code app_user} so deleting the account vacates the post.
   */
  @Getter
  @Setter
  @Column(name = "grand_admiral_user_id")
  private UUID grandAdmiralUserId;

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
   * Refuses to enable promotion on the Organisationsleitung. Surfaces a buggy {@code
   * setPromotionEnabled(true)} call at the call site rather than at flush time.
   *
   * @param value the requested flag value; must be {@code false}.
   * @throws IllegalArgumentException when {@code value} is {@code true} — the OL must never expose
   *     the promotion subsystem.
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
