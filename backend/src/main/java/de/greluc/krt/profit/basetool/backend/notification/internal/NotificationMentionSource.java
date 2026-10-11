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

package de.greluc.krt.profit.basetool.backend.notification.internal;

import de.greluc.krt.profit.basetool.backend.platform.api.PersonMentionSource;
import java.util.List;
import java.util.Set;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * The notification's part of the person search: the columns of notification rules that can name a
 * person, and those left out (REQ-SEC-060).
 */
@Component
public class NotificationMentionSource implements PersonMentionSource {

  @Override
  public @NotNull List<Target> targets() {
    return List.of(new Target("NOTIFICATION", "notification_rule", "description", "id", null));
  }

  @Override
  public @NotNull Set<String> exemptColumns() {
    return Set.of(
        "notification.entity_type",
        "notification.params",
        "notification.type",
        "notification_mute.notification_type",
        "notification_rule.event_type",
        "notification_rule.notification_type",
        "notification_rule_selector.context_role",
        "notification_rule_selector.kind",
        "notification_rule_selector.org_relative_role",
        "notification_rule_selector.role_code");
  }

  @Override
  public @NotNull Set<String> exemptButMayHoldNames() {
    return Set.of("notification.params");
  }
}
