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

package de.greluc.krt.profit.basetool.frontend.config;

import org.jetbrains.annotations.NotNull;

/**
 * Stands in for a session value that could not be read, carrying the little that may safely be said
 * about why.
 *
 * <p><strong>Why a sentinel rather than {@code null}.</strong> {@code null} is what Spring Session
 * itself uses for "this attribute is not set", and tombstones are pervasive here — {@code
 * BackendRoleSyncFilter} and {@code TermsAcceptanceGateFilter} both {@code removeAttribute} on
 * every re-check, Spring Security clears its {@code authorizationRequest} after the OAuth2
 * callback, and {@code SessionFlashMapManager} expires flash maps. Returning {@code null} for an
 * unreadable value therefore made the poison indistinguishable from routine housekeeping, which is
 * why the 2026-09-02 log storm could say only "a value was dropped" and never <em>which</em> one.
 * {@link SessionAttributeDiagnosticMapper} sees the whole hash, so it can pair this marker with the
 * attribute name and then hand the delegate the {@code null} the rest of the stack expects.
 *
 * <p><strong>Nothing here is payload.</strong> The three fields are the failure's exception type,
 * the unresolved Jackson type id and the base type it was being resolved against — class names and
 * fixed tokens. The exception <em>message</em> is deliberately absent: Jackson quotes the offending
 * JSON fragment in some of its messages, and a session payload holds OAuth2 tokens.
 *
 * @param cause simple name of the deepest cause, e.g. {@code InvalidTypeIdException}.
 * @param typeId the unresolved type id, or one of the fixed tokens {@link #TYPE_ID_ABSENT} / {@link
 *     #TYPE_ID_NOT_A_CLASS_NAME} / {@link #NOT_APPLICABLE}. See {@link
 *     FaultTolerantSessionSerializer} for the triage key these three answer.
 * @param baseType the type the id was being resolved against ({@code java.lang.Object} for a
 *     session attribute), or {@link #NOT_APPLICABLE}.
 */
record UnreadableSessionValue(
    @NotNull String cause, @NotNull String typeId, @NotNull String baseType) {

  /**
   * Rendered when Jackson reports no type id at all, i.e. the stored JSON object carried no {@code
   * @class} property. The single most informative value this field can take: it means the value was
   * written by a <em>final</em> runtime type (a record, a JDK immutable collection, any final
   * class), which the {@code NON_FINAL} default typing writes without a type id and then refuses to
   * read back.
   */
  static final String TYPE_ID_ABSENT = "absent";

  /**
   * Rendered when Jackson did report a type id but it does not look like a Java class name.
   *
   * <p>Not defensive dressing — measured. A {@code List.of(…)} stored as a session attribute is
   * written as a bare JSON array, and the reader then takes <em>element zero</em> for the type id:
   * the failure reads {@code Could not resolve type id 'a' as a subtype of java.lang.Object}, where
   * {@code a} is the member's own data. Logging the id verbatim would put session payload in a log
   * line, which REQ-OBS-004 forbids outright.
   */
  static final String TYPE_ID_NOT_A_CLASS_NAME = "not-a-class-name";

  /** Rendered for a field the failure does not carry, e.g. a cause that is not a Jackson one. */
  static final String NOT_APPLICABLE = "n/a";
}
