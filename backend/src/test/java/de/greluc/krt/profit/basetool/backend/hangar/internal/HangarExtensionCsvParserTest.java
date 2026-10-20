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

package de.greluc.krt.profit.basetool.backend.hangar.internal;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.hangar.api.ShipProductionStatus;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Unit tests for the Hangar Extension CSV reader and the shared value parsers (REQ-HANGAR-015). */
class HangarExtensionCsvParserTest {

  @Test
  void readRecords_handlesQuotesEscapedQuotesCommasAndLineBreaks() {
    String text = "a,\"b, c\",\"say \"\"hi\"\"\"\r\n\r\n\"multi\nline\",,x\n";

    List<List<String>> records = HangarExtensionCsvParser.readRecords(text);

    assertThat(records)
        .containsExactly(List.of("a", "b, c", "say \"hi\""), List.of("multi\nline", "", "x"));
  }

  @Test
  void parse_skipsRowsWithoutPledgeContentAndTakesInsuranceFromItsPledge() {
    String text =
        """
        Pledge ID,Pledge Date,Pledge Cost,Meltable,Giftable,Pledge Name,Pledge Content,Content Type
        "7","March 3, 2023","45.00 USD","no","yes","P","Drake Cutter","Ship"
        "7","March 3, 2023","45.00 USD","no","yes","P","120 Month Insurance","Insurance"
        "8","March 3, 2023","45.00 USD","no","yes","P","","Ship"
        "","March 3, 2023","45.00 USD","no","yes","P","Drake Vulture","Ship"
        """;

    List<FleetExportParser.FleetImportEntry> entries = HangarExtensionCsvParser.parse(text);

    assertThat(entries).hasSize(2);
    FleetExportParser.FleetImportEntry cutter = entries.getFirst();
    assertThat(cutter.name()).isEqualTo("Drake Cutter");
    assertThat(cutter.insurance()).isEqualTo("120");
    assertThat(cutter.pledge()).isNotNull();
    assertThat(cutter.pledge().pledgeDate()).isEqualTo(LocalDate.of(2023, 3, 3));
    assertThat(cutter.pledge().meltable()).isFalse();
    assertThat(cutter.pledge().giftable()).isTrue();
    assertThat(entries.get(1).pledge()).isNull();
    assertThat(entries.get(1).insurance()).isNull();
  }

  @ParameterizedTest
  @CsvSource({
    "'15,000.00 USD', 15000.00, USD",
    "'0.00 USD', 0.00, USD",
    "'4.5 eur', 4.50, EUR",
    "'12.00', 12.00, ",
  })
  void parseCostAndCurrency_readRsiPrices(String raw, String amount, String currency) {
    assertThat(HangarExtensionCsvParser.parseCost(raw)).isEqualByComparingTo(amount);
    assertThat(HangarExtensionCsvParser.parseCurrency(raw)).isEqualTo(currency);
  }

  @Test
  void parseCost_rejectsTextAndHugeAmounts() {
    assertThat(HangarExtensionCsvParser.parseCost("free")).isNull();
    assertThat(HangarExtensionCsvParser.parseCost("99999999999.00 USD")).isNull();
    assertThat(HangarExtensionCsvParser.parseCost(null)).isNull();
  }

  @Test
  void parseDate_readsRsiAndIsoDatesAndIgnoresGarbage() {
    assertThat(HangarExtensionCsvParser.parseDate("October 15, 2026"))
        .isEqualTo(LocalDate.of(2026, 10, 15));
    assertThat(HangarExtensionCsvParser.parseDate("2026-10-15"))
        .isEqualTo(LocalDate.of(2026, 10, 15));
    assertThat(HangarExtensionCsvParser.parseDate("yesterday")).isNull();
  }

  @Test
  void withoutFirstWord_dropsTheManufacturer() {
    assertThat(HangarExtensionCsvParser.withoutFirstWord("Mirai Fury LX")).isEqualTo("Fury LX");
    assertThat(HangarExtensionCsvParser.withoutFirstWord("Mule")).isNull();
  }

  @Test
  void parseYesNo_readsFlagsAndLeavesOthersUnknown() {
    assertThat(HangarExtensionCsvParser.parseYesNo("yes")).isTrue();
    assertThat(HangarExtensionCsvParser.parseYesNo("No")).isFalse();
    assertThat(HangarExtensionCsvParser.parseYesNo("maybe")).isNull();
  }

  @Test
  void pledgeId_acceptsShortTokensOnly() {
    assertThat(HangarExtensionCsvParser.pledgeId("113894222")).isEqualTo("113894222");
    assertThat(HangarExtensionCsvParser.pledgeId("1 2")).isNull();
    assertThat(HangarExtensionCsvParser.pledgeId("x".repeat(33))).isNull();
  }

  @ParameterizedTest
  @CsvSource({
    "LTI, LTI",
    "Lifetime Insurance, LTI",
    "6MI, 6",
    "0MI, 0",
    "120MI, 120",
    "120 Month Insurance, 120",
    "3 Month Insurance, 3",
    "121MI, ",
    "forever, ",
  })
  void parseInsurance_mapsExportTextsToHangarValues(String raw, String expected) {
    assertThat(FleetExportParser.parseInsurance(raw)).isEqualTo(expected);
  }

  @ParameterizedTest
  @CsvSource({
    "Flight-Ready, FLIGHT_READY",
    "flight ready, FLIGHT_READY",
    "In-Concept, IN_CONCEPT",
    "In Production, IN_PRODUCTION",
    "Announced, ",
  })
  void productionStatus_fromExport(String raw, ShipProductionStatus expected) {
    assertThat(ShipProductionStatus.fromExport(raw)).isEqualTo(expected);
  }
}
