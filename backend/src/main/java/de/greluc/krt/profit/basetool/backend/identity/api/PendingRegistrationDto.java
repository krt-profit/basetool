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

package de.greluc.krt.profit.basetool.backend.identity.api;

import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One row of the admin registration-approval queue; carries no Discord id.
 *
 * @param id the pending user's id
 * @param username the user's effective display name
 * @param serverNickname the Discord server nickname (REQ-DATA-018), or {@code null}
 * @param registeredAt when the registration first appeared
 * @param decidedAt the last decision time (REQ-SEC-034), or {@code null} while awaiting a decision
 * @param callsignCollision whether another account already holds this callsign, case-insensitively,
 *     so approving would create a second account
 * @param version optimistic-lock version, echoed back on approve or reject
 */
public record PendingRegistrationDto(
    UUID id,
    String username,
    @Nullable String serverNickname,
    Instant registeredAt,
    @Nullable Instant decidedAt,
    boolean callsignCollision,
    Long version) {

  /**
   * Maps one registration against a pre-resolved set of colliding callsigns.
   *
   * @param user the registration to map
   * @param collidingCallsigns lower-cased usernames held by more than one account
   * @return the DTO, with {@code callsignCollision} resolved from the set
   */
  public static @NotNull PendingRegistrationDto from(
      @NotNull User user, @NotNull Set<String> collidingCallsigns) {
    return new PendingRegistrationDto(
        user.getId(),
        user.getEffectiveName(),
        user.getDiscordGuildNickname(),
        user.getCreatedAt(),
        user.getApprovedAt(),
        user.getUsername() != null
            && collidingCallsigns.contains(user.getUsername().toLowerCase(Locale.ROOT)),
        user.getVersion());
  }
}
