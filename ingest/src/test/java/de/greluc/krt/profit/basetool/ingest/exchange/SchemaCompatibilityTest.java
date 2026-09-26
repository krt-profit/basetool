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

package de.greluc.krt.profit.basetool.ingest.exchange;

import static org.assertj.core.api.Assertions.assertThat;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Proves that {@link SchemaCompatibility} tells additive schema changes from breaking ones, so the
 * release-baseline check of REQ-XCH-026 cannot pass by checking nothing.
 */
class SchemaCompatibilityTest {

  private static final String BASE =
      "{'type':'object','required':['a'],'properties':{'a':{'type':'string','maxLength':40,"
          + "'pattern':'^x'},'n':{'type':'integer','minimum':0,'maximum':10},"
          + "'e':{'enum':['A','B']}},'$defs':{'d':{'type':'string'}},"
          + "'oneOf':[{'const':'add'},{'const':'remove'}]}";

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "\"required\":[\"a\"]|\"required\":[\"a\"],\"properties\":{\"z\":{\"type\":\"string\"}}",
        "\"maxLength\":40|\"maxLength\":80",
        "\"minimum\":0|\"minimum\":-5",
        "\"enum\":[\"A\",\"B\"]|\"enum\":[\"A\",\"B\",\"C\"]",
        "{\"const\":\"remove\"}]|{\"const\":\"remove\"},{\"const\":\"link\"}]",
        "\"maximum\":10|\"description\":\"no upper bound any more\""
      })
  void additiveChangesPass(String from, String to) {
    assertThat(SchemaCompatibility.breakingChanges(tree(BASE), tree(swap(from, to)), "#"))
        .isEmpty();
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "\"required\":[\"a\"]|\"required\":[\"a\",\"n\"]",
        "\"maxLength\":40|\"maxLength\":20",
        "\"minimum\":0|\"minimum\":1",
        "\"enum\":[\"A\",\"B\"]|\"enum\":[\"A\"]",
        "\"pattern\":\"^x\"|\"pattern\":\"^y\"",
        "\"type\":\"integer\"|\"type\":\"number\"",
        "\"$defs\":{\"d\":{\"type\":\"string\"}}|\"$defs\":{}",
        "{\"const\":\"add\"},{\"const\":\"remove\"}|{\"const\":\"add\"}",
        "\"e\":{\"enum\"|\"e\":{\"minLength\":1,\"enum\""
      })
  void breakingChangesAreReported(String from, String to) {
    assertThat(SchemaCompatibility.breakingChanges(tree(BASE), tree(swap(from, to)), "#"))
        .isNotEmpty();
  }

  /**
   * Replaces one fragment of the base schema, failing loudly when the fragment is not there.
   *
   * @param from the fragment in the base schema
   * @param to its replacement
   * @return the changed schema text
   */
  private static @NotNull String swap(@NotNull String from, @NotNull String to) {
    String base = BASE.replace('\'', '"');
    assertThat(base).contains(from);
    return base.replace(from, to);
  }

  /**
   * Parses schema text, accepting single quotes for readability.
   *
   * @param json the schema
   * @return its tree
   */
  private static @NotNull JsonNode tree(@NotNull String json) {
    return MAPPER.readTree(json.replace('\'', '"'));
  }
}
