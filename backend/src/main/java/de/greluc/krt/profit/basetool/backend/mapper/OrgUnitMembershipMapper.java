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
 * MapStruct mapper from {@link OrgUnitMembership} entities to their wire shape. Unpacks the
 * embedded {@link de.greluc.krt.profit.basetool.backend.model.OrgUnitMembershipId} composite key
 * into two flat UUID fields and reads {@code user.effectiveName} (display name with username
 * fallback) through the LAZY association so the admin roster page can render member chips without a
 * per-row join.
 *
 * <p>Inverse mapping (DTO → entity) is intentionally absent — memberships are created through the
 * service's {@code addMember(orgUnitId, userId)} helper which constructs the embedded id from the
 * two URL path variables, and updated through the {@link
 * de.greluc.krt.profit.basetool.backend.model.dto.MembershipFlagsPatchRequest} / {@link
 * de.greluc.krt.profit.basetool.backend.model.dto.MembershipLeadToggleRequest} payloads which apply
 * to an already-loaded entity. There is no inbound DTO → entity flow that needs a mapper.
 */
@Mapper(config = CentralMapperConfig.class, imports = MembershipRole.class)
public interface OrgUnitMembershipMapper {

  /**
   * Maps a persisted {@link OrgUnitMembership} to its wire shape. The {@code userId} / {@code
   * orgUnitId} fields come from the embedded {@link OrgUnitMembership#getId()}; the discriminator
   * and timestamps come straight from the entity. {@code userDisplayName} reads {@code
   * user.effectiveName} through the LAZY association — that helper returns the display name when
   * set and falls back to {@code username} when it is {@code null}/blank, so the admin roster never
   * shows an empty cell for users who have not configured a display name. Callers that already hold
   * a Hibernate session see it materialised; callers that hold a detached entity will get the value
   * if the user fields were eagerly accessed before detach (the admin roster code path always reads
   * it during the same transaction).
   *
   * @param entity the membership row; never {@code null}.
   * @return the wire-shape DTO, never {@code null}.
   */
  @Mapping(target = "userId", source = "id.userId")
  @Mapping(target = "orgUnitId", source = "id.orgUnitId")
  @Mapping(target = "userDisplayName", source = "user.effectiveName")
  @Mapping(target = "isLogistician", source = "logistician")
  @Mapping(target = "isMissionManager", source = "missionManager")
  @Mapping(target = "isLead", expression = "java(entity.getRole() == MembershipRole.SK_LEAD)")
  OrgUnitMembershipDto toDto(OrgUnitMembership entity);
}
