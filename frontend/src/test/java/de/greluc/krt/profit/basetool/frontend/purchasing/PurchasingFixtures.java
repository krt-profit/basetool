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

package de.greluc.krt.profit.basetool.frontend.purchasing;

import de.greluc.krt.profit.basetool.frontend.kernel.model.PageResponse;
import de.greluc.krt.profit.basetool.frontend.purchasing.model.DealDto;
import de.greluc.krt.profit.basetool.frontend.purchasing.model.DealPartnerDto;
import de.greluc.krt.profit.basetool.frontend.purchasing.model.DelegateDto;
import de.greluc.krt.profit.basetool.frontend.purchasing.model.DeliveryDto;
import de.greluc.krt.profit.basetool.frontend.purchasing.model.PartnerDto;
import de.greluc.krt.profit.basetool.frontend.purchasing.model.PurchasingRefDto;
import de.greluc.krt.profit.basetool.frontend.purchasing.model.PurchasingStateDto;
import de.greluc.krt.profit.basetool.frontend.purchasing.model.PurchasingTierRefDto;
import de.greluc.krt.profit.basetool.frontend.purchasing.model.SettlementDto;
import de.greluc.krt.profit.basetool.frontend.purchasing.model.TrancheDto;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.Setter;
import lombok.experimental.Accessors;

/** Builders for the purchasing DTO records the controller and render tests share. */
public final class PurchasingFixtures {

  /** The creation instant every fixture carries; noon UTC keeps its date stable in any zone. */
  public static final Instant CREATED_AT = Instant.parse("2026-09-01T12:00:00Z");

  private PurchasingFixtures() {}

  /**
   * Builds the caller state of an enabled purchasing surface.
   *
   * @param delegate whether the caller is a Handelsbeauftragter
   * @param governing whether the caller belongs to the governing Bereich
   * @param managesUnit whether the caller manages a supply unit
   * @return the state
   */
  public static PurchasingStateDto state(boolean delegate, boolean governing, boolean managesUnit) {
    return new PurchasingStateDto(true, delegate, governing, false, managesUnit, false, false);
  }

  /**
   * Wraps a list into a first page that holds all of it.
   *
   * @param content the rows
   * @param <T> the row type
   * @return the page
   */
  public static <T> PageResponse<T> page(List<T> content) {
    return new PageResponse<>(content, 0, 10, content.size(), 1, List.of());
  }

  /**
   * Builds the partner snapshot embedded in a deal.
   *
   * @param conditionsNote the conditions note, or {@code null}
   * @param reputationNote the reputation note, or {@code null}
   * @return the snapshot
   */
  public static DealPartnerDto dealPartner(String conditionsNote, String reputationNote) {
    return new DealPartnerDto(
        UUID.randomUUID(), "ORG", "Orion Traders", "ORT", "ACTIVE", conditionsNote, reputationNote);
  }

  /**
   * Builds a tranche.
   *
   * @param kind the tranche kind
   * @param amount the amount in aUEC
   * @param state the tranche state
   * @return the tranche
   */
  public static TrancheDto tranche(String kind, long amount, String state) {
    return new TrancheDto(UUID.randomUUID(), kind, amount, state, null, null, CREATED_AT, 1L);
  }

  /**
   * Builds a delivery that is still waiting for its decision.
   *
   * @param id the delivery
   * @param canAccept whether the caller may decide it
   * @return the delivery
   */
  public static DeliveryDto recordedDelivery(UUID id, boolean canAccept) {
    return new DeliveryDto(
        id,
        5.0,
        new PurchasingRefDto(UUID.randomUUID(), "Lorville"),
        "RECORDED",
        null,
        null,
        null,
        null,
        null,
        CREATED_AT,
        canAccept,
        2L);
  }

  /**
   * Builds a delivery that was decided with a rejected part.
   *
   * @param id the delivery
   * @return the delivery
   */
  public static DeliveryDto decidedDelivery(UUID id) {
    return new DeliveryDto(
        id, 5.0, null, "ACCEPTED", 4.0, 1.0, "DAMAGED", 700, CREATED_AT, CREATED_AT, false, 3L);
  }

  /**
   * Builds a settlement.
   *
   * @param settledAt when the settlement completed, or {@code null} while it waits for the bank
   * @param remainderRequestId the bank request of the remainder, or {@code null}
   * @return the settlement
   */
  public static SettlementDto settlement(Instant settledAt, UUID remainderRequestId) {
    return new SettlementDto(
        12_500L, 11_000L, 1_500L, 500L, "FRAUD", remainderRequestId, settledAt, 2L);
  }

