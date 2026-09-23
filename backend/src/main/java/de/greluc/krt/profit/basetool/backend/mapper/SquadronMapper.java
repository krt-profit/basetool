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

import de.greluc.krt.profit.basetool.backend.model.OrgUnit;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.model.dto.SquadronDto;
import de.greluc.krt.profit.basetool.backend.model.dto.SquadronReferenceDto;
import org.jetbrains.annotations.Nullable;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/** MapStruct mapper between Squadron entities and DTOs. */
@Mapper(config = CentralMapperConfig.class)
public interface SquadronMapper {
  /**
   * Maps a {@link Squadron} entity to its outbound DTO. {@code isPromotionEnabled} is taken from
   * the entity's {@code isPromotionEnabled} accessor and surfaces on the wire as a Boolean so the
   * admin-settings page can render the per-squadron toggle without a second lookup.
   *
   * @param entity the squadron entity to project; {@code null} maps to {@code null}.
   * @return the full squadron DTO.
   */
  @Mapping(target = "isPromotionEnabled", source = "promotionEnabled")
  @Mapping(target = "isProfitEligible", source = "profitEligible")
  SquadronDto toDto(Squadron entity);

  /**
   * Narrow reference projection (id + name + shorthand) embedded into the per-aggregate list /
   * detail DTOs so the squadron column / badge can be rendered without an extra round-trip. Used
   * via {@code @Mapper(uses = SquadronMapper.class)} from MissionMapper, JobOrderMapper,
   * InventoryItemMapper, RefineryOrderMapper, OperationMapper and ShipMapper.
   *
   * @param entity the squadron entity to project; {@code null} maps to {@code null}.
   * @return the slim reference DTO (id + name + shorthand).
   */
  SquadronReferenceDto toReferenceDto(Squadron entity);

  /**
   * OrgUnit-typed overload of {@link #toReferenceDto(Squadron)} used by the per-aggregate mappers
   * (Mission, Operation, Ship, InventoryItem, RefineryOrder, JobOrder) after R9 Step 2 dropped the
   * legacy {@code owningSquadron} / {@code creatingSquadron} / {@code requestingSquadron} fields
   * from those aggregates. The aggregates now expose an {@code OrgUnit}-typed owner; the DTOs still
   * publish a {@code SquadronReferenceDto} field so the API contract and the frontend templates
   * stay stable.
   *
   * <p>The reference triplet ({@code id}, {@code name}, {@code shorthand}) lives entirely on the
   * shared {@link OrgUnit} base, so both kinds project identically: a {@code Squadron} and a {@code
   * Spezialkommando} each surface their own id/name/shorthand into the slim DTO. This deliberately
   * supersedes the earlier "SK surfaces as {@code null}" behaviour so an SK-owned mission, order,
   * ship, etc. renders its SK badge in the list columns instead of a blank {@code -}. Reading the
   * base getters directly (rather than {@code instanceof}-dispatching to {@link
   * #toReferenceDto(Squadron)}) also sidesteps the lazy-proxy {@code instanceof} pitfall, where an
   * uninitialised {@code OrgUnit} proxy would not match its concrete subclass.
   *
   * @param orgUnit the owning org unit; may be {@code null}.
   * @return the reference DTO carrying the org unit's id/name/shorthand, or {@code null} when the
   *     {@code orgUnit} is {@code null}.
   */
  @Nullable
  default SquadronReferenceDto orgUnitToReferenceDto(OrgUnit orgUnit) {
    if (orgUnit == null) {
      return null;
    }
    return new SquadronReferenceDto(orgUnit.getId(), orgUnit.getName(), orgUnit.getShorthand());
  }

  /**
   * Builds a new {@link Squadron} entity from the inbound DTO. Timestamps are owned by the
   * persistence provider and ignored. {@code promotionEnabled} and {@code profitEligible} are
   * intentionally NOT mapped from the DTO either: both flags are only mutable through their
   * dedicated {@code PATCH /api/v1/squadrons/{id}/promotion-enabled} / {@code .../profit-eligible}
   * endpoints (see {@code SquadronService.setPromotionEnabled} / {@code
   * SquadronService.setProfitEligible}) so an accidental description edit cannot flip either
   * per-squadron toggle. The {@code parent} hierarchy link (epic #692) is ignored too — a unit's
   * Bereich is assigned through the org-hierarchy admin, never a squadron create/update.
   */
  @Mapping(target = "createdAt", ignore = true)
  @Mapping(target = "updatedAt", ignore = true)
  @Mapping(target = "promotionEnabled", ignore = true)
  @Mapping(target = "profitEligible", ignore = true)
  @Mapping(target = "parent", ignore = true)
  @Mapping(target = "department", ignore = true)
  Squadron toEntity(SquadronDto dto);
}
