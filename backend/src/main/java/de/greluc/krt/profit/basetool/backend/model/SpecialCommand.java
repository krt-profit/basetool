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
 * Spezialkommando tenant — a cross-cutting organisational unit that a user may join in addition to
 * (or instead of) their Squadron. Concrete {@link OrgUnit} subclass discriminated by {@code kind =
 * 'SPECIAL_COMMAND'} on the {@code org_unit} table.
 *
 * <p>Why this exists: the original tenant model allowed only one Staffel per user. The
 * Spezialkommando R2.a slice (see {@code SPEZIALKOMMANDO_PLAN.md} §3) introduces a second tenant
 * kind so a user can belong to one Squadron plus N Spezialkommandos, or to only a Spezialkommando
 * and no Squadron at all. Aggregates (mission, operation, ship, inventory, refinery, job order)
 * will eventually accept either kind as their owning org unit (R2.b), and feature flows that today
 * implicitly stamp the user's Squadron will gain an explicit owner picker (R2.b / R2.c).
 *
 * <p><b>Promotion subsystem is permanently disabled for SK rows.</b> The V94 CHECK constraint
 * {@code chk_org_unit_promotion_only_squadron} forces {@code is_promotion_enabled = false} on every
 * row with {@code kind = 'SPECIAL_COMMAND'}, so a careless admin click cannot turn promotion on for
 * a Spezialkommando. The {@link #SpecialCommand()} no-arg constructor sets the inherited flag to
 * {@code false} up front to keep the entity's transient state aligned with the DB invariant — JPA's
 * dirty-check would otherwise try to UPDATE the column to {@code true} on every save, only for
 * Postgres to reject the row with a constraint violation at flush time.
 *
 * <p>The entity intentionally adds no fields beyond what {@link OrgUnit} already carries; the
 * subclass exists for type-safe references (e.g. {@code CreateSpecialCommandRequest}, {@code
 * SpecialCommandRepository}) and for Hibernate's discriminator dispatch on read. Subclass- specific
 * columns can be added later via a joined-strategy migration without breaking the current shape.
 */
@Entity
@DiscriminatorValue("SPECIAL_COMMAND")
@ToString(callSuper = true)
public class SpecialCommand extends OrgUnit {

  /**
   * No-arg constructor required by JPA. Forces the inherited {@link OrgUnit#isPromotionEnabled}
   * flag to {@code false} before Hibernate has a chance to flush the row — the {@link OrgUnit}
   * default of {@code true} would otherwise round-trip through the V94 CHECK constraint as a
   * violation. The bypass through {@link #setPromotionEnabled} avoids the override's {@link
   * IllegalArgumentException} guard (which only blocks {@code true} values) and writes directly via
   * the inherited Lombok setter.
   */
  public SpecialCommand() {
    super.setPromotionEnabled(false);
  }

  /**
   * Returns {@link OrgUnitKind#SPECIAL_COMMAND} so the abstract base contract is satisfied and
   * callers do not have to {@code instanceof}-check to identify the kind. The value is a compile-
   * time constant; mismatches between this return value and the {@code @DiscriminatorValue} would
   * silently break the polymorphic identity contract, so the two must stay in lockstep.
   *
   * @return always {@link OrgUnitKind#SPECIAL_COMMAND}, never {@code null}.
   */
  @NotNull
  @Override
  public OrgUnitKind getKind() {
    return OrgUnitKind.SPECIAL_COMMAND;
  }

  /**
   * Returns {@code false} unconditionally, overriding the {@link OrgUnit#isPromotionEnabled()}
   * accessor so that callers reading the flag on an SK instance see the DB invariant reflected
   * regardless of what the field actually carries in memory. Defense in depth against a stale
   * deserialised entity that may have caught a transient {@code true} value before the V94 CHECK
   * fired.
   *
   * @return always {@code false} — the promotion subsystem is permanently disabled on
   *     Spezialkommandos by data-layer constraint.
   */
  @Override
  public boolean isPromotionEnabled() {
    return false;
  }

  /**
   * Refuses to set {@link OrgUnit#isPromotionEnabled} to anything other than {@code false}.
   * Application code that ports the existing {@code setPromotionEnabled(true)} path from the {@code
   * SquadronService} flow onto a {@link SpecialCommand} instance is buggy; this guard surfaces the
   * bug as an {@link IllegalArgumentException} at the call site rather than waiting for the V94
   * CHECK constraint to reject the UPDATE at flush time (which would be a much harder stack trace
   * to read).
   *
   * @param value the requested flag value; must be {@code false}.
   * @throws IllegalArgumentException when {@code value} is {@code true} — Spezialkommandos must
   *     never expose the promotion subsystem.
   */
  @Override
  public void setPromotionEnabled(boolean value) {
    if (value) {
      throw new IllegalArgumentException(
          "Promotion cannot be enabled on a SpecialCommand — the kind = 'SPECIAL_COMMAND' rows are"
              + " barred from the promotion subsystem by the V94 CHECK constraint");
    }
    super.setPromotionEnabled(false);
  }
}
