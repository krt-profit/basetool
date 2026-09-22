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

/**
 * Discriminator for {@link OrgUnit}: which concrete kind of tenant a row in the {@code org_unit}
 * table represents.
 *
 * <p>The enum values match the string literals that Flyway migration V94 (CHECK constraint), V95
 * (denormalised {@code kind} column on {@code org_unit_membership} kept in sync by the {@code
 * sync_org_unit_membership_kind} trigger), and the Hibernate {@code @DiscriminatorValue} on the
 * {@link OrgUnit} hierarchy all rely on. Changing or reordering the names here without coordinating
 * a Flyway migration would silently mis-map existing rows — keep this enum and the database CHECK /
 * trigger / discriminator strings synchronised.
 *
 * <p>Rationale for the four kinds:
 *
 * <ul>
 *   <li>{@link #SQUADRON} — the original "Staffel" tenant boundary that has driven the
 *       multi-tenancy work since Phase 1 (see {@code docs/archive/MULTI_SQUADRON_PLAN.md}). A user
 *       belongs to at most one Squadron. Squadrons may run the promotion subsystem.
 *   <li>{@link #SPECIAL_COMMAND} — added by the Spezialkommando extension (R2.a, see {@code
 *       docs/archive/SPEZIALKOMMANDO_PLAN.md}). A user may belong to any number of Special Commands
 *       in addition to (or instead of) a Squadron. Special Commands never carry the promotion
 *       subsystem; this invariant is enforced at the database layer via the {@code
 *       chk_org_unit_promotion_only_squadron} CHECK constraint and additionally in the {@link
 *       SpecialCommand} entity defaults.
 *   <li>{@link #BEREICH} and {@link #ORGANISATIONSLEITUNG} — the two hierarchy levels above them
 *       (epic #692, ADR-0025): a Bereich groups Staffeln and SKs, the Organisationsleitung sits on
 *       top. Neither carries promotion.
 * </ul>
 *
 * <p>The living description of the tenancy model is {@code docs/specs/org-unit-tenancy.md}.
 */
public enum OrgUnitKind {

  /**
   * The classic squadron tenant — Staffel in the German domain language. Mapped to {@code
   * org_unit.kind = 'SQUADRON'} via {@code @DiscriminatorValue} on {@link Squadron}. The legacy
   * {@code squadron} table it once mirrored was dropped in V105.
   */
  SQUADRON,

  /**
   * The Spezialkommando tenant — a cross-cutting unit that members may join on top of their
   * Squadron membership, or instead of one. Mapped to {@code org_unit.kind = 'SPECIAL_COMMAND'} via
   * {@code @DiscriminatorValue} on {@link SpecialCommand}. Permanently barred from the promotion
   * subsystem by the database CHECK constraint introduced in V94.
   */
  SPECIAL_COMMAND,

  /**
   * The Bereich (area / division) tenant — one level <em>above</em> Staffeln and Spezialkommandos
   * in the Kartell hierarchy (epic #692, REQ-ORG-014, ADR-0025). Mapped to {@code org_unit.kind =
   * 'BEREICH'} via {@code @DiscriminatorValue} on {@link Bereich}. A Bereich groups several
   * Staffeln and SKs (its children via {@code org_unit.parent_org_unit_id}, set in a later phase)
   * and is run by its Bereichsleitung (the {@code is_bereichsleiter} / {@code
   * is_bereichskoordinator} / {@code is_bereichsoperator} membership flags). Permanently barred
   * from promotion by {@code chk_org_unit_promotion_only_squadron} (only {@code SQUADRON} may carry
   * it), like SK.
   */
  BEREICH,

  /**
   * The Organisationsleitung (OL) tenant — the top of the Kartell hierarchy, above every Bereich
   * (epic #692, REQ-ORG-014, ADR-0025). Mapped to {@code org_unit.kind = 'ORGANISATIONSLEITUNG'}
   * via {@code @DiscriminatorValue} on {@link Organisationsleitung}. Its members carry the {@code
   * is_ol_member} membership flag and (per REQ-ORG-015) reach every org unit, without admin rights.
   * Has no parent ({@code chk_org_unit_ol_has_no_parent}) and never carries promotion.
   */
  ORGANISATIONSLEITUNG
}
