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

package de.greluc.krt.profit.basetool.frontend.model.dto;

import java.time.Instant;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Frontend mirror of one generic activity audit-trail row (REQ-AUDIT-001) for the admin-only
 * unified viewer. Covers the inventory / job-order / refinery / personal-inventory areas; the bank
 * tab uses the separate {@link BankAuditEventDto}. The enums arrive as their string names.
 *
 * @param id the audit row's id
 * @param occurredAt the mutation instant (UTC)
 * @param domain the functional area name
 * @param eventType the event-type enum name (rendered via {@code admin.audit.event.*} keys)
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
    @BackendEnumAsString String domain,
    @BackendEnumAsString String eventType,
    String actorHandle,
    @Nullable UUID subjectId,
    @Nullable String subjectLabel,
    @Nullable UUID targetUserId,
    @Nullable String details,
    @Nullable String clientId) {}
