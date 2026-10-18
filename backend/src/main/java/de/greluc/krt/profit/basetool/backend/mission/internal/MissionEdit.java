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

package de.greluc.krt.profit.basetool.backend.mission.internal;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import java.util.UUID;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * What a mission mutation reports back to {@link MissionEditor}: the audit event it caused, or none
 * when the call changed nothing (REQ-AUDIT-001).
 *
 * @param type the audit event type, or {@code null} when nothing changed
 * @param targetUserId the affected user for user-centric events, or {@code null}
 * @param details the compact audit payload without user free text, or {@code null}
 */
public record MissionEdit(
    @Nullable AuditEventType type, @Nullable UUID targetUserId, @Nullable CharSequence details) {

  /**
   * An edit audited without a target user.
   *
   * @param type the audit event type
   * @param details the audit payload, or {@code null}
   * @return the edit outcome
   */
  @Contract("_, _ -> new")
  public static @NotNull MissionEdit audited(
      @NotNull AuditEventType type, @Nullable CharSequence details) {
    return new MissionEdit(type, null, details);
  }

  /**
   * An edit audited against an affected user.
   *
   * @param type the audit event type
   * @param targetUserId the affected user, or {@code null}
   * @param details the audit payload, or {@code null}
   * @return the edit outcome
   */
  @Contract("_, _, _ -> new")
  public static @NotNull MissionEdit audited(
      @NotNull AuditEventType type, @Nullable UUID targetUserId, @Nullable CharSequence details) {
    return new MissionEdit(type, targetUserId, details);
  }

  /**
   * A call that changed nothing and records no audit event.
   *
   * @return the empty outcome
   */
  @Contract(" -> new")
  public static @NotNull MissionEdit unchanged() {
    return new MissionEdit(null, null, null);
  }
}
