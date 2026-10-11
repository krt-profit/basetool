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

package de.greluc.krt.profit.basetool.backend.identity.internal;

import de.greluc.krt.profit.basetool.backend.platform.api.PersonMentionSource;
import java.util.List;
import java.util.Set;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * The identity's part of the person search: the columns of accounts, registration decisions and
 * erasure requests that can name a person, and those left out (REQ-SEC-060).
 */
@Component
public class IdentityMentionSource implements PersonMentionSource {

  @Override
  public @NotNull List<Target> targets() {
    return List.of(
        new Target("MEMBER", "app_user", "description", "id", LinkKind.MEMBER),
        new Target("MEMBER", "app_user", "discord_guild_nickname", "id", LinkKind.MEMBER),
        new Target("MEMBER", "app_user", "display_name", "id", LinkKind.MEMBER),
        new Target("MEMBER", "app_user", "email", "id", LinkKind.MEMBER),
        new Target("MEMBER", "app_user", "rsi_handle", "id", LinkKind.MEMBER),
        new Target("MEMBER", "app_user", "username", "id", LinkKind.MEMBER),
        new Target("DELETION_REQUEST", "deletion_request", "decision_note", "id", null),
        new Target("REGISTRATION", "user_approval_event", "reason", "id", null));
  }

  @Override
  public @NotNull Set<String> exemptColumns() {
    return Set.of(
        "app_user.approval_status",
        "app_user.default_payout_preference",
        "app_user.discord_user_id",
        "deletion_request.status",
        "role.code",
        "role.description",
        "role.name",
        "role_permissions.permission",
        "terms_acceptance.terms_version",
        "user_approval_event.decision");
  }
}
