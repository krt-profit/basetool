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

import de.greluc.krt.profit.basetool.backend.model.JobType;
import de.greluc.krt.profit.basetool.backend.model.dto.JobTypeDto;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.mapstruct.AfterMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

/** MapStruct mapper between Job Type entities and DTOs. */
@Mapper(config = CentralMapperConfig.class)
public interface JobTypeMapper {

  /**
   * Maps a {@link JobType} entity to its DTO, flattening {@code parent.id} into {@code parentId}.
   */
  @Mapping(target = "parentId", source = "parent.id")
  @Mapping(target = "isLeadershipRole", source = "leadershipRole")
  @Mapping(target = "isMissionLead", source = "missionLead")
  JobTypeDto toDto(JobType entity);

  /**
   * Builds a new {@link JobType} entity from the DTO. The {@code parent} association is resolved
   * separately in {@link #setParentAfterMapping}; timestamps stay owned by the persistence
   * provider.
   */
  @Mapping(target = "parent", ignore = true)
  @Mapping(target = "createdAt", ignore = true)
  @Mapping(target = "updatedAt", ignore = true)
  @Mapping(target = "leadershipRole", source = "isLeadershipRole")
  @Mapping(target = "missionLead", source = "isMissionLead")
  JobType toEntity(JobTypeDto dto);

  /**
   * After-mapping callback that rewires the {@code parent} association from {@code source.parentId}
   * - a JPA stub with only the id is enough because the persistence provider resolves it on
   * persist.
   */
  @AfterMapping
  default void setParentAfterMapping(@MappingTarget JobType target, @NotNull JobTypeDto source) {
    UUID parentId = source.parentId();
    if (parentId != null) {
      JobType parent = new JobType();
      parent.setId(parentId);
      target.setParent(parent);
    } else {
      target.setParent(null);
    }
  }
}
