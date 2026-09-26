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

import java.util.List;

/**
 * Frontend mirror of the backend {@code NotificationRuleWriteRequest}; enum fields are sent as
 * {@link String}.
 *
 * @param eventType the trigger to match
 * @param notificationType the type produced per recipient
 * @param description free-text description, or {@code null}
 * @param enabled whether the rule is active
 * @param excludeActor whether to drop the triggering user from recipients
 * @param version expected version on update, {@code null} on create
 * @param selectors the recipient selectors (may be empty)
 */
public record NotificationRuleWriteRequest(
    @BackendEnumAsString String eventType,
    @BackendEnumAsString String notificationType,
    String description,
    boolean enabled,
    boolean excludeActor,
    Long version,
    List<NotificationRuleSelectorWriteRequest> selectors) {}
