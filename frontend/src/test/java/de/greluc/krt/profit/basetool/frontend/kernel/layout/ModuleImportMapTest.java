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

package de.greluc.krt.profit.basetool.frontend.kernel.layout;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Pins the import map a page renders (REQ-FE-035): the modules a page's scripts import, keyed by
 * their asset path, valued by the content-hashed URL, in sorted order and with nothing that could
 * close the script element.
 */
class ModuleImportMapTest {

  private final ModuleImportMap map =
      new ModuleImportMap(path -> path.replace(".js", "-0123456789abcdef0123456789abcdef.js"));

  @Test
  void aPageMapsEveryModuleItsEntryScriptsImport() {
    String json = map.json("bank/bank-dashboard");

    assertThat(json)
        .startsWith("{\"imports\":{")
        .endsWith("}}")
        .contains(
            "\"/js/bank/bank-livesync.js\":\"/js/bank/bank-livesync-0123456789abcdef0123456789abcdef.js\"")
        .contains("\"/js/bank/bank-messages.js\":")
        .contains("\"/js/bank/bank-previews.js\":");
  }

  @Test
  void theMapNamesOnlyImportedModulesNotTheEntriesNobodyImports() {
    String json = map.json("bank/bank-dashboard");

    assertThat(json).doesNotContain("\"/js/bank/bank.js\"");
  }

  @Test
  void aPageMapsNothingOfAnotherDomainsModules() {
    assertThat(map.json("bank/bank-dashboard")).doesNotContain("/js/mission/");
  }

  @Test
  void theKeysAreSortedAndPlainAssetPaths() {
    String json = map.json("bank/bank-dashboard");
    List<String> keys =
        Pattern.compile("\"(/js/[^\"]+)\":")
            .matcher(json)
            .results()
            .map(match -> match.group(1))
            .toList();

    assertThat(keys)
        .isNotEmpty()
        .isSorted()
        .allMatch(key -> key.matches("/js/[A-Za-z0-9._/-]+\\.js"));
    assertThat(json).doesNotContain("<").doesNotContain("\n");
  }

  @Test
  void aViewWithoutATemplateGetsTheLayoutMapAlone() {
    assertThat(map.json("no/such/page")).isEqualTo(map.json(""));
    assertThat(map.json(null)).isEqualTo(map.json(""));
    assertThat(map.json("no/such/page")).doesNotContain("/js/bank/");
  }

  @Test
  void anEntryModuleIsFoundInTheTemplateAndItsFragments() {
    assertThat(map.entryModules("bank/bank-dashboard")).contains("/js/bank/bank.js");
    assertThat(map.entryModules("bank/bank-dashboard"))
        .doesNotContain("/js/mission/mission-detail.js");
  }

  @Test
  void theImportsOfAModuleAreReadFromItsStaticImportStatements() {
    assertThat(map.importsOf("/js/bank/bank.js"))
        .contains(
            "/js/bank/bank-livesync.js",
            "/js/bank/bank-form-submit.js",
            "/js/bank/bank-modal-prime.js");
    assertThat(map.importsOf("/js/bank/bank-messages.js")).isEmpty();
    assertThat(map.importsOf("/js/bank/no-such-module.js")).isEmpty();
  }

  @Test
  void theClosureFollowsImportsTransitively() {
    Set<String> closure = map.importClosure(Set.of("/js/bank/bank.js"));

    assertThat(closure)
        .contains("/js/bank/bank-messages.js", "/js/bank/bank-controls.js")
        .doesNotContain("/js/bank/bank.js");
  }
}
