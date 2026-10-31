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

package de.greluc.krt.profit.basetool.backend.db;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.architecture.DomainMap;
import de.greluc.krt.profit.basetool.backend.platform.api.PersonalDataExportSection;
import de.greluc.krt.profit.basetool.backend.platform.api.PersonalDataExportSection.LegalBasis;
import de.greluc.krt.profit.basetool.backend.platform.api.PersonalDataExportSection.Section;
import de.greluc.krt.profit.basetool.backend.privacy.internal.ExportSectionDeclarations;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Holds every export section to the module that owns the table it selects the member's rows from
 * (REQ-DATA-020, REQ-SEC-058): a participant of one module may not declare the rows of another.
 *
 * <p>The owning module of a section is the owner, in {@code architecture/table-ownership.txt}, of
 * the table its {@code :userId} condition filters; the module of a participant is the one the
 * domain map assigns its class to.
 */
class PersonalDataExportOwnershipTest {

  private static final String OWNERSHIP_RESOURCE = "/architecture/table-ownership.txt";

  private static final String PLANTED_CLASS =
      "de.greluc.krt.profit.basetool.backend.mission.internal.PlantedExportSections";

  @Test
  @DisplayName("every section is declared by the module that owns the table it filters")
  void everySectionIsDeclaredByTheModuleThatOwnsItsKeyTable() {
    Map<String, List<Section>> byClass = new TreeMap<>();
    for (PersonalDataExportSection participant : ExportSectionDeclarations.participants()) {
      byClass.put(participant.getClass().getName(), participant.sections());
    }
    assertThat(byClass)
        .as("participants found by the scan (an empty scan would pass vacuously)")
        .hasSizeGreaterThanOrEqualTo(16);

    assertThat(mismatches(ownership(), DomainMap.load(), byClass))
        .as(
            """
            Export sections declared outside the module that owns their table. Move the section \
            to a PersonalDataExportSection of the owning module (REQ-DATA-020).\
            """)
        .isEmpty();
  }

  @Test
  @DisplayName("proof: a section of another module's table is reported")
  void aSectionOfAnotherModulesTableIsReported() {
    Section foreign =
        new Section(
            9000,
            "planted",
            LegalBasis.ART_15,
            "A planted section that filters a bank table from the mission module.",
            "SELECT note FROM bank_booking_request WHERE requested_by = :userId");

    assertThat(mismatches(ownership(), DomainMap.load(), Map.of(PLANTED_CLASS, List.of(foreign))))
        .hasSize(1)
        .first()
        .asString()
        .contains("planted", "bank_booking_request", "bank", "mission");
  }

  @Test
  @DisplayName("proof: a section with no :userId key condition is reported")
  void aSectionWithoutAKeyConditionIsReported() {
    Section keyless =
        new Section(
            9001,
            "keyless",
            LegalBasis.ART_15,
            "A planted section that filters nothing by the member.",
            "SELECT name FROM mission");

    assertThat(mismatches(ownership(), DomainMap.load(), Map.of(PLANTED_CLASS, List.of(keyless))))
        .hasSize(1);
  }

  private static List<String> mismatches(
      Map<String, String> owners, DomainMap domainMap, Map<String, List<Section>> byClass) {
    List<String> out = new ArrayList<>();
    byClass.forEach((className, sections) -> {
      String module = domainMap.ruleFor(className).map(DomainMap.Rule::module).orElse("?");
      for (Section section : sections) {
        String key = GdprParticipantCoverageTest.exportKey(section.sql(), owners.keySet());
        String table = key == null ? null : key.substring(0, key.indexOf('.'));
        String owner = table == null ? null : owners.get(table);
        if (owner == null || !owner.equals(module)) {
          out.add(
              section.key()
                  + " filters "
                  + table
                  + " (owner "
                  + owner
                  + ") but "
                  + className
                  + " is in "
                  + module);
        }
      }
    });
    return out;
  }

  private static @NotNull Map<String, String> ownership() {
    Map<String, String> owners = new LinkedHashMap<>();
    try (InputStream in =
        PersonalDataExportOwnershipTest.class.getResourceAsStream(OWNERSHIP_RESOURCE)) {
      assertThat(in).isNotNull();
      new String(in.readAllBytes(), StandardCharsets.UTF_8)
          .lines()
          .map(String::strip)
          .filter(line -> !line.isEmpty())
          .forEach(line -> {
            String[] parts = line.split("\\s+");
            owners.put(parts[0], parts[1]);
          });
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
    return owners;
  }
}
