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

package de.greluc.krt.profit.basetool.backend.catalogue.internal;

import de.greluc.krt.profit.basetool.backend.catalogue.api.Manufacturer;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipTypeRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipVehicleKind;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kClosedSet;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kLocalisedText;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kShipDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kShipInsurance;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kVehicleKind;
import de.greluc.krt.profit.basetool.backend.kernel.StringNormalization;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Writes the ship base data the P4K catalogue leads (REQ-P4K-031, ADR-0341): class name,
 * manufacturer, name, crew size ({@code crew_max}), role, career, mass, hull health ({@code
 * health}), size ({@code size_class}), vehicle kind and the insurance facts, plus the cargo ({@code
 * scu}) when every cargo grid was read. A {@code null} catalogue value never clears a stored one.
 */
@Component
@RequiredArgsConstructor
class P4kShipBaseData {

  /** The scale of {@code ship_type.scu}. */
  private static final int SCU_SCALE = 3;

  /** The {@code external_sync_report} aggregate of a ship disagreement. */
  private static final String AGGREGATE = "ship_type";

  private final ShipTypeRepository shipTypeRepository;
  private final SyncReportService syncReportService;

  /** Writes one led column; a method reference to a generic method implements it. */
  @FunctionalInterface
  private interface Sink {

    /**
     * Writes one led column.
     *
     * @param column the column
     * @param current the row's value
     * @param incoming the catalogue's value
     * @param setter writes the value
     * @param label renders a value for the report
     * @param <V> the value type
     */
    <V> void put(
        @NotNull String column,
        @Nullable V current,
        @Nullable V incoming,
        @NotNull Consumer<V> setter,
        @NotNull Function<V, String> label);
  }

  /**
   * Writes the led columns of a matched ship, logging each change, and reports a cargo value it
   * keeps and a name it cannot take.
   *
   * @param row the matched ship
   * @param ship the record
   * @param writer the row's writer
   * @param context the run
   */
  void lead(
      @NotNull ShipType row,
      @NotNull P4kShipDto ship,
      @NotNull P4kRowWriter writer,
      @NotNull P4kImportContext context) {
    leadName(row, ship, writer, context);
    writer.lead(
        "class_name",
        row.getClassName(),
        StringNormalization.blankToNull(ship.className()),
        row::setClassName,
        Function.identity());
    writer.lead(
        "manufacturer_id",
        row.getManufacturer(),
        context.manufacturer(ship.manufacturerGuid()),
        row::setManufacturer,
        Manufacturer::getName);
    facts(row, ship, writer::lead);
    BigDecimal cargo = cargo(ship);
    if (cargo == null) {
      return;
    }
    if (cargoComplete(ship)) {
      writer.lead("scu", row.getScu(), cargo, row::setScu, BigDecimal::toPlainString);
    } else {
      reportKeptCargo(row, ship, cargo, context);
    }
  }

  /**
   * Sets the led columns of a ship the run creates; the caller sets the name, class name and
   * manufacturer.
   *
   * @param row the new ship
   * @param ship the record
   */
  void seed(@NotNull ShipType row, @NotNull P4kShipDto ship) {
    facts(row, ship, P4kShipBaseData::setPresent);
    BigDecimal cargo = cargo(ship);
    if (cargo != null && cargoComplete(ship)) {
      row.setScu(cargo);
    }
  }

  /**
   * Reads a record's vehicle kind.
   *
   * @param ship the record
   * @return the kind, or {@code null} when absent or unknown
   */
  @Nullable
  static ShipVehicleKind vehicleKind(@NotNull P4kShipDto ship) {
    P4kVehicleKind kind = P4kClosedSet.parse(P4kVehicleKind.class, ship.vehicleKind());
    return kind == null ? null : kind.toShipVehicleKind();
  }

  /**
   * Writes the fact columns every ship shares.
   *
   * @param row the ship
   * @param ship the record
   * @param sink writes one column
   */
  private static void facts(@NotNull ShipType row, @NotNull P4kShipDto ship, @NotNull Sink sink) {
    sink.put("crew_max", row.getCrewMax(), ship.crewSize(), row::setCrewMax, String::valueOf);
    sink.put("role", row.getRole(), text(ship.role()), row::setRole, Function.identity());
    sink.put("career", row.getCareer(), text(ship.career()), row::setCareer, Function.identity());
    sink.put("mass", row.getMass(), ship.mass(), row::setMass, String::valueOf);
    sink.put("health", row.getHealth(), ship.hullHealth(), row::setHealth, String::valueOf);
    Integer size = ship.size() == null || ship.size() < 0 ? null : ship.size();
    sink.put("size_class", row.getSizeClass(), size, row::setSizeClass, String::valueOf);
    sink.put(
        "vehicle_kind", row.getVehicleKind(), vehicleKind(ship), row::setVehicleKind, Enum::name);
    P4kShipInsurance insurance = ship.insurance();
    if (insurance == null) {
      return;
    }
    sink.put(
        "insurance_restore_on_claim",
        row.getInsuranceRestoreOnClaim(),
        insurance.restoreOnClaim(),
        row::setInsuranceRestoreOnClaim,
        String::valueOf);
    sink.put(
        "insurance_ship_class_name",
        row.getInsuranceShipClassName(),
        StringNormalization.blankToNull(insurance.shipEntityClassName()),
        row::setInsuranceShipClassName,
        Function.identity());
    sink.put(
        "insurance_claim_minutes",
        row.getInsuranceClaimMinutes(),
        insurance.baseWaitTimeMinutes(),
        row::setInsuranceClaimMinutes,
        String::valueOf);
    sink.put(
        "insurance_mandatory_claim_minutes",
        row.getInsuranceMandatoryClaimMinutes(),
        insurance.mandatoryWaitTimeMinutes(),
        row::setInsuranceMandatoryClaimMinutes,
        String::valueOf);
    sink.put(
        "insurance_expedite_fee",
        row.getInsuranceExpediteFee(),
        insurance.expediteFee(),
        row::setInsuranceExpediteFee,
        String::valueOf);
  }

