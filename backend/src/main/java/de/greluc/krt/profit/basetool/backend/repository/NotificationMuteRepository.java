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

package de.greluc.krt.profit.basetool.backend.repository;

import de.greluc.krt.profit.basetool.backend.model.NotificationMute;
import de.greluc.krt.profit.basetool.backend.model.NotificationType;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Spring Data repository for {@link NotificationMute} (REQ-NOTIF-027). */
@Repository
public interface NotificationMuteRepository extends JpaRepository<NotificationMute, UUID> {

  /**
   * Returns every type one member has muted.
   *
   * @param userId the member
   * @return the member's mute rows; never {@code null}, possibly empty
   */
  List<NotificationMute> findByUserId(UUID userId);

  /**
   * Returns the members among the given ones who have muted a type.
   *
   * @param type the notification type
   * @param userIds the candidate recipients; an empty collection yields an empty set
   * @return the ids of the candidates who muted {@code type}; never {@code null}
   */
  @Query("SELECT m.userId FROM NotificationMute m WHERE m.type = :type AND m.userId IN :userIds")
  Set<UUID> findMutedUserIds(
      @Param("type") NotificationType type, @Param("userIds") Collection<UUID> userIds);

  /**
   * Removes one member's mute of a type; a no-op when none exists.
   *
   * @param userId the member
   * @param type the notification type
   * @return the number of rows removed, {@code 0} or {@code 1}
   */
  @Modifying
  @Query("DELETE FROM NotificationMute m WHERE m.userId = :userId AND m.type = :type")
  int deleteByUserIdAndType(@Param("userId") UUID userId, @Param("type") NotificationType type);

  /**
   * Tells whether a member has muted a type.
   *
   * @param userId the member
   * @param type the notification type
   * @return {@code true} when the mute exists
   */
  boolean existsByUserIdAndType(UUID userId, NotificationType type);
}
