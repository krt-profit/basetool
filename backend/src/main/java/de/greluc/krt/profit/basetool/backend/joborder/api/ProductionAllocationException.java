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

import de.greluc.krt.profit.basetool.backend.kernel.DomainProblem;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.HttpStatus;

/**
 * Thrown when a production booking (REQ-ORDERS-025) is refused by one of its five allocation
 * checks.
 *
 * <p>Mapped to {@code 422 Unprocessable Entity} with code {@link
 * JobOrderProblemCode#PRODUCTION_ALLOCATION}. The {@link Reason} and its allow-listed figures reach
 * the handler's WARN line through {@link #logExtra()} and never the problem body (REQ-OBS-004).
 */
public final class ProductionAllocationException extends DomainProblem {

  /** The allocation check that refused the booking. */
  public enum Reason {
    /** The booked amount is above the line's remaining units. */
    AMOUNT_EXCEEDS_REMAINING,
    /** A material's consumption is not equal to its demand. */
    DEMAND_MISMATCH,
    /** A consumed material is not required by the line. */
    MATERIAL_NOT_REQUIRED,
    /** An entry is drawn over its order slice. */
    OVER_ORDER_SLICE,
    /** An entry is drawn over its stock. */
    OVER_STOCK
  }

  /** The refusing check. */
  private final Reason reason;

  /** The allow-listed ids and figures of the refusal; never free text. */
  private final Map<String, Object> fields;

  /**
   * Creates a refusal; the client-visible {@code detail} resolves from the localized {@code
   * problem.production_allocation.detail} bundle key so it is translated per the caller's {@code
   * Accept-Language}.
   *
   * @param reason the refusing check
   * @param fields the ids and numbers that explain it; copied and logged as given
   */
  private ProductionAllocationException(
      @NotNull Reason reason, @NotNull Map<String, Object> fields) {
    super("problem.production_allocation.detail");
    this.reason = reason;
    this.fields = fields;
  }

  /**
   * The booked amount is above the line's remaining units.
   *
   * @param amount the booked units
   * @param remaining the units still to manufacture
   * @return the refusal
   */
  @NotNull
  public static ProductionAllocationException amountExceedsRemaining(int amount, int remaining) {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("amount", amount);
    fields.put("remaining", remaining);
    return new ProductionAllocationException(Reason.AMOUNT_EXCEEDS_REMAINING, fields);
  }

  /**
   * A material's consumption is not equal to its demand.
   *
   * @param materialId the material
   * @param demand the quantity the booking requires
   * @param consumed the quantity the payload consumes
   * @return the refusal
   */
  @NotNull
  public static ProductionAllocationException demandMismatch(
      @NotNull UUID materialId, double demand, double consumed) {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("materialId", materialId);
    fields.put("demand", demand);
    fields.put("consumed", consumed);
    return new ProductionAllocationException(Reason.DEMAND_MISMATCH, fields);
  }

  /**
   * A consumed material is not required by the line.
   *
   * @param materialId the consumed material
   * @param consumed the quantity the payload consumes
   * @return the refusal
   */
  @NotNull
  public static ProductionAllocationException materialNotRequired(
      @NotNull UUID materialId, double consumed) {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("materialId", materialId);
    fields.put("consumed", consumed);
    return new ProductionAllocationException(Reason.MATERIAL_NOT_REQUIRED, fields);
  }

  /**
   * An entry is drawn over its order slice.
   *
   * @param materialId the consumed material
   * @param inventoryItemId the drawn entry
   * @param consumed the quantity drawn
   * @param slice the entry's slice reserved for the order
   * @return the refusal
   */
  @NotNull
  public static ProductionAllocationException overOrderSlice(
      @NotNull UUID materialId, @NotNull UUID inventoryItemId, double consumed, double slice) {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("materialId", materialId);
    fields.put("inventoryItemId", inventoryItemId);
    fields.put("consumed", consumed);
    fields.put("slice", slice);
    return new ProductionAllocationException(Reason.OVER_ORDER_SLICE, fields);
  }

  /**
   * An entry is drawn over its stock.
   *
   * @param materialId the consumed material
   * @param inventoryItemId the drawn entry
   * @param consumed the quantity drawn
   * @param stock the entry's stock
   * @return the refusal
   */
  @NotNull
  public static ProductionAllocationException overStock(
      @NotNull UUID materialId, @NotNull UUID inventoryItemId, double consumed, double stock) {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("materialId", materialId);
    fields.put("inventoryItemId", inventoryItemId);
    fields.put("consumed", consumed);
    fields.put("stock", stock);
    return new ProductionAllocationException(Reason.OVER_STOCK, fields);
  }

  /**
   * The check that refused the booking.
   *
   * @return the refusal's reason
   */
  @NotNull
  public Reason reason() {
    return reason;
  }

  /**
   * The status the refusal is answered with.
   *
   * @return {@code 422}
   */
  @NotNull
  @Override
  public HttpStatus status() {
    return JobOrderProblemCode.PRODUCTION_ALLOCATION.status();
  }

  /**
   * The stable code of the refusal.
   *
   * @return {@code PRODUCTION_ALLOCATION}
   */
  @NotNull
  @Override
  public String code() {
    return JobOrderProblemCode.PRODUCTION_ALLOCATION.code();
  }

  /**
   * The label the handler's log line names the refusal by.
   *
   * @return {@code "Production allocation"}
   */
  @NotNull
  @Override
  public String logLabel() {
    return "Production allocation";
  }

  /**
   * The allow-listed fields of the handler's WARN line: the {@code reason} name and the ids and
   * numbers of the failed check, never the request body.
   *
   * @return the reason followed by the check's fields, in insertion order
   */
  @NotNull
  @Override
  public Map<String, Object> logExtra() {
    Map<String, Object> extra = new LinkedHashMap<>();
    extra.put("reason", reason.name());
    extra.putAll(fields);
    return Collections.unmodifiableMap(extra);
  }
}
