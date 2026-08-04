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

/**
 * The header naming the member the ingest gateway is acting for (ADR-0129).
 *
 * <p>All that remains of {@code ActingSubjectResolver}. That class resolved the acting subject per
 * call site, which was the design this PR replaced: the subject is now swapped into the {@link
 * org.springframework.security.core.context.SecurityContext} once, by {@code ActingMemberFilter},
 * so nothing downstream resolves anything. Keeping the resolver as dead code would have been worse
 * than deleting it — its Javadoc still promised that the header "cannot grant a role, widen a scope
 * or select an org unit", which is precisely the sentence this change had to amend.
 *
 * <p>Not an enum or a utility with static helpers: there is one constant and no behaviour.
 */
public final class ActingMemberHeader {

  /**
   * Names the member the ingest gateway is acting for.
   *
   * <p>Must stay identical to {@code BackendImportClient.ON_BEHALF_OF_HEADER} in the ingest module.
   * The two are separate modules with no shared code, so the literal is duplicated on purpose and
   * pinned by {@code OnBehalfOfHeaderParityTest} — a rename on one side alone would not fail, it
   * would silently stop attributing ingest writes to the sending member.
   */
  public static final String ON_BEHALF_OF_HEADER = "X-Ingest-On-Behalf-Of";

  /** Not instantiable: a constant holder, not a component. */
  private ActingMemberHeader() {}
}
