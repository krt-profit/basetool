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
import org.jetbrains.annotations.Nullable;

/**
 * View model for one row of the unified admin audit page (REQ-AUDIT-001), adapted from both {@link
 * BankAuditEventDto} and {@link AuditEventDto}.
 *
 * @param occurredAt the mutation instant (UTC), rendered locally by the {@code .utc-time} script
 * @param actorHandle the acting user's handle snapshot
 * @param eventLabelKey the fully-qualified i18n key for the event-type label
 * @param subject the affected subject label, or a dash when the event has no subject
 * @param details the compact details payload
 * @param clientId the bounded label of the client the mutation came through (REQ-AUDIT-005), e.g.
 *     {@code basetool-android}, or {@code null} when not recorded
 */
public record AuditRowView(
    Instant occurredAt,
    String actorHandle,
    String eventLabelKey,
    String subject,
    @Nullable String details,
    @Nullable String clientId) {}
