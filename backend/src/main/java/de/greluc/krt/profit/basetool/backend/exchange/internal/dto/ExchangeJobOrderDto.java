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

package de.greluc.krt.profit.basetool.backend.exchange.internal.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderRelation;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderStatus;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.Builder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * One job order of the member's read set as a client reads it (REQ-OXCH-002): the header every kind
 * carries, the member's own part and the progress of its kind; the fields of another kind are left
 * out. No holder, amount per person, provision, fee, movement, recipient, commitment, claim, Bank
 * state, history or other member's note is ever carried.
 *
 * @param id the order's id, the feed key
 * @param number the order's display number
 * @param kind the order's kind
 * @param status the lifecycle status
 * @param priority the queue position, {@code null} while the order is terminal
 * @param requestingUnit the requesting org unit
 * @param responsibleUnit the responsible org unit
 * @param supportingUnits the actively supporting org units
 * @param createdAt when the order was created
 * @param updatedAt the last change of a carried field: the instant of the order's fingerprint
 * @param relations the member's current own relations to the order, in a fixed order
 * @param comment the author's comment, or {@code null}
 * @param contact the author's contact handle on MATERIAL and ITEM orders; left out on aUEC orders
 *     and when the order names none
 * @param responsibilityInvalid whether the responsible unit lost its valid Profit responsibility
 * @param link the absolute {@code https} URL of the order's page in the Basetool
 * @param assignees the assignees who do not withhold their name
 * @param versions the section versions a client write echoes
 * @param materials a MATERIAL order's progress per material and quality floor; left out otherwise
 * @param items an ITEM order's progress per item line; left out otherwise
 * @param target an aUEC order's target in whole aUEC; left out otherwise
 * @param netHandedOver an aUEC order's net handover to the requester in whole aUEC; left out
 *     otherwise
 * @param remaining an aUEC order's target less the net handover, never negative; left out otherwise
 * @param overpaid an aUEC order's net handover beyond the target, never negative; left out
 *     otherwise
 * @param settlementOpen whether a terminal aUEC order still holds purpose-bound funds or an
 *     overpayment; left out on MATERIAL and ITEM orders
 */
@Builder
public record ExchangeJobOrderDto(
    @NotNull UUID id,
    int number,
    @NotNull JobOrderType kind,
    @NotNull JobOrderStatus status,
    @Nullable Integer priority,
    @NotNull ExchangeOrgUnitDto requestingUnit,
    @NotNull ExchangeOrgUnitDto responsibleUnit,
    @NotNull @Unmodifiable List<ExchangeOrgUnitDto> supportingUnits,
    @NotNull Instant createdAt,
    @NotNull Instant updatedAt,
    @NotNull @Unmodifiable List<JobOrderRelation> relations,
    @Nullable String comment,
    @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable String contact,
    boolean responsibilityInvalid,
    @NotNull String link,
    @NotNull @Unmodifiable List<Assignee> assignees,
    @NotNull Versions versions,
    @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable @Unmodifiable List<MaterialLine> materials,
    @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable @Unmodifiable List<ItemLine> items,
    @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable Long target,
    @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable Long netHandedOver,
    @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable Long remaining,
    @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable Long overpaid,
    @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable Boolean settlementOpen) {

  /**
   * An assignee of the order under the org-stock naming rules (REQ-XCH-043).
   *
   * @param name the name the Basetool shows
   * @param ref the per-client pseudonym of the assignee
   * @param own whether the entry is the acting member's own
   * @param note the acting member's own note on their own entry, {@code null} on every other entry
   *     and when they wrote none
   */
  @Schema(name = "ExchangeJobOrderAssignee")
  public record Assignee(
      @NotNull String name, @NotNull String ref, boolean own, @Nullable String note) {}

  /**
   * The section versions a client write echoes; the aUEC contact's information version is never
   * carried.
   *
   * @param lifecycle the lifecycle version
   * @param target the aUEC target version, {@code null} on MATERIAL and ITEM orders
   * @param comment the comment version
   */
  @Schema(name = "ExchangeJobOrderVersions")
  public record Versions(long lifecycle, @Nullable Long target, long comment) {}

  /**
   * One material and quality floor of a MATERIAL order, in the material's own unit.
   *
   * @param material the material, with its id as {@code bt}
   * @param minQuality the lowest quality that satisfies the line
   * @param unit {@code SCU} or {@code PIECE}
   * @param requested the requested amount
   * @param handedOver the amount handed over
   * @param remaining the requested amount less the amount handed over, never negative
   * @param open the remaining amount less linked stock and claims, never negative
   */
  @Schema(name = "ExchangeJobOrderMaterialLine")
  public record MaterialLine(
      @NotNull ExchangeItemRefDto material,
      int minQuality,
      @NotNull String unit,
      @NotNull BigDecimal requested,
      @NotNull BigDecimal handedOver,
      @NotNull BigDecimal remaining,
      @NotNull BigDecimal open) {}

  /**
   * One item line of an ITEM order, in whole pieces.
   *
   * @param id the item line
   * @param item the ordered item, with its id as {@code bt}
   * @param ordered the ordered pieces
   * @param manufactured the manufactured pieces
   * @param delivered the delivered pieces
   * @param open the ordered pieces not yet delivered, never negative
   */
  @Schema(name = "ExchangeJobOrderItemLine")
  public record ItemLine(
      @NotNull UUID id,
      @NotNull ExchangeItemRefDto item,
      int ordered,
      int manufactured,
      int delivered,
      int open) {}
}
