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

package de.greluc.krt.profit.basetool.backend.inventory.internal;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditDetails;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QuantityType;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.inventory.api.InventoryItemDto;
import de.greluc.krt.profit.basetool.backend.inventory.api.OverAllocationException;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockOfferLookup;
import de.greluc.krt.profit.basetool.backend.inventory.api.events.InventoryNotices;
import de.greluc.krt.profit.basetool.backend.inventory.api.events.TransferredLot;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.BusinessConflictException;
import de.greluc.krt.profit.basetool.backend.kernel.Entities;
import de.greluc.krt.profit.basetool.backend.kernel.OptimisticLock;
import de.greluc.krt.profit.basetool.backend.kernel.ScuAmounts;
import de.greluc.krt.profit.basetool.backend.kernel.StringNormalization;
import de.greluc.krt.profit.basetool.backend.notification.api.events.ActorRef;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipQueryService;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitReferenceDto;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.scope.api.OwnerScopeService;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Moves a shared Lager row, or a part of it, from its pool into the pool of another org unit its
 * holder directly belongs to (REQ-INV-059). The whole row keeps its earmarks and Materialbörse
 * offers, and one with a donation pledge is not moved in full; a part is split off from the free
 * stock as a new row. The holder is told when somebody else moved it, and the result merges by the
 * write-time rules (REQ-INV-026).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InventoryPoolChangeService {

  /** The contextual role a pool change asks of the caller in both units. */
  static final String LOGISTICIAN = "LOGISTICIAN";

  /** Amounts closer than this count as equal. */
  private static final double QUANTITY_EPSILON = 1e-9;

  /** Name shown in a notification for a member or unit without one. */
  private static final String UNKNOWN_NAME = "—";

  private final InventoryItemRepository inventoryItemRepository;
  private final OrgUnitRepository orgUnitRepository;
  private final OrgUnitMembershipQueryService membershipQueryService;
  private final OwnerScopeService ownerScopeService;
  private final StockOfferLookup stockOfferLookup;
  private final InventoryCheckoutService inventoryCheckoutService;
  private final InventoryItemMapper inventoryItemMapper;
  private final UserRepository userRepository;
  private final AuditRecorder auditRecorder;
  private final ApplicationEventPublisher eventPublisher;

  /**
   * Moves a shared row, or a part of it, into another pool for a caller who is a Logistician of
   * both units. Naming the row's own pool changes nothing and records nothing.
   *
   * @param itemId the row
   * @param dto the version, the target unit, the part and the merge opt-in
   * @param callerId the authenticated caller, recorded as the actor
   * @return the row that holds the moved stock afterwards, merged or not
   * @throws de.greluc.krt.profit.basetool.backend.kernel.NotFoundException when the row or the unit
   *     is unknown
   * @throws AccessDeniedException when the caller is not a Logistician of both units
   * @throws BadRequestException when the row is personal, the holder is not a direct member of the
   *     target unit, or the part is not positive, exceeds the row or is fractional on whole-unit
   *     stock
   * @throws BusinessConflictException when the part would leave the row below its Materialbörse
   *     offer
   * @throws OverAllocationException when the rest could no longer carry the row's earmarks
   * @throws org.springframework.orm.ObjectOptimisticLockingFailureException when the version is
   *     stale
   */
  @Transactional
  public @NotNull InventoryItemDto changePool(
      @NotNull UUID itemId, @NotNull InventoryItemPoolChangeDto dto, @NotNull UUID callerId) {
    InventoryItem item =
        Entities.require(
            inventoryItemRepository.findByIdForRebook(itemId),
            () -> "Inventory item not found: " + itemId);
    OptimisticLock.checkOptionalClient(
        item.getVersion(), dto.version(), InventoryItem.class, itemId);
    OrgUnit source = requireSharedRow(item);
    if (!ownerScopeService.hasRoleInOrgUnit(source.getId(), LOGISTICIAN)
        || !ownerScopeService.hasRoleInOrgUnit(dto.targetOwningOrgUnitId(), LOGISTICIAN)) {
      throw new AccessDeniedException(
          "Only a Logistician of both units may move stock between them");
    }
    return inventoryItemMapper.toDto(
        movePool(
            item,
            dto.targetOwningOrgUnitId(),
            dto.amount(),
            Boolean.TRUE.equals(dto.mergeStock()),
            callerId));
  }

  /**
   * Lists the pools a caller may move a shared row into: the units its holder directly belongs to,
   * other than the row's own, in which the caller is a Logistician, ordered by name.
   *
   * @param itemId the row
   * @return the units, empty for a personal row or when the caller is no Logistician of its pool
   * @throws de.greluc.krt.profit.basetool.backend.kernel.NotFoundException when the row is unknown
   */
  public @NotNull List<OrgUnitReferenceDto> poolTargets(@NotNull UUID itemId) {
    InventoryItem item =
        Entities.require(
            inventoryItemRepository.findById(itemId), () -> "Inventory item not found: " + itemId);
    if (Boolean.TRUE.equals(item.getPersonal())
        || item.getOwningOrgUnit() == null
        || !ownerScopeService.hasRoleInOrgUnit(item.getOwningOrgUnit().getId(), LOGISTICIAN)) {
      return List.of();
    }
    UUID source = item.getOwningOrgUnit().getId();
    List<UUID> candidates =
        membershipQueryService.findDirectMembershipOrgUnitIds(item.getUser().getId()).stream()
            .filter(id -> !id.equals(source))
            .filter(id -> ownerScopeService.hasRoleInOrgUnit(id, LOGISTICIAN))
            .toList();
    return orgUnitRepository.findAllById(candidates).stream()
        .map(
            unit ->
                new OrgUnitReferenceDto(
                    unit.getId(), unit.getName(), unit.getShorthand(), unit.getKind()))
        .sorted(
            Comparator.comparing(
                OrgUnitReferenceDto::name, Comparator.nullsLast(String::compareTo)))
        .toList();
  }

  /**
   * Moves a locked shared row, or a part of it, into another pool of its holder, for a caller the
   * caller already checked; joins the caller's transaction.
   *
   * @param item the locked shared row
   * @param targetId the org unit whose pool receives the stock
   * @param amount the part to move, or {@code null} for the whole row
   * @param mergeStock the merge opt-in of an SCU material (REQ-INV-026)
   * @param actorId the member making the change
   * @return the row that holds the moved stock afterwards, the row itself when the target is its
   *     own pool
   * @throws de.greluc.krt.profit.basetool.backend.kernel.NotFoundException when the unit is unknown
   * @throws BadRequestException when the holder is not a direct member of the target unit, or the
   *     part is invalid
   * @throws BusinessConflictException when the part would leave the row below its Materialbörse
   *     offer
   * @throws OverAllocationException when the rest could no longer carry the row's earmarks
   */
  @Transactional(propagation = Propagation.MANDATORY)
  @NotNull
  InventoryItem movePool(
      @NotNull InventoryItem item,
      @NotNull UUID targetId,
      @Nullable Double amount,
      boolean mergeStock,
      @NotNull UUID actorId) {
    OrgUnit source = requireSharedRow(item);
    if (source.getId().equals(targetId)) {
      return item;
    }
    UUID holderId = item.getUser().getId();
    if (!membershipQueryService.findDirectMembershipOrgUnitIds(holderId).contains(targetId)) {
      throw new BadRequestException("The holder is not a direct member of the target org unit");
    }
    OrgUnit target = Entities.require(orgUnitRepository.findById(targetId), "Org unit not found");
    double moved = amount == null ? amountOf(item) : amount;
    InventoryItem result = moveInto(item, orgUnitRef(source), target, moved);
    if (!holderId.equals(actorId)) {
      eventPublisher.publishEvent(
          InventoryNotices.poolChangedByOther(
              holderId,
              item.getId(),
              new ActorRef(actorId, actorName(actorId)),
              unitName(source),
              unitName(target),
              new TransferredLot(
                  catalogName(item),
                  moved,
                  requiresWholeUnits(item),
                  item.getQuality(),
                  item.getLocation() != null ? item.getLocation().getName() : null)));
    }
    return inventoryCheckoutService.mergeStockIfRequested(result, mergeStock);
  }

  /**
   * Moves a locked shared row without an org unit, or a part of it, into the pool of the named unit
   * for a lot rebooking that already decided the amount (REQ-INV-060); joins the caller's
   * transaction and audits the move as {@code INVENTORY_POOL_CHANGED} from {@code none}.
   *
   * @param item the locked shared row without an org unit
   * @param amount the part to move, at most the row's movable stock
   * @param target the org unit whose pool receives the stock
   * @return the row that holds the moved stock afterwards, merged or not
   * @throws BadRequestException when the row is personal or in a pool, or the part is invalid
   * @throws BusinessConflictException when the part would leave the row below its Materialbörse
   *     offer
   * @throws OverAllocationException when the rest could no longer carry the row's earmarks
   */
  @Transactional(propagation = Propagation.MANDATORY)
  @NotNull
  InventoryItem moveUnitlessRowIntoPool(
      @NotNull InventoryItem item, double amount, @NotNull OrgUnit target) {
    if (Boolean.TRUE.equals(item.getPersonal()) || item.getOwningOrgUnit() != null) {
      throw new BadRequestException("Only a shared row without an org unit can move this way");
    }
    return inventoryCheckoutService.mergeStockIfRequested(
        moveInto(item, "none", target, amount), false);
  }

  /**
   * Moves the row, or a part of it, into the target's pool and audits it as {@code
   * INVENTORY_POOL_CHANGED}.
   *
   * @param item the locked shared row
   * @param from the audited source pool, {@code kind:id} or {@code none}
   * @param target the receiving unit
   * @param amount the part to move
   * @return the row that holds the moved stock, before any merge
   */
  private @NotNull InventoryItem moveInto(
      @NotNull InventoryItem item, @NotNull String from, @NotNull OrgUnit target, double amount) {
    boolean whole = Math.abs(amount - amountOf(item)) <= QUANTITY_EPSILON;
    InventoryItem result = whole ? moveWholeRow(item, target) : splitOff(item, amount, target);
    auditRecorder.record(
        AuditEventType.INVENTORY_POOL_CHANGED,
        item.getId(),
        InventoryAuditLabels.label(item),
        item.getUser().getId(),
        AuditDetails.of("fromOrgUnit", from)
            .with("toOrgUnit", orgUnitRef(target))
            .with("amount", amount)
            .with("split", !whole)
            .with("newRow", whole ? "-" : result.getId()));
    return result;
  }

  /**
   * Returns a row's amount.
   *
   * @param item the row
   * @return the amount, {@code 0} when unset
   */
  private static double amountOf(@NotNull InventoryItem item) {
    return item.getAmount() != null ? item.getAmount() : 0.0;
  }

  /**
   * Moves the whole row with its job-order and mission earmarks and its offers; a row that carries
   * a donation pledge is not moved in full (REQ-INV-058).
   *
   * @param item the locked row
   * @param target the receiving unit
   * @return the row
   */
  private @NotNull InventoryItem moveWholeRow(
      @NotNull InventoryItem item, @NotNull OrgUnit target) {
    InventoryDonationGuards.requireNoDonation(item);
    item.setOwningOrgUnit(target);
    return inventoryItemRepository.saveAndFlush(item);
  }

  /**
   * Splits a part off the row's free stock as a new row of the receiving pool; the source keeps the
   * rest, its earmarks, its pledges and its offer.
   *
   * @param item the locked source row
   * @param amount the part
   * @param target the receiving unit
   * @return the new row
   * @throws BadRequestException when the part is not positive, exceeds the row or is fractional on
   *     whole-unit stock
   * @throws BusinessConflictException when the rest would fall below the row's offer
   * @throws OverAllocationException when the rest could no longer carry the row's earmarks
   */
  private @NotNull InventoryItem splitOff(
      @NotNull InventoryItem item, double amount, @NotNull OrgUnit target) {
    double held = item.getAmount() != null ? item.getAmount() : 0.0;
    if (amount <= 0) {
      throw new BadRequestException("error.inventory.poolChange.amountPositive");
    }
    if (amount > held) {
      throw new BadRequestException("error.inventory.poolChange.amountTooLarge");
    }
    if (requiresWholeUnits(item) && amount % 1 != 0) {
      throw new BadRequestException("error.inventory.poolChange.wholeUnits");
    }
    double remaining = ScuAmounts.roundToScuScale(held - amount);
    if (remaining + QUANTITY_EPSILON < stockOfferLookup.activeOfferedAmount(item.getId())) {
      throw new BusinessConflictException("error.inventory.poolChange.belowOffer");
    }
    InventoryItem part = new InventoryItem();
    part.setUser(item.getUser());
    part.setOwningOrgUnit(target);
    part.setMaterial(item.getMaterial());
    part.setGameItem(item.getGameItem());
    part.setLocation(item.getLocation());
    part.setQuality(item.getQuality());
    part.setAmount(ScuAmounts.roundToScuScale(amount));
    part.setPersonal(false);
    part.setStolen(item.getStolen());
    final InventoryItem savedPart = inventoryItemRepository.save(part);
    item.setAmount(remaining);
    if (!InventoryAllocations.fits(item)) {
      throw new OverAllocationException();
    }
    inventoryItemRepository.saveAndFlush(item);
    return savedPart;
  }

  /**
   * Returns the row's pool, refusing a personal row.
   *
   * @param item the row
   * @return the unit whose pool holds it
   * @throws BadRequestException when the row is personal or belongs to no unit
   */
  private static @NotNull OrgUnit requireSharedRow(@NotNull InventoryItem item) {
    if (Boolean.TRUE.equals(item.getPersonal()) || item.getOwningOrgUnit() == null) {
      throw new BadRequestException("Only a shared row of a pool can move into another pool");
    }
    return item.getOwningOrgUnit();
  }

  /**
   * Resolves the actor's effective name for the notification.
   *
   * @param actorId the member making the change
   * @return the name, or an em dash when the member is unknown or has none
   */
  private @NotNull String actorName(@NotNull UUID actorId) {
    return userRepository
        .findById(actorId)
        .map(user -> StringNormalization.trimToNull(user.getEffectiveName()))
        .orElse(UNKNOWN_NAME);
  }

  /**
   * Returns a unit's display name for the notification.
   *
   * @param unit the unit
   * @return its name, or an em dash when it has none
   */
  private static @NotNull String unitName(@NotNull OrgUnit unit) {
    return Objects.requireNonNullElse(StringNormalization.trimToNull(unit.getName()), UNKNOWN_NAME);
  }

  /**
   * Formats an org unit for an audit payload as {@code kind:id}.
   *
   * @param unit the unit
   * @return the bounded reference
   */
  private static @NotNull String orgUnitRef(@NotNull OrgUnit unit) {
    return unit.getKind() + ":" + unit.getId();
  }

  /**
   * Returns a row's catalogue name for the notification.
   *
   * @param item the row
   * @return the material or item name, or an em dash
   */
  private static @NotNull String catalogName(@NotNull InventoryItem item) {
    if (item.getMaterial() != null) {
      return item.getMaterial().getName();
    }
    return item.getGameItem() != null ? item.getGameItem().getName() : UNKNOWN_NAME;
  }

  /**
   * Whether amounts on this row must be whole numbers: game-item rows and {@code PIECE} materials.
   *
   * @param item the row
   * @return {@code true} for whole-unit stock
   */
  private static boolean requiresWholeUnits(@NotNull InventoryItem item) {
    return item.getGameItem() != null
        || (item.getMaterial() != null
            && item.getMaterial().getQuantityType() == QuantityType.PIECE);
  }
}
