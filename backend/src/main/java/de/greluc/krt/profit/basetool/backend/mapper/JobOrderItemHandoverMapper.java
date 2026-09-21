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

import de.greluc.krt.profit.basetool.backend.model.GameItem;
import de.greluc.krt.profit.basetool.backend.model.JobOrderItemHandover;
import de.greluc.krt.profit.basetool.backend.model.JobOrderItemHandoverEntry;
import de.greluc.krt.profit.basetool.backend.model.dto.GameItemReferenceDto;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderItemHandoverDto;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderItemHandoverEntryDto;
import org.jetbrains.annotations.Nullable;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/** MapStruct mapper between item-handover entities and DTOs. */
@Mapper(
    config = CentralMapperConfig.class,
    uses = {UserMapper.class, SquadronMapper.class})
public interface JobOrderItemHandoverMapper {

  /**
   * Maps a {@link JobOrderItemHandover} to its DTO, flattening the parent job-order id and
   * projecting the executing-user + squadron audit snapshot through their reference mappers.
   *
   * @param handover the entity to project
   * @return the populated DTO
   */
  @Mapping(source = "jobOrder.id", target = "jobOrderId")
  @Mapping(source = "executingUser", target = "executingUser")
  @Mapping(source = "executingSquadron", target = "executingSquadron")
  JobOrderItemHandoverDto toDto(JobOrderItemHandover handover);

  /**
   * Maps a delivered {@link JobOrderItemHandoverEntry} to its DTO, flattening the ordered-line id
   * and projecting the produced item to a slim reference.
   *
   * @param entry the entity to project
   * @return the populated DTO
   */
  @Mapping(source = "jobOrderItem.id", target = "jobOrderItemId")
  @Mapping(source = "jobOrderItem.gameItem", target = "gameItem")
  JobOrderItemHandoverEntryDto toDto(JobOrderItemHandoverEntry entry);

  /**
   * Projects a {@link GameItem} to the slim reference used in handover entries.
   *
   * @param gameItem the catalogue entity, or {@code null}
   * @return the slim reference, or {@code null} when the input is {@code null}
   */
  @Nullable
  default GameItemReferenceDto toGameItemReference(GameItem gameItem) {
    if (gameItem == null) {
      return null;
    }
    return new GameItemReferenceDto(
        gameItem.getId(),
        gameItem.getName(),
        gameItem.getKind() == null ? null : gameItem.getKind().name());
  }
}
