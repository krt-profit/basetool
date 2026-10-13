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

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The peer projection of a {@link UserDto}, shared by every surface that embeds another member's
 * user record. Lives in the dependency-leaf {@code support} package.
 */
public final class UserDtoRedaction {

  private UserDtoRedaction() {}

  /**
   * Returns the slim peer view, keeping only {@code id}, {@code username}, {@code displayName},
   * {@code effectiveName}, {@code rank}, {@code inKeycloak}, {@code squadron}, {@code squadrons}
   * and {@code version}.
   *
   * <p>Unlike {@code MissionPeerRedactor.cleanupUserForPeer}, it keeps {@code squadron} and {@code
   * squadrons}.
   *
   * @param dto the persisted user DTO, or {@code null}.
   * @return the peer-shaped DTO, or {@code null} when {@code dto} was {@code null}.
   */
  @Contract("null -> null; !null -> !null")
  public static @Nullable UserDto toPeerShape(@Nullable UserDto dto) {
    if (dto == null) {
      return null;
    }
    return new UserDto(
        dto.id(),
        dto.username(),
        dto.displayName(),
        dto.effectiveName(),
        null,
        dto.rank(),
        null,
        null,
        null,
        null,
        false,
        false,
        dto.inKeycloak(),
        dto.squadron(),
        dto.squadrons(),
        dto.version(),
        null,
        null);
  }

  /**
   * Convenience overload used where a caller-dependent decision has already been made.
   *
   * @param dto the persisted user DTO, or {@code null}.
   * @param redact whether to apply the peer projection.
   * @return the peer-shaped DTO when {@code redact}, otherwise {@code dto} unchanged.
   */
  public static @Nullable UserDto toPeerShapeIf(@Nullable UserDto dto, boolean redact) {
    return redact ? toPeerShape(dto) : dto;
  }

  /**
   * Re-attaches the caller's own email to a DTO the mapper produced without it, for the {@code
   * /me*} self endpoints; every other projection keeps the email {@code null}.
   *
   * @param dto the email-free DTO from the mapper
   * @param user the caller's own entity, the source of the email
   * @return a copy of {@code dto} with {@code email} taken from {@code user}
   */
  public static @NotNull UserDto withSelfEmail(@NotNull UserDto dto, @NotNull User user) {
    return new UserDto(
        dto.id(),
        dto.username(),
        dto.displayName(),
        dto.effectiveName(),
        user.getEmail(),
        dto.rank(),
        dto.description(),
        dto.roles(),
        dto.permissions(),
        dto.lastReadAnnouncementId(),
        dto.isLogistician(),
        dto.isMissionManager(),
        dto.inKeycloak(),
        dto.squadron(),
        dto.squadrons(),
        dto.version(),
        dto.joinDate(),
        dto.discordLinked());
  }
}
