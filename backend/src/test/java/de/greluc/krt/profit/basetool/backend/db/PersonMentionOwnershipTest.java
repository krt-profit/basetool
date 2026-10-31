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
import de.greluc.krt.profit.basetool.backend.platform.api.PersonMentionSource;
import de.greluc.krt.profit.basetool.backend.platform.api.PersonMentionSource.Target;
import de.greluc.krt.profit.basetool.backend.privacy.internal.MentionSourceDeclarations;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Holds every person-mention source to the module that owns the tables it names (REQ-DATA-020,
 * REQ-SEC-060): a source of one module may not search or exempt the columns of another module's
 * table.
 *
 * <p>The owner of a table is its entry in {@code architecture/table-ownership.txt}; the module of a
 * source is the one the domain map assigns its class to. The one table no module owns, Flyway's
 * schema history, belongs to the platform's source.
 */
class PersonMentionOwnershipTest {

  private static final String OWNERSHIP_RESOURCE = "/architecture/table-ownership.txt";

  private static final String PLANTED_CLASS =
      "de.greluc.krt.profit.basetool.backend.mission.internal.PlantedMentionSource";

  @Test
  @DisplayName("every source names only tables its own module owns")
  void everySourceNamesOnlyTablesItsOwnModuleOwns() {
    List<PersonMentionSource> sources = MentionSourceDeclarations.sources();
    assertThat(sources)
        .as("sources found by the scan (an empty scan would pass vacuously)")
        .hasSizeGreaterThanOrEqualTo(20);

    Map<String, PersonMentionSource> byClass = new LinkedHashMap<>();
    sources.forEach(source -> byClass.put(source.getClass().getName(), source));

    assertThat(mismatches(ownership(), DomainMap.load(), byClass))
        .as(
            """
            Tables named by a person-mention source outside the module that owns them. Move the \
            declaration to the PersonMentionSource of the owning module (REQ-DATA-020).\
            """)
        .isEmpty();
  }

  @Test
  @DisplayName("proof: a target, an exempt column and an exempt table of another module differ")
  void aForeignTargetAnExemptColumnAndAnExemptTableAreReported() {
    PersonMentionSource planted =
        new PersonMentionSource() {
          @Override
          public @NotNull List<Target> targets() {
            return List.of(new Target("BANK", "bank_account", "name", "id", null));
          }

          @Override
          public @NotNull Set<String> exemptColumns() {
            return Set.of("audit_event.client_id");
          }

          @Override
          public @NotNull Set<String> exemptTables() {
            return Set.of("city");
          }
        };

    assertThat(mismatches(ownership(), DomainMap.load(), Map.of(PLANTED_CLASS, planted)))
        .hasSize(3);
  }

  private static List<String> mismatches(
      Map<String, String> owners, DomainMap domainMap, Map<String, PersonMentionSource> byClass) {
    List<String> out = new ArrayList<>();
    byClass.forEach((className, source) -> {
      String module = domainMap.ruleFor(className).map(DomainMap.Rule::module).orElse("?");
      List<String> tables = new ArrayList<>();
      source.targets().forEach(t -> tables.add(t.table()));
      source.exemptColumns().forEach(c -> tables.add(c.substring(0, c.indexOf('.'))));
      source.exemptButMayHoldNames().forEach(c -> tables.add(c.substring(0, c.indexOf('.'))));
      tables.addAll(source.exemptTables());
      for (String table : tables) {
        String owner = owners.get(table);
        if (!module.equals(owner)) {
          out.add(table + " is owned by " + owner + " but " + className + " is in " + module);
        }
      }
    });
    return out;
  }

  private static @NotNull Map<String, String> ownership() {
    Map<String, String> owners = new LinkedHashMap<>();
    try (InputStream in =
        PersonMentionOwnershipTest.class.getResourceAsStream(OWNERSHIP_RESOURCE)) {
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
    owners.put("flyway_schema_history", "platform");
    return owners;
  }
}
