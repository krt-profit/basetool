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
 * The functional area an {@link AuditEvent} belongs to (REQ-AUDIT-001). The shared {@code
 * audit_event} table is one physical store; this discriminator keeps the per-area logs
 * <em>logically</em> separate so the admin viewer can switch between them and each can be exported
 * on its own (ADR-0037). The bank audit trail is deliberately <strong>not</strong> a value here —
 * it keeps its own {@code bank_audit_event} table and is surfaced on the same page as its own tab.
 */
public enum AuditDomain {

  /** Squadron warehouse stock — the {@code InventoryItem} aggregate (Lagerverwaltung). */
  INVENTORY,

  /** Job orders — the {@code JobOrder} aggregate and its claims/handovers (Auftragsverwaltung). */
  JOB_ORDER,

  /** Refinery orders and refinery reference data (Raffinerieverwaltung). */
  REFINERY,

  /** Per-user personal stash — the {@code PersonalInventoryItem} aggregate (Mein Inventar). */
  PERSONAL_INVENTORY,

  /**
   * Missions — the {@code Mission} aggregate and its participants/units/crew/finance (Missionen).
   */
  MISSION,

  /** Operations — the {@code Operation} aggregate and its payout toggles (Operationen). */
  OPERATION,

  /**
   * Org-unit role &amp; membership management — the {@code org_unit_membership} aggregate (epic
   * #800): who is a member of which org unit, the leadership ranks granted/changed/revoked
   * (Bereichsleitung, OL, SK-Lead and, from Phase 3, the squadron ranks) and the Logistician /
   * Mission-Manager capability flags (Rollen &amp; Mitglieder).
   */
  ROLE,

  /**
   * Promotion system — the per-Staffel promotion catalogue and member grading (Beförderung): the
   * {@code PromotionTopic} / {@code PromotionCategory} / {@code PromotionLevelContent} subtree, the
   * {@code RankRequirement} promotion-rules catalogue, and the per-member {@code MemberEvaluation}
   * gradings. Every catalogue or evaluation mutation is captured here. For an evaluation the
   * subject is the graded category (its non-personal label); the member is the target reference.
   */
  PROMOTION,

  /**
   * Materialbörse — the material-exchange trade board (Flotte &amp; Logistik, REQ-MARKET-001…): a
   * player releasing a Lager row for trade, editing its remark, deactivating the offer, and members
   * registering or withdrawing interest. The subject is the offer (labelled by material, a
   * non-personal value); the anbieter is the target reference. Interessenten identities are never
   * recorded — only counts and lengths.
   */
  MARKET
}
