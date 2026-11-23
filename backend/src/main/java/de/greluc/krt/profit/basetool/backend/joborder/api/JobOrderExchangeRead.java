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

package de.greluc.krt.profit.basetool.backend.joborder.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import lombok.Builder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * The member-independent part of a job order's exchange read: what every client reader of the order
 * is served alike, and the whole input of the order's exchange fingerprint (REQ-OXCH-003,
 * ADR-0406). Units and assignees are carried by id; names, the member's relations and own note are
 * the member's part of the read. Lists are held in a canonical order, so two reads of one state are
 * equal.
 *
 * @param id the order's id, the key of the read
 * @param number the display number, a display field only
 * @param kind the order's kind
 * @param status the lifecycle status
 * @param priority the queue position, {@code null} while the order is terminal
 * @param createdAt when the order was created
 * @param requestingUnitId the requesting org unit
 * @param responsibleUnitId the responsible org unit
 * @param supportingUnitIds the actively supporting org units, ascending
 * @param comment the author's comment, or {@code null}
 * @param contact the author's contact handle on MATERIAL and ITEM orders, always {@code null} on
 *     aUEC orders
 * @param responsibilityInvalid whether the responsible unit lost its valid Profit responsibility
 *     (REQ-ORDERS-055)
 * @param versions the section versions a client write echoes
 * @param assigneeIds the members holding an assignee entry, ascending
 * @param progress the progress of the order's kind
 */
@Builder(toBuilder = true)
public record JobOrderExchangeRead(
    @NotNull UUID id,
    int number,
    @NotNull JobOrderType kind,
    @NotNull JobOrderStatus status,
    @Nullable Integer priority,
    @NotNull Instant createdAt,
    @NotNull UUID requestingUnitId,
    @NotNull UUID responsibleUnitId,
    @NotNull @Unmodifiable List<UUID> supportingUnitIds,
    @Nullable String comment,
    @Nullable String contact,
    boolean responsibilityInvalid,
    @NotNull Versions versions,
    @NotNull @Unmodifiable List<UUID> assigneeIds,
    @NotNull Progress progress) {

  /** Holds both id lists distinct and ascending. */
  public JobOrderExchangeRead {
    supportingUnitIds = supportingUnitIds.stream().distinct().sorted().toList();
    assigneeIds = assigneeIds.stream().distinct().sorted().toList();
  }

  /**
   * The section versions a client write echoes; the information version of the aUEC contact is
   * never carried.
   *
   * @param lifecycle the lifecycle version
   * @param target the aUEC target version, {@code null} on MATERIAL and ITEM orders
   * @param comment the comment version
   */
  public record Versions(long lifecycle, @Nullable Long target, long comment) {}

  /** The progress figures of one order kind. */
  public sealed interface Progress permits MaterialProgress, ItemProgress, AuecProgress {}

  /**
   * The progress of a MATERIAL order, one line per material and quality tier.
   *
   * @param lines the lines, ascending by material and then quality tier
   */
  public record MaterialProgress(@NotNull @Unmodifiable List<MaterialLine> lines)
      implements Progress {

    /** Holds the lines in their canonical order. */
    public MaterialProgress {
      lines =
          lines.stream()
              .sorted(
                  Comparator.comparing(MaterialLine::materialId)
                      .thenComparing(MaterialLine::qualityTierId))
              .toList();
    }
  }

  /**
   * One material and quality tier of a MATERIAL order, in the material's own unit rounded to three
   * decimals.
   *
   * @param materialId the material
   * @param qualityTierId the quality tier
   * @param requested the requested amount
   * @param handedOver the amount handed over
   * @param remaining the requested amount less the amount handed over, never negative
   * @param open the remaining amount less linked stock and claims, never negative
   */
  public record MaterialLine(
      @NotNull UUID materialId,
      @NotNull UUID qualityTierId,
      double requested,
      double handedOver,
      double remaining,
      double open) {}

  /**
   * The progress of an ITEM order, one line per item line.
   *
   * @param lines the lines, ascending by line id
   */
  public record ItemProgress(@NotNull @Unmodifiable List<ItemLine> lines) implements Progress {

    /** Holds the lines in their canonical order. */
    public ItemProgress {
      lines = lines.stream().sorted(Comparator.comparing(ItemLine::lineId)).toList();
    }
  }

  /**
   * One item line of an ITEM order, in whole units.
   *
   * @param lineId the item line
   * @param gameItemId the ordered item
   * @param ordered the ordered units
   * @param manufactured the manufactured units
   * @param delivered the delivered units
   * @param open the ordered units not yet delivered, never negative
   */
  public record ItemLine(
      @NotNull UUID lineId,
      @NotNull UUID gameItemId,
      int ordered,
      int manufactured,
      int delivered,
      int open) {}

  /**
   * The progress of an aUEC order, in exact whole aUEC.
   *
   * @param target the target
   * @param netHandedOver the net amount handed over to the requester
   * @param remaining the target less the net handover, never negative
   * @param overpaid the net handover beyond the target, never negative
   * @param settlementOpen whether the terminal order still holds purpose-bound funds or an
   *     overpayment
   */
  public record AuecProgress(
      @NotNull BigDecimal target,
      @NotNull BigDecimal netHandedOver,
      @NotNull BigDecimal remaining,
      @NotNull BigDecimal overpaid,
      boolean settlementOpen)
      implements Progress {}
}
