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

package de.greluc.krt.profit.basetool.backend.support;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The audit subject label for a job order: {@code #<displayId>}, and nothing else (REQ-AUDIT-001).
 *
 * <p><b>It used to carry the order's contact handle</b> — {@code #<displayId> '<handle>'} —
 * composed independently in seven services. That was two defects in one string.
 *
 * <p><b>It put a person's name in a column REQ-AUDIT-001 defines as non-personal.</b> The handle is
 * the order's contact ({@code orders.create.handle}, "Handle des Ansprechpartners") and is
 * frequently somebody outside the organisation with no account at all. The requirement's own
 * wording gave "an order title" as an example of a <em>non-personal</em> label, which is how the
 * Art. 15 export came to select the column, and how the person search came to need it as a target.
 *
 * <p><b>And the erasure could never reach it.</b> {@code
 * AuditEventRepository.anonymiseSubjectLabel} matches the <em>whole</em> label against the name —
 * deliberately, because a label that merely contains a handle belongs to a different person's row
 * and rewriting it would erase somebody who did not ask. For contact {@code Bob} on order #12 the
 * stored label is {@code #12 'Bob'}, so the comparison is false and the name stayed readable for
 * the full 730-day retention. The registry recorded the same handle twice, as "somebody else" on
 * the label and as "the order's contact, matched by text" on {@code job_order.handle}, so the
 * residue counted as handled and never reached the manual review either.
 *
 * <p>Composing only the display id fixes both at the source, for every row written from now on. The
 * order's contact is still erasable where it actually lives — {@code job_order.handle}, matched by
 * the whole value — and the display id is what identifies the order to a reader anyway.
 *
 * <p><b>Rows already written keep their composed label.</b> Back-dating them would make the trail
 * claim something that never happened, which is the same reasoning REQ-AUDIT-001 applies to a
 * renamed detail value. They stay a person-name surface, the person search finds them, and {@link
 * HandleErasureCoverage} records them as an administrator's manual step.
 *
 * <p>One helper rather than seven copies, because seven copies is how the composition came to be
 * changed in one place and not the others.
 */
public final class JobOrderAuditLabel {

  /** Not instantiable. */
  private JobOrderAuditLabel() {}

  /**
   * The label for one order.
   *
   * @param displayId the order's human-facing running number, or {@code null} for an order whose
   *     number has not been assigned yet
   * @return {@code #<displayId>}, or {@code #?} when the number is not there. The trail needs
   *     <em>some</em> subject label for the row to be readable, and an unnumbered order is a state
   *     that should not reach an audited mutation — so it is marked rather than hidden.
   */
  public static @NotNull String of(@Nullable Integer displayId) {
    return "#" + (displayId == null ? "?" : displayId);
  }
}
