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
import de.greluc.krt.profit.basetool.backend.support.StockViewerAccess;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.springframework.beans.factory.annotation.Autowired;

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
public abstract class JobOrderMapper {

  @Autowired protected StockViewerAccess stockAccess;

  /**
   * The same {@link UserMapper} the generated subclass maps each assignee's {@code UserDto} with,
   * injected under its own name so {@link #mapAndSortAssignees(Set)} can seed its request memo for
   * the whole Bearbeiter list first.
   */
  @Autowired protected UserMapper assigneeUserMapper;

  /**
   * Resolves the caller-dependent {@code canEdit} projection of one job order.
   *
   * @param jobOrder the order being mapped; {@code null} or id-less yields {@code false}.
   * @return whether the current caller may edit it — role <em>and</em> scope, as the endpoint
   *     gates.
   */
  protected boolean resolveCanEdit(JobOrder jobOrder) {
    return jobOrder != null
        && jobOrder.getId() != null
        && stockAccess.mayEditJobOrder(jobOrder.getId());
  }

  /**
   * Maps a {@link JobOrder} entity to its outbound DTO, projecting the responsible and requesting
   * org units (Staffel or Spezialkommando) into {@code SquadronReferenceDto} slots.
   *
   * @param jobOrder the entity to project; {@code null} returns {@code null}
   * @return the outbound DTO
   */
  @Mapping(target = "responsibleOrgUnit", source = "responsibleOrgUnit")
  @Mapping(target = "requestingOrgUnit", source = "requestingOrgUnit")
  @Mapping(target = "items", ignore = true)
  @Mapping(target = "aggregatedMaterials", ignore = true)
  @Mapping(target = "itemHandovers", ignore = true)
  @Mapping(target = "redacted", ignore = true)
  @Mapping(target = "canEdit", expression = "java(resolveCanEdit(jobOrder))")
  @Mapping(target = "withAssignees", ignore = true)
  @Mapping(target = "withRedacted", ignore = true)
  public abstract JobOrderDto toDto(JobOrder jobOrder);

  /**
   * Maps a {@link JobOrderMaterial} child to its DTO, leaving the service-owned {@code
   * currentStock}, {@code claims} and {@code openAmount} unmapped.
   */
  @Mapping(target = "currentStock", ignore = true)
  @Mapping(target = "claims", ignore = true)
  @Mapping(target = "openAmount", ignore = true)
  public abstract JobOrderMaterialDto toDto(JobOrderMaterial material);

  /**
   * Maps a {@link JobOrderAssignee} edge to its DTO, including the assigned user, note and version.
   *
   * @param assignee the assignee edge; {@code null} returns {@code null}
   * @return the assignee DTO
   */
  public abstract JobOrderAssigneeDto toDto(JobOrderAssignee assignee);

  /**
   * Seeds the {@link UserMapper} request memo for the assignees of a whole page of orders in one go
   * (REQ-DATA-003); call it before mapping the page.
   *
   * @param orders the orders about to be mapped; never {@code null}
   */
  public void primeAssignees(@NotNull Collection<JobOrder> orders) {
    assigneeUserMapper.primeStaffelMemberships(
        orders.stream()
            .filter(o -> o.getAssignees() != null)
            .flatMap(o -> o.getAssignees().stream())
            .map(JobOrderAssignee::getUser)
            .toList());
  }

  /**
   * Maps assignee edges to a DTO list sorted case-insensitively by the assignee's effective name.
   *
   * @param assignees the assignee edges; {@code null} returns {@code null}
   * @return the sorted assignee DTOs
   */
  @Nullable
  public List<JobOrderAssigneeDto> mapAndSortAssignees(Set<JobOrderAssignee> assignees) {
    if (assignees == null) {
      return null;
    }
    assigneeUserMapper.primeStaffelMemberships(
        assignees.stream().map(JobOrderAssignee::getUser).toList());
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
  @Nullable
  public List<JobOrderMaterialDto> mapAndSortMaterials(Set<JobOrderMaterial> materials) {
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
