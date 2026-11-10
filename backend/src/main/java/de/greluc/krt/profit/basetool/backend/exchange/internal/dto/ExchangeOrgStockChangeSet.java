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

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonValue;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.QualityValue;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.Nullable;

/**
 * A client's batch of org-stock writes, as the ingest gateway relays it after checking it against
 * {@code change-set.schema.json#/$defs/orgStockChangeSet} (REQ-XCH-045).
 *
 * @param ops the writes, at most 500
 * @param dryRun whether to report what would happen without writing
 */
public record ExchangeOrgStockChangeSet(
    @NotNull @Size(min = 1, max = 500) List<@NotNull @Valid Op> ops, boolean dryRun) {

  /** The supported org-stock wire operations. */
  @RequiredArgsConstructor
  @Schema(name = "OrgStockOperation")
  public enum Operation {
    BOOK_IN("book-in"),
    BOOK_OUT("book-out"),
    SELL("sell"),
    MOVE_LOCATION("move-location"),
    MOVE_HOLDER("move-holder"),
    MOVE_POOL("move-pool"),
    NOTE("note"),
    STOLEN("stolen"),
    ALLOCATION_ADD("allocation-add"),
    ALLOCATION_CHANGE("allocation-change"),
    ALLOCATION_REMOVE("allocation-remove");

    private final String wireName;

    /**
     * Returns the operation's stable wire spelling.
     *
     * @return the wire spelling
     */
    @JsonValue
    public String wireName() {
      return wireName;
    }

    /**
     * Decodes the validated wire discriminator.
     *
     * @param name the operation name
     * @return its typed discriminator
     * @throws BadRequestException when the operation is unsupported
     */
    public static Operation decode(String name) {
      return Arrays.stream(values())
          .filter(op -> op.wireName.equals(name))
          .findFirst()
          .orElseThrow(() -> new BadRequestException("Unknown org-stock op"));
    }
  }

  /** Validates the operation spelling before the adapter creates a typed plan. */
  @Constraint(validatedBy = OperationValidator.class)
  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
  public @interface SupportedOperation {
    /** Returns the localized validation message key. */
    String message() default "{exchange.orgStock.unsupportedOperation}";

    /** Returns the validation groups. */
    Class<?>[] groups() default {};

    /** Returns constraint metadata. */
    Class<? extends Payload>[] payload() default {};
  }

  /** Checks wire names against the authoritative operation enum. */
  public static final class OperationValidator
      implements ConstraintValidator<SupportedOperation, String> {
    /**
     * Accepts supported names, leaving required-value checks to NotNull.
     *
     * @param value the wire discriminator
     * @param context validation context
     * @return whether the discriminator is supported or absent
     */
    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
      return value == null
          || Arrays.stream(Operation.values()).anyMatch(op -> op.wireName.equals(value));
    }
  }

  /**
   * One write on a pool; the gateway's schema decides which fields each op carries.
   *
   * @param opId the client's id for the op, echoed in the result, or {@code null}
   * @param op {@code book-in}, {@code book-out}, {@code sell}, {@code move-location}, {@code
   *     move-holder}, {@code move-pool}, {@code note}, {@code stolen}, {@code allocation-add},
   *     {@code allocation-change} or {@code allocation-remove}
   * @param unit the org unit whose pool the op writes
   * @param holder a {@code book-in}'s holder or a {@code move-holder}'s new holder, a {@code ref}
   *     from the roster of the receiving unit
   * @param material a {@code book-in}'s material or item
   * @param location a {@code book-in}'s Lager location or a {@code move-location}'s new one
   * @param quality a {@code book-in}'s quality, ignored for an item
   * @param stolen whether a {@code book-in}'s stock is marked stolen, or the marker a {@code
   *     stolen} op sets
   * @param quantity the amount the op books, sells, moves, marks or earmarks; absent on a {@code
   *     note}, an {@code allocation-remove} and a {@code stolen} op that marks the whole entry
   * @param note a {@code book-in}'s note, or a {@code note} op's new note; {@code null} for none
   * @param mergeScu whether a {@code book-in} or a move of an SCU material merges into the
   *     receiving holder's entry
   * @param jobOrders a {@code book-in}'s job-order earmarks, or {@code null}
   * @param missions a {@code book-in}'s mission earmarks, or {@code null}
   * @param entry the entry every op but {@code book-in} writes
   * @param version the entry's version the client last read
   * @param fromJobOrders how much of a book-out, sale or move comes out of each job-order earmark
   * @param fromMissions how much of a book-out, sale or move comes out of each mission earmark
   * @param terminal a {@code sell}'s terminal
   * @param proceeds the aUEC a {@code sell} brought in
   * @param toUnit the unit whose pool a {@code move-holder} or {@code move-pool} moves into
   * @param jobOrder the job order an earmark op writes the earmark to, or {@code null}
   * @param mission the mission an earmark op writes the earmark to, or {@code null}
   */
  @Schema(name = "OrgStockOp")
  public record Op(
      @Nullable @Size(min = 1, max = 64) String opId,
      @NotNull @SupportedOperation @Schema(implementation = Operation.class) String op,
      @NotNull UUID unit,
      @Nullable @Size(min = 1, max = 128) String holder,
      @Nullable @Valid ExchangeItemRef material,
      @Nullable @Valid ExchangeLocationRef location,
      @Nullable @QualityValue Integer quality,
      @Nullable Boolean stolen,
      @Nullable @Valid ExchangeStockChangeSet.Quantity quantity,
      @Nullable @Size(max = 1000) String note,
      @Nullable Boolean mergeScu,
      @Nullable @Size(max = 100) List<@NotNull @Valid JobOrderLine> jobOrders,
      @Nullable @Size(max = 100) List<@NotNull @Valid MissionLine> missions,
      @Nullable UUID entry,
      @Nullable @Min(0) Long version,
      @Nullable @Size(max = 100) List<@NotNull @Valid JobOrderLine> fromJobOrders,
      @Nullable @Size(max = 100) List<@NotNull @Valid MissionLine> fromMissions,
      @Nullable @Size(min = 1, max = 120) String terminal,
      @Nullable @DecimalMin("0") @DecimalMax("999999999999999") BigDecimal proceeds,
      @Nullable UUID toUnit,
      @Nullable UUID jobOrder,
      @Nullable UUID mission) {
    /**
     * Decodes the operation at the adapter boundary.
     *
     * @return the typed operation
     * @throws BadRequestException if a direct caller bypassed wire validation
     */
    @JsonIgnore
    @Schema(hidden = true)
    public Operation operation() {
      return Operation.decode(op);
    }
  }

  /**
   * An amount for one job order.
   *
   * @param jobOrder the job order
   * @param quantity the amount, in the lot's unit
   */
  @Schema(name = "OrgStockJobOrderLine")
  public record JobOrderLine(
      @NotNull UUID jobOrder, @NotNull @Valid ExchangeStockChangeSet.Quantity quantity) {}

  /**
   * An amount for one mission.
   *
   * @param mission the mission
   * @param quantity the amount, in the lot's unit
   */
  @Schema(name = "OrgStockMissionLine")
  public record MissionLine(
      @NotNull UUID mission, @NotNull @Valid ExchangeStockChangeSet.Quantity quantity) {}
}
