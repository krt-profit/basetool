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

package de.greluc.krt.profit.basetool.backend.exchange.internal;

import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeOrgStockTargetsDto;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderItemService;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.kernel.OrgUnitKind;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import de.greluc.krt.profit.basetool.backend.scope.api.OwnerScopeService;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * Lists the job orders and missions the web's „+ Zuordnen" picker offers for an entry of one unit's
 * pool, as a Logistician sees them with that unit as the active one (REQ-XCH-045, REQ-INV-027).
 *
 * <p>Job orders are the open and in-progress ones that need the material or item and whose
 * responsible unit is none, a Spezialkommando or the pool's unit, and only when the member passes
 * the web's job-order gate; missions are those the Lager's mission picker lists for the unit, and
 * none for an item.
 */
@Component
@RequiredArgsConstructor
public class ExchangeOrgStockTargets {

  /** How far back the picker still lists completed and cancelled missions, in months. */
  private static final int TERMINAL_MISSION_MONTHS = 3;

  /** The mission picker's admin-all scope, which the exchange never grants. */
  private static final boolean NO_ADMIN_SCOPE = false;

  /**
   * Whether the mission picker shows internal missions without a unit: a Logistician of a unit is a
   * member, whom the web shows them.
   */
  private static final boolean VIEWER_IS_MEMBER = true;

  private final MaterialRepository materialRepository;
  private final GameItemRepository gameItemRepository;
  private final JobOrderRepository jobOrderRepository;
  private final JobOrderItemService jobOrderItemService;
  private final MissionRepository missionRepository;

  /** The web's job-order gate, evaluated for the acting member. */
  private final OwnerScopeService ownerScopeService;

  private final Clock clock = Clock.systemUTC();

  /**
   * Lists the targets of a material or item for a unit's pool.
   *
   * @param unit the unit whose pool holds the entries
   * @param catalogueId the material or item, as the org stock's lots name it
   * @return the job orders and missions; both empty for an id that names neither
   */
  public @NotNull Targets of(@NotNull UUID unit, @NotNull UUID catalogueId) {
    boolean material = materialRepository.existsById(catalogueId);
    if (!material && !gameItemRepository.existsById(catalogueId)) {
      return new Targets(List.of(), List.of());
    }
    return new Targets(
        jobOrders(unit, catalogueId, material), material ? missions(unit) : List.of());
  }

  /**
   * Lists the open and in-progress job orders the unit sees that need the material or item.
   *
   * @param unit the unit
   * @param catalogueId the material or item
   * @param material whether {@code catalogueId} is a material
   * @return the job orders in the picker's order
   */
  private @NotNull List<ExchangeOrgStockTargetsDto.JobOrder> jobOrders(
      @NotNull UUID unit, @NotNull UUID catalogueId, boolean material) {
    if (!ownerScopeService.canViewJobOrders()) {
      return List.of();
    }
    return jobOrderRepository.findAllActiveWithMaterials().stream()
        .filter(order -> visibleIn(order, unit))
        .filter(
            order ->
                (material
                        ? jobOrderItemService.requiredMaterialIds(order)
                        : jobOrderItemService.requiredGameItemIds(order))
                    .contains(catalogueId))
        .map(
            order ->
                new ExchangeOrgStockTargetsDto.JobOrder(
                    order.getId(), order.getDisplayId(), order.getStatus().name()))
        .toList();
  }

  /**
   * Whether a job order is visible with the unit as the active one: it has no responsible unit, a
   * Spezialkommando is responsible, or the unit itself is.
   *
   * @param order the job order
   * @param unit the unit
   * @return {@code true} when the web's picker lists it for the unit
   */
  private static boolean visibleIn(@NotNull JobOrder order, UUID unit) {
    OrgUnit responsible = order.getResponsibleOrgUnit();
    return responsible == null
        || responsible.getKind() == OrgUnitKind.SPECIAL_COMMAND
        || unit.equals(responsible.getId());
  }

  /**
   * Lists the missions the Lager's mission picker offers with the unit as the active one.
   *
   * @param unit the unit
   * @return the missions, newest planned start first
   */
  private @NotNull List<ExchangeOrgStockTargetsDto.Mission> missions(@NotNull UUID unit) {
    return missionRepository
        .findAllActiveReference(
            NO_ADMIN_SCOPE,
            unit,
            Set.of(),
            VIEWER_IS_MEMBER,
            OffsetDateTime.now(clock).minusMonths(TERMINAL_MISSION_MONTHS).toInstant())
        .stream()
        .map(
            mission ->
                new ExchangeOrgStockTargetsDto.Mission(
                    mission.id(), mission.name(), mission.status()))
        .toList();
  }

  /**
   * The targets of one material or item.
   *
   * @param jobOrders the job orders
   * @param missions the missions
   */
  public record Targets(
      @NotNull List<ExchangeOrgStockTargetsDto.JobOrder> jobOrders,
      @NotNull List<ExchangeOrgStockTargetsDto.Mission> missions) {}
}
