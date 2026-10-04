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

package de.greluc.krt.profit.basetool.backend.identity.api.events;

import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * A member left the organisation, as the backend learned from the identity provider: their account
 * was disabled, lost every role, or no longer exists there (REQ-XCH-008).
 *
 * @param userId the member
 * @param reason how the departure showed, one of the {@code REASON_*} constants
 */
public record MemberDepartedEvent(@NotNull UUID userId, @NotNull String reason) {

  /** The account was disabled in the identity provider. */
  public static final String REASON_DISABLED = "disabled";

  /** The account no longer holds any role. */
  public static final String REASON_ROLE_LOST = "role_lost";

  /** The account no longer exists in the identity provider. */
  public static final String REASON_REMOVED = "removed";
}
