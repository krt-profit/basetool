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
 * A member left the organisation: their account was disabled or no longer exists in the identity
 * provider (REQ-XCH-008), or an admin withdrew their KRT Member role in the Basetool (REQ-SEC-088).
 *
 * @param userId the member
 * @param reason how the departure showed, one of the {@code REASON_*} constants
 */
public record MemberDepartedEvent(@NotNull UUID userId, @NotNull String reason) {

  /** The account was disabled in the identity provider. */
  public static final String REASON_DISABLED = "disabled";

  /** The account no longer exists in the identity provider. */
  public static final String REASON_REMOVED = "removed";

  /** The KRT Member role was withdrawn in the Basetool (REQ-SEC-088). */
  public static final String REASON_MEMBER_ROLE_WITHDRAWN = "member_role_withdrawn";
}
