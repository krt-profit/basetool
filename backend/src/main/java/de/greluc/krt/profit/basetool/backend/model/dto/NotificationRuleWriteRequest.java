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

import de.greluc.krt.profit.basetool.backend.model.NotificationEventType;
import de.greluc.krt.profit.basetool.backend.model.NotificationType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Create/update payload for an admin-managed notification rule. On update the {@code version}
 * carries the expected optimistic-lock version; on create it is {@code null}. The selector list
 * fully replaces the rule's selectors.
 *
 * @param eventType the trigger to match (required)
 * @param notificationType the type produced per recipient (required)
 * @param description free-text description, or {@code null}
 * @param enabled whether the rule is active
 * @param excludeActor whether to drop the triggering user from recipients
 * @param version expected version on update, {@code null} on create
 * @param selectors the recipient selectors (required, may be empty)
 */
public record NotificationRuleWriteRequest(
    @NotNull NotificationEventType eventType,
    @NotNull NotificationType notificationType,
    @Size(max = 255) String description,
    boolean enabled,
    boolean excludeActor,
    Long version,
    @NotNull List<@Valid NotificationRuleSelectorWriteRequest> selectors) {}
