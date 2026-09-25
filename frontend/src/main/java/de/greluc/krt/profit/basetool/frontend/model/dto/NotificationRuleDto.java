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
import java.util.List;
import java.util.UUID;

/**
 * Frontend mirror of the backend {@code NotificationRuleDto}; backend enum fields are carried as
 * {@link String}.
 *
 * @param id rule id
 * @param eventType the trigger the rule matches
 * @param notificationType the type produced per recipient
 * @param description free-text admin description, or {@code null}
 * @param enabled whether the rule is active
 * @param excludeActor whether the triggering user is dropped from recipients
 * @param version optimistic-lock version
 * @param createdAt creation timestamp
 * @param updatedAt last-modification timestamp
 * @param selectors the recipient selectors
 */
public record NotificationRuleDto(
    UUID id,
    @BackendEnumAsString String eventType,
    @BackendEnumAsString String notificationType,
    String description,
    boolean enabled,
    boolean excludeActor,
    Long version,
    Instant createdAt,
    Instant updatedAt,
    List<NotificationRuleSelectorDto> selectors) {}
