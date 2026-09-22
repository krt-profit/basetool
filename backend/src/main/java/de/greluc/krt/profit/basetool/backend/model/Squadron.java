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
 * Staffel tenant — the original organisational unit the multi-tenancy work was built around (see
 * {@code MULTI_SQUADRON_PLAN.md}). Concrete {@link OrgUnit} subclass discriminated by {@code kind =
 * 'SQUADRON'} on the {@code org_unit} table.
 *
 * <p>Before R2.b this entity stood alone and mapped to the dedicated {@code squadron} table. R2.b
 * folds Squadron into the single-table {@link OrgUnit} hierarchy so SK and Staffel share the same
 * shape, the same repository plumbing, and (in R2.c onward) the same scope-resolution code path.
 * The mapping change is mechanically invisible to existing callers because:
 *
 * <ul>
 *   <li>{@link SquadronRepository} still types its parameter as {@code Squadron}, and Hibernate
 *       narrows every query to {@code WHERE kind = 'SQUADRON'} through the inherited discriminator
 *       — Spezialkommando rows never leak into a Squadron-typed query.
 *   <li>Every {@link OrgUnit} field is exposed through the Lombok-generated getter/setter on the
 *       superclass; existing call sites that read or set {@code name}, {@code shorthand}, {@code
 *       description}, {@code active}, {@code isPromotionEnabled} continue to compile and behave
 *       identically.
 *   <li>The legacy {@code squadron} table was kept in lockstep with {@code org_unit} by a sync
 *       trigger during the transition; V105 dropped it and retargeted the remaining Squadron-typed
 *       foreign keys ({@code promotion_topic.owning_squadron_id}, {@code
 *       mission_participant.squadron_id}, {@code job_order_handover.executing_squadron_id}) at
 *       {@code org_unit}, keeping their column names.
 * </ul>
 *
 * <p>The {@link #IRIDIUM_ID} canonical UUID is preserved verbatim — the application code that
 * references it (backfill paths, test fixtures) stays unchanged.
 *
 * <p>No subclass-specific columns are added in R2.b; the JPA-layer existence of {@code Squadron} is
 * enough to give the inheritance hierarchy a complete shape. Squadron-specific behaviour (e.g.
 * promotion-system flag handling) remains on the {@link OrgUnit} superclass because the exact same
 * accessor surface is needed by code that holds a polymorphic {@code OrgUnit} reference, not just a
 * typed {@code Squadron}.
 */
@Entity
@DiscriminatorValue("SQUADRON")
@ToString(callSuper = true)
@NoArgsConstructor
public class Squadron extends OrgUnit {

  /**
   * Canonical UUID of the IRIDIUM Staffel — the project's reference tenant since Phase 1 of the
   * multi-squadron rollout. Seeded by Flyway migration V80 with this exact value so application
   * code, tests, and migration backfills can refer to it without an upfront database lookup. The
   * Spezialkommando R2.b refactor preserves the constant on its original class so existing imports
   * of {@code Squadron.IRIDIUM_ID} keep compiling without a rename round-trip across the codebase.
   */
  public static final UUID IRIDIUM_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

  /**
   * Returns {@link OrgUnitKind#SQUADRON} so the abstract base contract is satisfied without forcing
   * callers to {@code instanceof}-check to identify the kind. The value is a compile-time constant
   * that must stay in lockstep with the {@code @DiscriminatorValue("SQUADRON")} marker on this
   * class — a mismatch would silently break the polymorphic identity contract used by {@link
   * OrgUnit} list endpoints in R2.c.
   *
   * @return always {@link OrgUnitKind#SQUADRON}, never {@code null}.
   */
  @NotNull
  @Override
  public OrgUnitKind getKind() {
    return OrgUnitKind.SQUADRON;
  }
}