  /**
   * Renames a matched ship to its catalogue name unless another ship type holds that name, which is
   * reported instead ({@code name} is unique).
   *
   * @param row the matched ship
   * @param ship the record
   * @param writer the row's writer
   * @param context the run
   */
  private void leadName(
      @NotNull ShipType row,
      @NotNull P4kShipDto ship,
      @NotNull P4kRowWriter writer,
      @NotNull P4kImportContext context) {
    String name = StringNormalization.blankToNull(ship.nameEn());
    if (name == null || name.strip().equals(row.getName())) {
      return;
    }
    String target = name.strip();
    Optional<ShipType> holder = shipTypeRepository.findByNameIgnoreCase(target);
    if (holder.isPresent() && !holder.get().getId().equals(row.getId())) {
      disagreement(row, ship, context, "column=name;stored=" + row.getName() + ";source=" + target);
      return;
    }
    writer.lead("name", row.getName(), target, row::setName, Function.identity());
  }

  /**
   * Reports a catalogue cargo value the row keeps because some cargo grid was unreadable.
   *
   * @param row the matched ship
   * @param ship the record
   * @param cargo the catalogue's cargo, a lower bound
   * @param context the run
   */
  private void reportKeptCargo(
      @NotNull ShipType row,
      @NotNull P4kShipDto ship,
      @NotNull BigDecimal cargo,
      @NotNull P4kImportContext context) {
    BigDecimal stored = row.getScu();
    if (stored == null || P4kFields.sameValue(stored, cargo)) {
      return;
    }
    disagreement(
        row,
        ship,
        context,
        "column=scu;stored="
            + stored.toPlainString()
            + ";source="
            + cargo.toPlainString()
            + ";cargoGridsWithIssue="
            + ship.cargoGridsWithIssue());
  }

  /**
   * Records a disagreement of an APPLY in {@code external_sync_report}; a preview records none.
   *
   * @param row the ship
   * @param ship the record
   * @param context the run
   * @param detail the {@code column=…;stored=…;source=…} entry
   */
  private void disagreement(
      @NotNull ShipType row,
      @NotNull P4kShipDto ship,
      @NotNull P4kImportContext context,
      @NotNull String detail) {
    UUID runId = context.getRunId();
    if (context.isApply() && runId != null) {
      syncReportService.logP4kEvent(
          runId,
          SyncEventType.P4K_DISAGREEMENT,
          AGGREGATE,
          ship.parsedGuid(),
          row.getName(),
          detail);
    }
  }

  /**
   * Whether the catalogue read every cargo grid of a ship, so its cargo value is exact.
   *
   * @param ship the record
   * @return {@code true} when {@code cargoGridsWithIssue} is 0
   */
  private static boolean cargoComplete(@NotNull P4kShipDto ship) {
    Integer grids = ship.cargoGridsWithIssue();
    return grids != null && grids == 0;
  }

  /**
   * Converts a record's cargo to the scale of {@code ship_type.scu}.
   *
   * @param ship the record
   * @return the cargo in SCU, or {@code null} when the record states none
   */
  @Nullable
  private static BigDecimal cargo(@NotNull P4kShipDto ship) {
    Double scu = ship.cargoScu();
    return scu == null ? null : BigDecimal.valueOf(scu).setScale(SCU_SCALE, RoundingMode.HALF_UP);
  }

  /**
   * Returns the English text of a localised label.
   *
   * @param text the label, or {@code null}
   * @return the trimmed English text, or {@code null} when absent or blank
   */
  @Nullable
  private static String text(@Nullable P4kLocalisedText text) {
    String en = StringNormalization.blankToNull(P4kLocalisedText.enOf(text));
    return en == null ? null : en.strip();
  }

  /**
   * Sets a value when present; the sink of a ship the run creates.
   *
   * @param column the column, unused
   * @param current the row's value, unused
   * @param incoming the catalogue's value
   * @param setter writes the value
   * @param label unused
   * @param <V> the value type
   */
  private static <V> void setPresent(
      @NotNull String column,
      @Nullable V current,
      @Nullable V incoming,
      @NotNull Consumer<V> setter,
      @NotNull Function<V, String> label) {
    if (incoming != null) {
      setter.accept(incoming);
    }
  }
}
