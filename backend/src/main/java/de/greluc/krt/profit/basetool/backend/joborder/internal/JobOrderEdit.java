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

package de.greluc.krt.profit.basetool.backend.joborder.internal;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import java.util.List;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * What a job-order mutation reports back to {@link JobOrderEditor}: the order to project, the audit
 * event it caused and the notices to publish (REQ-AUDIT-001).
 *
 * @param order the managed order after the mutation, possibly a re-fetched instance
 * @param type the audit event type, or {@code null} when nothing changed
 * @param details the compact audit payload without user free text, or {@code null}
 * @param notices the events to publish after the audit event, in order
 */
public record JobOrderEdit(
    @NotNull JobOrder order,
    @Nullable AuditEventType type,
    @Nullable CharSequence details,
    @NotNull List<Object> notices) {

  /**
   * An audited edit without notices.
   *
   * @param order the managed order after the mutation
   * @param type the audit event type
   * @param details the audit payload
   * @return the edit outcome
   */
  @Contract("_, _, _ -> new")
  public static @NotNull JobOrderEdit audited(
      @NotNull JobOrder order, @NotNull AuditEventType type, @Nullable CharSequence details) {
    return new JobOrderEdit(order, type, details, List.of());
  }

  /**
   * A call that changed nothing; it records no audit event and publishes no notice.
   *
   * @param order the unchanged order
   * @return the empty outcome
   */
  @Contract("_ -> new")
  public static @NotNull JobOrderEdit unchanged(@NotNull JobOrder order) {
    return new JobOrderEdit(order, null, null, List.of());
  }

  /**
   * This outcome with the given notices published after its audit event.
   *
   * @param events the events to publish, in order
   * @return a copy carrying the notices
   */
  @Contract("_ -> new")
  public @NotNull JobOrderEdit notifying(@NotNull List<?> events) {
    return new JobOrderEdit(order, type, details, List.<Object>copyOf(events));
  }
}
