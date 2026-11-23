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

package de.greluc.krt.profit.basetool.backend.joborder.api;

/**
 * Names the read check a job-order command applies to the loaded order beside the writer rule; the
 * commands take it as a parameter and decide it under their order locks on every call (ADR-0405).
 */
public enum JobOrderReadCheck {

  /**
   * The web's complete-order read of the signed-in caller, from the request's authorities and the
   * honoured pin (REQ-ORDERS-052).
   */
  CONTEXTUAL,

  /**
   * The exchange's read set of the request's acting member: a current own relation through the
   * active units they directly belong to while holding {@code KRT Member}, or their assignee entry,
   * decided from the database (REQ-OXCH-001, ADR-0407).
   */
  OWN_RELATION
}
