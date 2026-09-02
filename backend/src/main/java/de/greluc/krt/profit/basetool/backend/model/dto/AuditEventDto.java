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

package de.greluc.krt.profit.basetool.backend.model.dto;

import de.greluc.krt.profit.basetool.backend.model.AuditDomain;
import de.greluc.krt.profit.basetool.backend.model.AuditEventType;
import java.time.Instant;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Response payload for one activity audit-trail row (REQ-AUDIT-001) — admin-only surface. The actor
 * and subject render from the deletion-proof handle/label snapshots, never from a live join.
 *
 * @param id the audit row's id
 * @param occurredAt the mutation instant (UTC)
 * @param domain the functional area the row belongs to
 * @param eventType what happened
 * @param actorHandle the acting user's handle snapshot
 * @param subjectId the primary affected aggregate's id, when the event concerns one
 * @param subjectLabel the affected aggregate's human-readable label snapshot
 * @param targetUserId the affected user for user-centric events
 * @param details compact human-readable details payload
 * @param clientId which client the mutation came through (REQ-AUDIT-005) — a bounded label, {@code
 *     null} only on rows written before the column existed
 */
public record AuditEventDto(
    UUID id,
    Instant occurredAt,
    AuditDomain domain,
    AuditEventType eventType,
    String actorHandle,
    @Nullable UUID subjectId,
    @Nullable String subjectLabel,
    @Nullable UUID targetUserId,
    @Nullable String details,
    @Nullable String clientId) {}
