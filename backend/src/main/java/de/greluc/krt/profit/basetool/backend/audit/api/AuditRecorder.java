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

package de.greluc.krt.profit.basetool.backend.audit.api;

import java.time.Instant;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Records activity audit events inside the caller's business transaction (REQ-AUDIT-001, plan
 * §5.3).
 *
 * <p>The audit module's published entry point: every other module records through this type and
 * never through the audit module's internals. {@link #record} joins the caller's transaction
 * ({@code MANDATORY}), so a failed insert rolls the audited mutation back.
 */
public interface AuditRecorder {

  /**
   * Appends one audit event for the current caller within the surrounding transaction; the domain
   * comes from {@code eventType.domain()}.
   *
   * @param eventType what happened; its domain pins the row's area
   * @param subjectId the primary affected aggregate's id, or {@code null} for aggregate-less events
   * @param subjectLabel the affected aggregate's label snapshot, or {@code null}
   * @param targetUserId the affected user for user-centric events, or {@code null}
   * @param details compact {@code key=value} payload without user free text, typically an {@link
   *     AuditDetails}; or {@code null}
   * @throws org.springframework.transaction.IllegalTransactionStateException when no transaction is
   *     active
   */
  void record(
      @NotNull AuditEventType eventType,
      @Nullable UUID subjectId,
      @Nullable String subjectLabel,
      @Nullable UUID targetUserId,
      @Nullable CharSequence details);

  /**
   * Whether an event of one type was recorded about one subject at or after a time.
   *
   * @param eventType the event type; its domain narrows the lookup
   * @param subjectId the subject's id
   * @param since the inclusive lower bound
   * @return {@code true} when such an event exists
   */
  boolean recordedSince(
      @NotNull AuditEventType eventType, @NotNull UUID subjectId, @NotNull Instant since);
}
