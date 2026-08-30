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

package de.greluc.krt.profit.basetool.backend.support;

import de.greluc.krt.profit.basetool.backend.model.dto.UserDto;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Nullable;

/**
 * The one peer projection of a {@link UserDto}, shared by every surface that embeds another
 * member's user record.
 *
 * <p>It exists because the projection was previously a private helper of {@code UserController},
 * which meant the direct read {@code GET /api/v1/users/&#123;id&#125;} returned the slim shape
 * while every aggregate that <em>nests</em> a {@code UserDto} returned the full one to the same
 * caller - the same person, more data, through a different door. {@code GET
 * /api/v1/orders/&#123;id&#125;} was the instance the audit named: it redacts the requester tier
 * but hands every other viewer, including a member of another Staffel reading through the SK public
 * escape, each assignee's complete record with {@code roles}, {@code permissions}, {@code
 * description}, {@code joinDate} and {@code discordLinked}. The assignee chips render {@code
 * effectiveName} and nothing else.
 *
 * <p>Kept in the dependency-leaf {@code support} package so controllers, mappers and services can
 * all reach it without a package cycle.
 */
public final class UserDtoRedaction {

  private UserDtoRedaction() {}

  /**
   * Returns the slim peer view: keeps the identification tuple ({@code id}, {@code username},
   * {@code displayName}, {@code effectiveName}, {@code rank}, {@code inKeycloak}, {@code squadron},
   * {@code squadrons}, {@code version}) and drops everything else - {@code email} (already {@code
   * null} out of the mapper), {@code description}, {@code roles}, {@code permissions}, {@code
   * lastReadAnnouncementId}, the {@code isLogistician} / {@code isMissionManager} flags, {@code
   * joinDate} and {@code discordLinked}.
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
        null, // email
        dto.rank(),
        null, // description
        null, // roles
        null, // permissions
        null, // lastReadAnnouncementId
        false, // isLogistician
        false, // isMissionManager
        dto.inKeycloak(),
        dto.squadron(),
        dto.squadrons(),
        dto.version(),
        null, // joinDate
        null // discordLinked - the Discord-link status is an admin-only column
        );
  }

  /**
   * Peer-shapes every assignee's nested user record of a job order.
   *
   * @param assignees the assignee rows as mapped, or {@code null}.
   * @return the same rows with peer-shaped users, or {@code null} for a {@code null} input.
   */
  @Contract("null -> null; !null -> !null")
  public static @Nullable java.util.List<
          de.greluc.krt.profit.basetool.backend.model.dto.JobOrderAssigneeDto>
      toPeerShapedAssignees(
          @Nullable
              java.util.List<de.greluc.krt.profit.basetool.backend.model.dto.JobOrderAssigneeDto>
                  assignees) {
    if (assignees == null) {
      return null;
    }
    return assignees.stream()
        .map(
            a ->
                new de.greluc.krt.profit.basetool.backend.model.dto.JobOrderAssigneeDto(
                    toPeerShape(a.user()), a.note(), a.version()))
        .toList();
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
}
