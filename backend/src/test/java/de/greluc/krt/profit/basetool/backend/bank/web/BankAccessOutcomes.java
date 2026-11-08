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

package de.greluc.krt.profit.basetool.backend.bank.web;

import de.greluc.krt.profit.basetool.backend.bank.web.BankAccessMatrix.Case;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

/**
 * The recorded outcome of every matrix case, {@code bank/bank-access-matrix.tsv} in the test
 * resources: one tab-separated line per case, the {@link Case#key()} columns followed by the
 * outcome.
 *
 * <p>The file is the expectation of the web path and of every later path that must answer alike. A
 * run writes what it observed to {@link #ACTUAL}; once a change of outcome is intended, that file
 * replaces the recorded one.
 */
final class BankAccessOutcomes {

  /** The classpath location of the recorded outcomes. */
  static final String RESOURCE = "/bank/bank-access-matrix.tsv";

  /** Where a run writes the outcomes it observed, relative to the module directory. */
  static final Path ACTUAL = Path.of("build", "bank-access-matrix", "bank-access-matrix.tsv");

  /** The header line naming the columns. */
  private static final String HEADER = "actor\toperation\tsubject\tband\tpin\toutcome";

  private BankAccessOutcomes() {}

  /**
   * Reads the recorded outcomes.
   *
   * @return the outcome by case key; empty when nothing is recorded yet
   */
  @NotNull
  @Unmodifiable
  static Map<String, String> recorded() {
    Map<String, String> outcomes = new HashMap<>();
    try (InputStream stream = BankAccessOutcomes.class.getResourceAsStream(RESOURCE)) {
      if (stream == null) {
        return Map.of();
      }
      BufferedReader reader =
          new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
      reader
          .lines()
          .filter(line -> !line.isBlank() && !line.equals(HEADER))
          .forEach(line -> {
            int split = line.lastIndexOf('\t');
            outcomes.put(line.substring(0, split), line.substring(split + 1));
          });
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return Map.copyOf(outcomes);
  }

  /**
   * Writes observed outcomes to {@link #ACTUAL} in matrix order.
   *
   * @param cases the cases in the order to write
   * @param observed the observed outcome by case key; a case without one is skipped
   */
  static void writeActual(@NotNull List<Case> cases, @NotNull Map<String, String> observed) {
    StringBuilder text = new StringBuilder(HEADER).append('\n');
    for (Case c : cases) {
      String outcome = observed.get(c.key());
      if (outcome != null) {
        text.append(c.key()).append('\t').append(outcome).append('\n');
      }
    }
    try {
      Files.createDirectories(ACTUAL.getParent());
      Files.writeString(ACTUAL, text, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
