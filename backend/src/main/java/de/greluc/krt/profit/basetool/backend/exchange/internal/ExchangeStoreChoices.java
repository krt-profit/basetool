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

import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeOrgUnitDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeStoreChoiceJobOrdersDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeStoreChoiceReceiversDto;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderItemService;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderStatus;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderVisibility;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The store choices of a refinery pickup, decided from the database because an exchange request
 * carries no stored roles (REQ-XCH-053, REQ-XCH-054, REQ-SEC-039, ADR-0351): the member may book
 * onto themselves, and onto the direct members of every unit they are a Logistician of who do not
 * withhold their name; a line's unit is a direct membership of its receiver or one of those units;
 * a job order is active, in the member's read scope and needs the material.
 */
@Component
@RequiredArgsConstructor
public class ExchangeStoreChoices {

  private final ExchangeOrgUnitService orgUnitService;
  private final ExchangeOrgStockRoster roster;
  private final ExchangeHolderRefs holderRefs;
  private final JobOrderRepository jobOrderRepository;
  private final JobOrderItemService jobOrderItemService;

  /** The web's job-order read scope, evaluated for the acting member (REQ-ORG-003). */
  private final JobOrderVisibility jobOrderVisibility;

  /**
   * Decides whom the member may book onto and in which units.
   *
   * @param caller the client and member
   * @return the member's receivers by ref and the units they may book for others in
   */
  @Transactional(readOnly = true)
  public @NotNull Receivers receivers(@NotNull ExchangeCaller caller) {
    UUID self = caller.member();
    Set<UUID> units = orgUnitService.logisticianUnits(self);
    Set<UUID> members = new LinkedHashSet<>();
    members.add(self);
    members.addAll(roster.members(units));
    Function<UUID, String> refs = holderRefs.forClient(caller.clientId());
    Map<String, UUID> byRef = new HashMap<>();
    members.forEach(member -> byRef.put(refs.apply(member), member));
    return new Receivers(self, Set.copyOf(units), Map.copyOf(byRef));
  }

  /**
   * Lists the member's receivers with the name the web shows and their direct memberships.
   *
   * @param caller the client and member
   * @return the member first, then the others ordered by name and then by ref
   */
  @Transactional(readOnly = true)
  public @NotNull ExchangeStoreChoiceReceiversDto receiverList(@NotNull ExchangeCaller caller) {
    Receivers receivers = receivers(caller);
    Collection<UUID> members = receivers.byRef().values();
    Map<UUID, List<ExchangeOrgUnitDto>> memberships = orgUnitService.orgUnitsOf(members);
    List<ExchangeStoreChoiceReceiversDto.Receiver> items =
        roster.holders(caller.clientId(), members).stream()
            .sorted(Comparator.comparing(holder -> !holder.userId().equals(receivers.self())))
            .map(holder ->
                new ExchangeStoreChoiceReceiversDto.Receiver(
                    holder.ref(),
                    holder.name(),
                    holder.userId().equals(receivers.self()),
                    memberships.getOrDefault(holder.userId(), List.of())))
            .toList();
    return new ExchangeStoreChoiceReceiversDto(items);
  }

  /**
   * Lists the active job orders the member sees that need a material, as the web's active-order
   * lookup filters the store dialog's picker.
   *
   * @param material the material
   * @return the job orders; empty for a material that names nothing
   */
  @Transactional(readOnly = true)
  public @NotNull ExchangeStoreChoiceJobOrdersDto jobOrders(@NotNull UUID material) {
    return new ExchangeStoreChoiceJobOrdersDto(
        jobOrderRepository.findAllActiveWithMaterials().stream()
            .filter(order -> usable(order, material))
            .map(order ->
                new ExchangeStoreChoiceJobOrdersDto.JobOrder(
                    order.getId(), order.getDisplayId(), order.getStatus().name()))
            .toList());
  }

  /**
   * Whether a store line may earmark its material to a job order.
   *
   * @param jobOrder the job order
   * @param material the line's material
   * @return {@code true} when the order exists, is open or in progress, lies in the member's read
   *     scope and needs the material
   */
  @Transactional(readOnly = true)
  public boolean jobOrderAdmits(@NotNull UUID jobOrder, @NotNull UUID material) {
    return jobOrderRepository
        .findById(jobOrder)
        .filter(order -> usable(order, material))
        .isPresent();
  }

  /**
   * Whether a job order is offered for a material.
   *
   * @param order the job order
   * @param material the material
   * @return {@code true} when it is active, visible to the member and needs the material
   */
  private boolean usable(@NotNull JobOrder order, @NotNull UUID material) {
    return (order.getStatus() == JobOrderStatus.OPEN
            || order.getStatus() == JobOrderStatus.IN_PROGRESS)
        && jobOrderVisibility.canSeeJobOrderInScope(order)
        && jobOrderItemService.requiredMaterialIds(order).contains(material);
  }

  /**
   * Whom the member may book onto, as one change set sees it.
   *
   * @param self the member
   * @param units the units the member is a Logistician of, in which they may book for others
   * @param byRef every receiver by the ref the client names it with
   */
  public record Receivers(
      @NotNull UUID self,
      @NotNull @Unmodifiable Set<UUID> units,
      @NotNull @Unmodifiable Map<String, UUID> byRef) {

    /**
     * Resolves a line's receiver.
     *
     * @param ref the line's receiver ref, or {@code null} for the member
     * @return the receiver, or {@code null} when the ref names nobody the member may book onto
     */
    public @Nullable UUID resolve(@Nullable String ref) {
      return ref == null ? self : byRef.get(ref);
    }
  }
}
