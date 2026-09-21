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
import lombok.ToString;
import org.jetbrains.annotations.NotNull;

/**
 * Bereich (area / division) tenant — one level <em>above</em> Staffeln and Spezialkommandos in the
 * Kartell hierarchy (epic #692, REQ-ORG-014, ADR-0025). Concrete {@link OrgUnit} subclass
 * discriminated by {@code kind = 'BEREICH'} on the {@code org_unit} table.
 *
 * <p>A Bereich (e.g. Profit, Sub-Radar, Raumueberlegenheit) groups several Staffeln and SKs as its
 * children via {@code org_unit.parent_org_unit_id} (wired up by an admin in a later phase) and is
 * run by its Bereichsleitung — the {@code is_bereichsleiter} / {@code is_bereichskoordinator} /
 * {@code is_bereichsoperator} flags on {@link OrgUnitMembership}. Its own parent is the {@link
 * Organisationsleitung}. The leadership's reach over the Bereich's children is a cascading,
 * officer-equivalent scope computed in {@code OwnerScopeService} (REQ-ORG-015) — it grants no admin
 * rights.
 *
 * <p><b>Promotion is permanently disabled for Bereich rows</b>, exactly as for {@link
 * SpecialCommand}: the {@code chk_org_unit_promotion_only_squadron} CHECK forces {@code
 * is_promotion_enabled = false} on every non-{@code SQUADRON} row, so the no-arg constructor sets
 * the inherited flag to {@code false} up front to keep the transient state aligned with the DB
 * invariant (otherwise Hibernate's dirty-check would try to UPDATE the column to {@code true} and
 * Postgres would reject the row at flush time). The entity adds no fields beyond {@link OrgUnit};
 * the subclass exists for type-safe references and Hibernate's discriminator dispatch.
 */
@Entity
@DiscriminatorValue("BEREICH")
@ToString(callSuper = true)
public class Bereich extends OrgUnit {

  /**
   * No-arg constructor required by JPA. Forces the inherited {@link OrgUnit#isPromotionEnabled}
   * flag to {@code false} before Hibernate flushes the row — the {@link OrgUnit} default of {@code
   * true} would otherwise violate the {@code chk_org_unit_promotion_only_squadron} CHECK. The
   * bypass through {@link #setPromotionEnabled} writes directly via the inherited setter.
   */
  public Bereich() {
    super.setPromotionEnabled(false);
  }

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

  /**
   * Returns {@code false} unconditionally, overriding {@link OrgUnit#isPromotionEnabled()} so
   * callers reading the flag on a Bereich instance always see the DB invariant — the promotion
   * subsystem is permanently off for Bereiche.
   *
   * @return always {@code false}.
   */
  @Override
  public boolean isPromotionEnabled() {
    return false;
  }

  /**
   * Refuses to enable promotion on a Bereich. Surfaces a buggy {@code setPromotionEnabled(true)}
   * call at the call site rather than waiting for the {@code chk_org_unit_promotion_only_squadron}
   * CHECK to reject the UPDATE at flush time.
   *
   * @param value the requested flag value; must be {@code false}.
   * @throws IllegalArgumentException when {@code value} is {@code true} — Bereiche must never
   *     expose the promotion subsystem.
   */
  @Override
  public void setPromotionEnabled(boolean value) {
    if (value) {
      throw new IllegalArgumentException(
          "Promotion cannot be enabled on a Bereich — the kind = 'BEREICH' rows are barred from the"
              + " promotion subsystem by the chk_org_unit_promotion_only_squadron CHECK"
              + " constraint");
    }
    super.setPromotionEnabled(false);
  }
}
