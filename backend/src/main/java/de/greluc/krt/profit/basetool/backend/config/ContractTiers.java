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

package de.greluc.krt.profit.basetool.backend.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

/**
 * The contract tier of every frozen operation, read from {@code api/contract-tiers.txt} on the
 * classpath (REQ-API-018, ADR-0234).
 *
 * <p>One line per operation, {@code <tier> <VERB> <path>}: {@code T0} never breaks, {@code T1} is
 * the Android contract and breaks only in a declared hard-cut wave. Every operation the file does
 * not name is {@code T2}, web only. The same file is what the contract tests compare the frozen set
 * with.
 */
public final class ContractTiers {

  /** Where the tier list lives on the classpath. */
  public static final String RESOURCE = "api/contract-tiers.txt";

  /** The tier that never breaks. */
  public static final String T0 = "T0";

  /** The tier of the Android contract. */
  public static final String T1 = "T1";

  /** The tier of everything only the web frontend calls. */
  public static final String T2 = "T2";

  /** The tiers a line may name. */
  private static final Set<String> LISTED = Set.of(T0, T1);

  /** The verbs a line may name. */
  private static final Set<String> VERBS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");

  /** {@code VERB path} to its tier, for the listed operations. */
  private final Map<String, String> byOperation;

  /**
   * Wraps a parsed tier list.
   *
   * @param byOperation {@code VERB path} to {@code T0} or {@code T1}
   */
  private ContractTiers(@NotNull Map<String, String> byOperation) {
    this.byOperation = Map.copyOf(byOperation);
  }

  /**
   * Reads the tier list off the classpath.
   *
   * @return the parsed list
   * @throws UncheckedIOException if the resource is missing or unreadable
   * @throws IllegalArgumentException if a line is malformed or names an operation twice
   */
  @NotNull
  public static ContractTiers load() {
    try (InputStream in = ContractTiers.class.getClassLoader().getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IOException("missing classpath resource " + RESOURCE);
      }
      return parse(List.of(new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\R")));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Parses the lines of a tier list.
   *
   * @param lines the lines; blank lines are skipped
   * @return the parsed list
   * @throws IllegalArgumentException naming the line when it is not {@code <T0|T1> <VERB> <path>}
   *     or repeats an operation
   */
  @NotNull
  public static ContractTiers parse(@NotNull List<String> lines) {
    Map<String, String> tiers = new HashMap<>();
    for (int index = 0; index < lines.size(); index++) {
      String line = lines.get(index).strip();
      if (line.isEmpty()) {
        continue;
      }
      String[] tokens = line.split(" ");
      if (tokens.length != 3
          || !LISTED.contains(tokens[0])
          || !VERBS.contains(tokens[1])
          || !tokens[2].startsWith("/")) {
        throw new IllegalArgumentException(
            RESOURCE + " line " + (index + 1) + " is not '<T0|T1> <VERB> <path>': " + line);
      }
      if (tiers.put(tokens[1] + " " + tokens[2], tokens[0]) != null) {
        throw new IllegalArgumentException(
            RESOURCE + " line " + (index + 1) + " names an operation a second time: " + line);
      }
    }
    return new ContractTiers(tiers);
  }

  /**
   * Names the tier of one operation.
   *
   * @param method the HTTP verb, any case
   * @param path the path template as the document writes it
   * @return {@code T0} or {@code T1} for a listed operation, {@code T2} otherwise
   */
  @NotNull
  public String tierOf(@NotNull String method, @NotNull String path) {
    return byOperation.getOrDefault(method.toUpperCase(Locale.ROOT) + " " + path, T2);
  }

  /**
   * Lists the operations of one tier.
   *
   * @param tier {@code T0} or {@code T1}
   * @return the operations as {@code VERB path}
   */
  @NotNull
  @Unmodifiable
  public Set<String> operations(@NotNull String tier) {
    return byOperation.entrySet().stream()
        .filter(entry -> entry.getValue().equals(tier))
        .map(Map.Entry::getKey)
        .collect(Collectors.toUnmodifiableSet());
  }
}
