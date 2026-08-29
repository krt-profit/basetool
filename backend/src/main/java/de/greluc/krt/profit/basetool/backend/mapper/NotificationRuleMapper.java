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

import de.greluc.krt.profit.basetool.backend.model.NotificationRule;
import de.greluc.krt.profit.basetool.backend.model.NotificationRuleSelector;
import de.greluc.krt.profit.basetool.backend.model.dto.NotificationRuleDto;
import de.greluc.krt.profit.basetool.backend.model.dto.NotificationRuleSelectorDto;
import org.mapstruct.Mapper;

/**
 * MapStruct mapper from notification-rule entities to their outbound DTOs. Write requests are
 * applied to entities by {@code NotificationRuleService} directly (the selector back-reference and
 * full-replace semantics are clearer in code than in a generated mapper), so this mapper is
 * read-only.
 */
@Mapper(config = CentralMapperConfig.class)
public interface NotificationRuleMapper {

  /**
   * Maps a rule entity (with its selectors) to its read DTO.
   *
   * @param rule the rule entity
   * @return the read DTO
   */
  NotificationRuleDto toDto(NotificationRule rule);

  /**
   * Maps one selector entity to its read DTO.
   *
   * <p>Name-matched again since #1640 renamed the DTO property to {@code userId} alongside the
   * column. It briefly needed an explicit {@code @Mapping} bridge, and the reason is worth keeping:
   * MapStruct matches by name, so while the two disagreed it mapped the property to {@code null}
   * <em>silently</em>, with a green build. {@code NotificationRuleMapperTest} is what catches that.
   *
   * @param selector the selector entity
   * @return the read DTO
   */
  NotificationRuleSelectorDto toDto(NotificationRuleSelector selector);
}
