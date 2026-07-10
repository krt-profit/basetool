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

import de.greluc.krt.profit.basetool.backend.model.JobOrder;
import de.greluc.krt.profit.basetool.backend.model.JobOrderAssignee;
import de.greluc.krt.profit.basetool.backend.model.JobOrderMaterial;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderAssigneeDto;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderDto;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderMaterialDto;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/** MapStruct mapper between Job Order entities and DTOs. */
@Mapper(
    config = CentralMapperConfig.class,
    uses = {
      InventoryItemMapper.class,
      UserMapper.class,
      MaterialMapper.class,
      JobOrderHandoverMapper.class,
      SquadronMapper.class
    })
public interface JobOrderMapper {

  /**
   * Maps a {@link JobOrder} entity to its outbound DTO. The legacy free-text {@code squadron} field
   * was removed from the DTO together with the V90 DROP COLUMN migration; clients consume the
   * structured {@code requestingSquadron} reference (and its {@code shorthand} sub-field) for a
   * human-readable label.
   *
   * <p>The Phase 2 rework (#342) exposes {@code responsibleOrgUnit} (the processing unit) and
   * {@code requestingOrgUnit} (the customer) on the entity, both typed {@code OrgUnit}; the DTO
   * publishes them as {@code SquadronReferenceDto}. The two explicit mappings below route both
   * fields through {@code SquadronMapper.orgUnitToReferenceDto}, which projects either kind — a
   * Staffel or a Spezialkommando — into the slim reference (id/name/shorthand), so an SK
   * responsible or requester surfaces its SK badge instead of a blank cell. {@code
   * responsibleOrgUnit} is {@code null} only on pre-rework rows not yet backfilled (Phase 3).
   *
   * @param jobOrder the entity to project; {@code null} returns {@code null}.
   * @return the populated outbound DTO.
   */
  @Mapping(target = "responsibleOrgUnit", source = "responsibleOrgUnit")
  @Mapping(target = "requestingOrgUnit", source = "requestingOrgUnit")
  @Mapping(target = "items", ignore = true)
  @Mapping(target = "aggregatedMaterials", ignore = true)
  @Mapping(target = "itemHandovers", ignore = true)
  // Per-order redaction decision is not an entity property; it is stamped by the read path
  // (JobOrderService.getJobOrderById) via JobOrderDto#withRedacted, so the mapper leaves it false.
  @Mapping(target = "redacted", ignore = true)
  JobOrderDto toDto(JobOrder jobOrder);

  /**
   * Maps a {@link JobOrderMaterial} child to its DTO. {@code currentStock} (inventory queried at
   * request time) and the claim fields {@code claims}/{@code openAmount} (populated by the service
   * for SK orders, Phase 5 #345) are all owned by the service layer and stay unmapped here.
   */
  @Mapping(target = "currentStock", ignore = true)
  @Mapping(target = "claims", ignore = true)
  @Mapping(target = "openAmount", ignore = true)
  JobOrderMaterialDto toDto(JobOrderMaterial material);

  /**
   * Maps a single {@link JobOrderAssignee} edge to its DTO: the assigned {@code user} routes
   * through {@code UserMapper}, while {@code note} and the edge's own {@code version} map straight
   * across.
   *
   * @param assignee the assignee edge to project; {@code null} returns {@code null}.
   * @return the populated assignee DTO.
   */
  JobOrderAssigneeDto toDto(JobOrderAssignee assignee);

  /**
   * Maps a set of {@link JobOrderAssignee} edges into a DTO list sorted by the assignee's effective
   * name (case-insensitive), so the Bearbeiter list renders in a stable order across reloads and
   * fragment refreshes.
   *
   * @param assignees the assignee edges to project; {@code null} returns {@code null}.
   * @return the sorted assignee DTO list.
   */
  default List<JobOrderAssigneeDto> mapAndSortAssignees(Set<JobOrderAssignee> assignees) {
    if (assignees == null) {
      return null;
    }
    return assignees.stream()
        .map(this::toDto)
        .sorted(
            Comparator.comparing(
                a ->
                    (a.user() != null && a.user().effectiveName() != null)
                        ? a.user().effectiveName()
                        : "",
                String.CASE_INSENSITIVE_ORDER))
        .toList();
  }

  /**
   * Maps a set of {@link JobOrderMaterial} children into a sorted DTO list: SCU-typed materials
   * first, then alphabetical by material name (case-insensitive). The deterministic order keeps the
   * materials table stable across reloads.
   */
  default List<JobOrderMaterialDto> mapAndSortMaterials(Set<JobOrderMaterial> materials) {
    if (materials == null) {
      return null;
    }
    return materials.stream()
        .map(this::toDto)
        .sorted(
            Comparator.<JobOrderMaterialDto, Integer>comparing(
                    m ->
                        (m.material() != null
                                && "SCU".equalsIgnoreCase(m.material().quantityType()))
                            ? 0
                            : 1)
                .thenComparing(
                    m ->
                        (m.material() != null && m.material().name() != null)
                            ? m.material().name()
                            : "",
                    String.CASE_INSENSITIVE_ORDER))
        .toList();
  }
}
