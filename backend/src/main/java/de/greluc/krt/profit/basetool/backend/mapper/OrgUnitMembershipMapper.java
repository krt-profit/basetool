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

import de.greluc.krt.profit.basetool.backend.model.MembershipRole;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.model.dto.OrgUnitMembershipDto;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * Maps {@link OrgUnitMembership} entities to their wire shape, flattening the composite {@link
 * de.greluc.krt.profit.basetool.backend.model.OrgUnitMembershipId} and reading the user's effective
 * name.
 */
@Mapper(config = CentralMapperConfig.class, imports = MembershipRole.class)
public interface OrgUnitMembershipMapper {

  /**
   * Maps a membership to its wire shape, with {@code userDisplayName} from {@code
   * user.effectiveName} (display name, falling back to username).
   *
   * <p>Reads the lazy {@code user} association, so call it inside the loading transaction.
   *
   * @param entity the membership row; never {@code null}
   * @return the DTO; never {@code null}
   */
  @Mapping(target = "userId", source = "id.userId")
  @Mapping(target = "orgUnitId", source = "id.orgUnitId")
  @Mapping(target = "userDisplayName", source = "user.effectiveName")
  @Mapping(target = "isLogistician", source = "logistician")
  @Mapping(target = "isMissionManager", source = "missionManager")
  @Mapping(target = "isLead", expression = "java(entity.getRole() == MembershipRole.SK_LEAD)")
  OrgUnitMembershipDto toDto(OrgUnitMembership entity);
}
