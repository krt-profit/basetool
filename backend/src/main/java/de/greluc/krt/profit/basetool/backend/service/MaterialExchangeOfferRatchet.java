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

package de.greluc.krt.profit.basetool.backend.service;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditDetails;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.model.MaterialExchangeOfferKind;
import de.greluc.krt.profit.basetool.backend.repository.MaterialExchangeOfferRepository;
import de.greluc.krt.profit.basetool.backend.repository.MaterialExchangeOfferRepository.OfferStock;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps Materialbörse offers in step with the Lager rows they stand on, and audits every offer a
 * stock change lowers or removes (REQ-MARKET-013, REQ-AUDIT-001).
 *
 * <p>Every path that lowers a Lager row calls {@link #lower}; every path that deletes one calls a
 * {@code beforeDelete…} method before the delete, whose {@code ON DELETE CASCADE} then removes the
 * offers. Each method must run in the stock-changing transaction.
 */
@Service
@RequiredArgsConstructor
public class MaterialExchangeOfferRatchet {

  private final MaterialExchangeOfferRepository offerRepository;
  private final AuditRecorder auditRecorder;

  /**
   * Lowers the active offers on a Lager row to the row's reduced stock and records {@code
   * MARKET_OFFER_REDUCED} for each offer it lowered; a stock at or above an offer leaves it alone.
   *
   * @param inventoryItemId the reduced row
   * @param stock the row's new stock: SCU for a material offer, floored to whole pieces for an item
   *     offer
   * @param reason the stock change
   * @return the number of offers lowered
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public int lower(@NotNull UUID inventoryItemId, double stock, @NotNull Reason reason) {
    List<OfferStock> offers =
        offerRepository.findActiveStockByInventoryItemIds(List.of(inventoryItemId));
    if (offers.isEmpty()) {
      return 0;
    }
    int wholeUnits = (int) Math.floor(stock);
    int lowered = 0;
    for (OfferStock offer : offers) {
      if (offer.getOfferedAmount() != null && offer.getOfferedAmount() > stock) {
        reduced(offer, offer.getOfferedAmount(), stock, reason);
        lowered++;
      } else if (offer.getKind() == MaterialExchangeOfferKind.ITEM
          && offer.getItemQuantity() != null
          && offer.getItemQuantity() > wholeUnits) {
        reduced(offer, offer.getItemQuantity(), wholeUnits, reason);
        lowered++;
      }
    }
    offerRepository.clampOfferedAmountToStock(inventoryItemId, stock);
    offerRepository.clampItemQuantityToStock(inventoryItemId, wholeUnits);
    return lowered;
  }

  /**
   * Records {@code MARKET_OFFER_REMOVED} for every active offer on Lager rows about to be deleted.
   *
   * @param inventoryItemIds the rows to be deleted
   * @param reason the stock change
   * @return the number of offers the delete will remove
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public int beforeDelete(@NotNull Collection<UUID> inventoryItemIds, @NotNull Reason reason) {
    if (inventoryItemIds.isEmpty()) {
      return 0;
    }
    return removed(offerRepository.findActiveStockByInventoryItemIds(inventoryItemIds), reason);
  }

  /**
   * Records {@code MARKET_OFFER_REMOVED} for every active offer on the non-personal rows the global
   * wipe is about to delete in the given scope.
   *
   * @param scope the wipe's scope
   * @return the number of offers the wipe will remove
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public int beforeWipe(@NotNull ScopePredicate scope) {
    return removed(
        offerRepository.findActiveStockOnNonPersonalRows(
            scope.adminAllScope(), scope.activeOrgUnitId(), scope.memberOrgUnitIds()),
        Reason.WIPE);
  }

  /**
   * Records {@code MARKET_OFFER_REMOVED} for every active offer on the rows of a member whose
   * account is about to be deleted.
   *
   * @param userId the member
   * @return the number of offers the purge will remove
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public int beforeUserPurge(@NotNull UUID userId) {
    return removed(offerRepository.findActiveStockByRowOwner(userId), Reason.USER_DELETION);
  }

  /**
   * Records {@code MARKET_OFFER_REMOVED} for each offer.
   *
   * @param offers the offers
   * @param reason the stock change
   * @return the number of offers
   */
  private int removed(@NotNull List<OfferStock> offers, @NotNull Reason reason) {
    for (OfferStock offer : offers) {
      auditRecorder.record(
          AuditEventType.MARKET_OFFER_REMOVED,
          offer.getId(),
          label(offer),
          offer.getOwnerId(),
          AuditDetails.of("kind", offer.getKind()).with("reason", reason.getCode()));
    }
    return offers.size();
  }

  /**
   * Records {@code MARKET_OFFER_REDUCED} for one offer.
   *
   * @param offer the offer
   * @param from what it offered
   * @param to what it offers now
   * @param reason the stock change
   */
  private void reduced(
      @NotNull OfferStock offer, @NotNull Number from, @NotNull Number to, @NotNull Reason reason) {
    auditRecorder.record(
        AuditEventType.MARKET_OFFER_REDUCED,
        offer.getId(),
        label(offer),
        offer.getOwnerId(),
        AuditDetails.of("kind", offer.getKind())
            .with("from", from)
            .with("to", to)
            .with("reason", reason.getCode()));
  }

  /**
   * The audit label of an offer: its item name, else the material name of its row.
   *
   * @param offer the offer
   * @return the label
   */
  private static String label(@NotNull OfferStock offer) {
    return offer.getItemName() != null ? offer.getItemName() : offer.getMaterialName();
  }

  /**
   * The offers one stock change lowered and removed.
   *
   * @param reduced the offers lowered
   * @param removed the offers removed
   */
  public record Effects(int reduced, int removed) {

    /** A stock change that touched no offer. */
    public static final Effects NONE = new Effects(0, 0);
  }

  /** The stock change that lowered or removed an offer, written as the audit {@code reason}. */
  @Getter
  @RequiredArgsConstructor
  public enum Reason {

    /** A discard or sale booked out of the Lager. */
    CHECKOUT("checkout"),

    /** A selection of rows booked out at once. */
    BULK_CHECKOUT("bulk-checkout"),

    /** A transfer to another member or location. */
    TRANSFER("transfer"),

    /** A move across the personal marker, alone or as part of a bulk rebooking. */
    REBOOK("rebook"),

    /** The global wipe of the shared Lager. */
    WIPE("wipe"),

    /** A job-order material handover or item delivery. */
    HANDOVER("handover"),

    /** Materials consumed by booked job-order production. */
    PRODUCTION("production"),

    /** A connected application setting the member's stock. */
    STOCK("stock"),

    /** The deletion of the member's account. */
    USER_DELETION("user-deletion");

    /** The value written to the audit details. */
    private final String code;
  }
}
