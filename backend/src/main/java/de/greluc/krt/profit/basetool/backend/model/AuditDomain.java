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
 * The functional area an {@link AuditEvent} belongs to, keeping the per-area logs in the shared
 * {@code audit_event} table logically separate (REQ-AUDIT-001, ADR-0037). The bank trail is not a
 * value here; it has its own {@code bank_audit_event} table.
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
   * Org-unit role and membership management (Rollen &amp; Mitglieder): memberships, leadership
   * ranks and the Logistician / Mission-Manager capability flags.
   */
  ROLE,

  /**
   * Promotion system (Beförderung): the per-Staffel promotion catalogue, the rank-requirement rules
   * and the per-member evaluations. For an evaluation the subject is the graded category and the
   * member is the target.
   */
  PROMOTION,

  /**
   * Materialbörse, the material-exchange trade board (REQ-MARKET-001): offers, requests and
   * interest registrations. The subject is the offer and the anbieter the target; interested
   * members are recorded only as counts.
   */
  MARKET
}
