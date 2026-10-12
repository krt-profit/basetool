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

package de.greluc.krt.profit.basetool.frontend.template;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.frontend.kernel.layout.ModuleImportMap;
import de.greluc.krt.profit.basetool.frontend.support.AssetReferences;
import de.greluc.krt.profit.basetool.frontend.support.AssetReferences.Reference;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Resolves every script, stylesheet, font and image a template links to a file of the asset trees
 * the application serves, and every script and stylesheet to a template that links it, so a moved
 * asset cannot leave a dead link or an orphaned file behind (REQ-FE-034).
 */
class AssetReferenceResolutionTest {

  /** Selection floor: asset links of all templates when the test was added. */
  private static final int MIN_REFERENCES = 290;

  private static final Path RESOURCES = AssetReferences.MAIN_RESOURCES;

  @Test
  void everyAssetATemplateLinksExists() {
    List<Reference> references = AssetReferences.inTemplates(RESOURCES.resolve("templates"));

    assertThat(references)
        .as("selection floor: the scan must find at least today's asset links")
        .hasSizeGreaterThanOrEqualTo(MIN_REFERENCES);
    assertThat(unresolved(references, RESOURCES))
        .as("asset links that name no file of a served tree")
        .isEmpty();
  }

  @Test
  void everyScriptAndStylesheetIsLinkedByATemplate() throws IOException {
    Set<String> linked = new TreeSet<>();
    AssetReferences.inTemplates(RESOURCES.resolve("templates"))
        .forEach(reference -> linked.add(reference.url()));
    linked.addAll(new ModuleImportMap(path -> path).importClosure(Set.copyOf(linked)));

    assertThat(orphans(RESOURCES, linked))
        .as("scripts and stylesheets no template links and no linked module imports")
        .isEmpty();
  }

  @Test
  void aLinkToAMissingAssetIsReported(@TempDir Path root) throws IOException {
    write(root.resolve("static/js/bank/planted.js"));
    write(root.resolve("static/css/pages/bank/planted.css"));
    List<Reference> references =
        List.of(
            new Reference("bank/page.html", "/js/bank/planted.js"),
            new Reference("bank/page.html", "/js/planted.js"),
            new Reference("bank/page.html", "/css/pages/planted.css"),
            new Reference("bank/page.html", "/css/pages/bank/planted.css"),
            new Reference("bank/page.html", "/js/${name}.js"),
            new Reference("bank/page.html", "/elsewhere/x.js"));

    assertThat(unresolved(references, root))
        .containsExactly(
            "bank/page.html: /js/planted.js",
            "bank/page.html: /css/pages/planted.css",
            "bank/page.html: /js/${name}.js",
            "bank/page.html: /elsewhere/x.js");
  }

  @Test
  void anAssetNoTemplateLinksIsReported(@TempDir Path root) throws IOException {
    write(root.resolve("static/js/bank/planted.js"));
    write(root.resolve("static/js/bank/unlinked.js"));
    write(root.resolve("static/css/pages/bank/unlinked.css"));

    assertThat(orphans(root, Set.of("/js/bank/planted.js")))
        .containsExactly("/css/pages/bank/unlinked.css", "/js/bank/unlinked.js");
  }

  @Test
  void theScanReadsEveryLinkShape() {
    String source =
        """
        <link rel="stylesheet" th:href="@{/css/kernel/styles.css}">
        <script th:src="@{/js/kernel/krt-fetch.js}" defer th:attr="nonce=${cspNonce}"></script>
        <img src="/images/pattern.svg" alt="">
        <link rel="icon" href="/logos/basetool-favicon.svg?v=2">
        <a href="/missions">not an asset</a>
        """;

    assertThat(AssetReferences.urls(source))
        .containsExactly(
            "/css/kernel/styles.css",
            "/js/kernel/krt-fetch.js",
            "/images/pattern.svg",
            "/logos/basetool-favicon.svg");
  }

  private static @NotNull List<String> unresolved(
      @NotNull List<Reference> references, @NotNull Path resources) {
    List<String> missing = new ArrayList<>();
    for (Reference reference : references) {
      if (!AssetReferences.resolves(reference.url(), resources)) {
        missing.add(reference.template() + ": " + reference.url());
      }
    }
    return missing;
  }

  private static @NotNull List<String> orphans(@NotNull Path resources, @NotNull Set<String> linked)
      throws IOException {
    List<String> orphans = new ArrayList<>();
    for (String tree : List.of("js", "css")) {
      Path base = resources.resolve("static").resolve(tree);
      if (!Files.isDirectory(base)) {
        continue;
      }
      try (Stream<Path> files = Files.walk(base)) {
        for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
          String url = "/" + tree + "/" + base.relativize(file).toString().replace('\\', '/');
          if (!linked.contains(url)) {
            orphans.add(url);
          }
        }
      }
    }
    return orphans.stream().sorted().toList();
  }

  private static void write(@NotNull Path file) throws IOException {
    Files.createDirectories(file.getParent());
    Files.writeString(file, "x\n", StandardCharsets.UTF_8);
  }
}
