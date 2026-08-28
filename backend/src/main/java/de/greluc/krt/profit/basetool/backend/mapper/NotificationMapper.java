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

package de.greluc.krt.profit.basetool.backend.mapper;

import de.greluc.krt.profit.basetool.backend.model.Notification;
import de.greluc.krt.profit.basetool.backend.model.dto.NotificationDto;
import de.greluc.krt.profit.basetool.backend.support.NotificationParamsCodec;
import org.mapstruct.Mapper;

/**
 * MapStruct mapper from {@link Notification} entities to their outbound {@link NotificationDto}.
 * The {@code type} enum is rendered to its name and the opaque JSON {@code params} column is
 * expanded to a map via {@link NotificationParamsCodec}; {@code recipientUserId} is deliberately
 * not exposed.
 */
@Mapper(config = CentralMapperConfig.class, uses = NotificationParamsCodec.class)
public interface NotificationMapper {

  /**
   * Maps a notification entity to its read DTO, decoding the stored JSON parameters.
   *
   * @param notification the entity to map
   * @return the outbound DTO
   */
  NotificationDto toDto(Notification notification);
}