  /**
   * Builds a partner of the partner list.
   *
   * @param name the partner name
   * @param kind {@code ORG} or {@code INDIVIDUAL}
   * @param status {@code ACTIVE} or {@code BLOCKED}
   * @param erased whether the personal data was erased
   * @param conditionsNote the conditions note, or {@code null}
   * @param reputationNote the reputation note, or {@code null}
   * @return the partner
   */
  public static PartnerDto partner(
      String name,
      String kind,
      String status,
      boolean erased,
      String conditionsNote,
      String reputationNote) {
    return new PartnerDto(
        UUID.randomUUID(),
        kind,
        name,
        null,
        "DISCORD",
        conditionsNote,
        reputationNote,
        "DIPLOMACY",
        status,
        erased,
        4L,
        3L,
        5L);
  }

  /**
   * Builds an appointment of the delegate list.
   *
   * @param name the delegate's display name
   * @param monthlyLimit the personal limit, or {@code null} for the default
   * @param effectiveLimit the limit that applies
   * @param revokedAt when the appointment was revoked, or {@code null}
   * @return the appointment, active exactly while it is not revoked
   */
  public static DelegateDto delegate(
      String name, Long monthlyLimit, long effectiveLimit, Instant revokedAt) {
    return new DelegateDto(
        UUID.randomUUID(),
        UUID.randomUUID(),
        name,
        CREATED_AT,
        "Leitung",
        null,
        monthlyLimit,
        effectiveLimit,
        revokedAt,
        revokedAt == null,
        2L);
  }

  /**
   * Builds a tier reference with a quality floor.
   *
   * @param minQuality the floor
   * @return the reference
   */
  public static PurchasingTierRefDto tier(int minQuality) {
    return new PurchasingTierRefDto(UUID.randomUUID(), "HIGH", "Hoch", "High", minQuality);
  }

  /**
   * Starts a deal with a funded, mine, quiet default.
   *
   * @return the builder
   */
  public static DealBuilder deal() {
    return new DealBuilder();
  }

  /** Fluent builder of a {@link DealDto}; every setter overrides one default. */
  @Setter
  @Accessors(fluent = true, chain = true)
  public static final class DealBuilder {

    private UUID id = UUID.randomUUID();
    private long dealNo = 7L;
    private String status = "FUNDED";
    private boolean overdue;
    private boolean mine = true;
    private double amount = 10.0;
    private double acceptedAmount = 3.0;
    private double pendingAmount;
    private long unitPrice = 1_250L;
    private long total = 12_500L;
    private Double uexReferencePrice;
    private PurchasingTierRefDto qualityTier;
    private String paymentModel = "ADVANCE";
    private String riskClass = "A";
    private boolean escortWish;
    private Instant settlementDueAt;
    private long requestedAmount = 12_500L;
    private long fundedAmount = 5_000L;
    private DealPartnerDto partner = dealPartner(null, null);
    private List<TrancheDto> tranches = List.of();
    private List<DeliveryDto> deliveries = List.of();
    private SettlementDto settlement;
    private boolean canEdit;
    private boolean canRequestTranche;
    private boolean canRecordDelivery;
    private boolean canCloseDelivery;
    private boolean canSettle;
    private boolean canCancel;

    private DealBuilder() {}

    /**
     * Switches every {@code can*} flag at once.
     *
     * @param allowed whether the caller may do everything
     * @return this builder
     */
    public DealBuilder everything(boolean allowed) {
      return canEdit(allowed)
          .canRequestTranche(allowed)
          .canRecordDelivery(allowed)
          .canCloseDelivery(allowed)
          .canSettle(allowed)
          .canCancel(allowed);
    }

    /**
     * Builds the record.
     *
     * @return the deal
     */
    public DealDto build() {
      return new DealDto(
          id,
          dealNo,
          UUID.randomUUID(),
          new PurchasingRefDto(UUID.randomUUID(), "ARGON"),
          new PurchasingRefDto(UUID.randomUUID(), "Quantanium"),
          null,
          "SCU",
          qualityTier,
          amount,
          acceptedAmount,
          pendingAmount,
          unitPrice,
          total,
          uexReferencePrice,
          paymentModel,
          riskClass,
          escortWish,
          null,
          status,
          false,
          settlementDueAt,
          overdue,
          requestedAmount,
          fundedAmount,
          partner,
          "Tester",
          mine,
          tranches,
          deliveries,
          settlement,
          canEdit,
          canRequestTranche,
          canRecordDelivery,
          canCloseDelivery,
          canSettle,
          canCancel,
          3L,
          CREATED_AT);
    }
  }
}
