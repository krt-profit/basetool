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
 * Frontend mirror of one bank audit-trail row (REQ-BANK-012) for the admin-only viewer (A2 mockup).
 * The actor renders from the deletion-proof handle snapshot.
 *
 * @param id the audit row's id
 * @param occurredAt the mutation instant (UTC)
 * @param actorHandle the acting user's handle snapshot
 * @param eventType event type enum name (rendered via {@code admin.bank.audit.type.*} keys)
 * @param accountId the affected account, when the event concerns one
 * @param accountNo the affected account's display number, resolved server-side
 * @param transactionId the created ledger transaction for booking events
 * @param targetUserId the affected user for grant/holder events
 * @param details compact human-readable details payload
 * @param clientId which client the mutation came through (REQ-AUDIT-005) — a bounded label, {@code
 *     null} on rows written before the column existed, where it means "not recorded"
 */
public record BankAuditEventDto(
    UUID id,
    Instant occurredAt,
    String actorHandle,
    @BackendEnumAsString String eventType,
    @Nullable UUID accountId,
    @Nullable String accountNo,
    @Nullable UUID transactionId,
    @Nullable UUID targetUserId,
    @Nullable String details,
    @Nullable String clientId) {}
