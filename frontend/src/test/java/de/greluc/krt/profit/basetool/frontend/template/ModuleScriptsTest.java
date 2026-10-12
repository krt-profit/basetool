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
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

/**
 * Guards the ES-module step (REQ-FE-035, plan F5): a script is a module unless {@code
 * module-ratchet.json} lists it as classic, a module is linked with {@code type="module"} and a
 * classic script without it, a module imports only absolute {@code /js/} paths of its own domain or
 * of the kernel, imports form no cycle, and the classic list only shrinks.
 */
class ModuleScriptsTest {

  /**
   * The most classic scripts the manifest may list; lower it whenever a script becomes a module.
   */
  private static final int CLASSIC_CEILING = 1;

  /** Selection floor: module scripts when the guard was added. */
  private static final int MODULES_FLOOR = 147;

  private static final Path SCRIPTS = AssetReferences.MAIN_RESOURCES.resolve("static/js");

  private static final Path TEMPLATES = AssetReferences.MAIN_RESOURCES.resolve("templates");

  private static final Path MANIFEST = Path.of("module-ratchet.json");

  private static final Pattern SCRIPT_TAG = Pattern.compile("<script\\b([^>]*)>");

  private static final Pattern SCRIPT_SRC =
      Pattern.compile("th:src\\s*=\\s*\"@\\{(/js/[^}?\"]+)[}?]");

  private static final Pattern BARE_IMPORT = Pattern.compile("(?m)^import\\s*(['\"])([^'\"]+)\\1");

  private static final Pattern FROM_IMPORT =
      Pattern.compile("(?m)^(?:import|export)\\b[^;'\"()]*?\\bfrom\\s*(['\"])([^'\"]+)\\1");

  private static final Pattern DYNAMIC_IMPORT = Pattern.compile("\\bimport\\s*\\(");

  private static final Pattern SPECIFIER = Pattern.compile("/js/([a-z]+)/[a-z0-9-]+\\.js");

  @Test
  void theClassicListNamesRealScriptsAndOnlyShrinks() throws IOException {
    Set<String> classic = classicScripts();

    assertThat(classic).allSatisfy(script -> assertThat(SCRIPTS.resolve(script)).isRegularFile());
    assertThat(classic.size()).as("classic scripts").isLessThanOrEqualTo(CLASSIC_CEILING);
    assertThat(allScripts().size() - classic.size())
        .as("module scripts")
        .isGreaterThanOrEqualTo(MODULES_FLOOR);
  }

  @Test
  void aScriptIsLinkedAsAModuleExactlyWhenItIsNotClassic() throws IOException {
    Set<String> classic = classicScripts();
    List<String> offenders = new ArrayList<>();
    try (Stream<Path> tree = Files.walk(TEMPLATES)) {
      for (Path template : tree.filter(p -> p.toString().endsWith(".html")).sorted().toList()) {
        String name = TEMPLATES.relativize(template).toString().replace('\\', '/');
        Matcher tag = SCRIPT_TAG.matcher(Files.readString(template, StandardCharsets.UTF_8));
        while (tag.find()) {
          Matcher src = SCRIPT_SRC.matcher(tag.group(1));
          if (!src.find()) {
            continue;
          }
          String script = src.group(1).substring("/js/".length());
          boolean module = tag.group(1).contains("type=\"module\"");
          if (module == classic.contains(script) || (module && tag.group(1).contains("defer"))) {
            offenders.add(name + " -> " + tag.group());
          }
        }
      }
    }

    assertThat(offenders)
        .as("a module is linked type=\"module\" without defer, a classic script without type")
        .isEmpty();
  }

  @Test
  void everyPageMapsTheModulesItsOwnTemplateLinksAndTheirImports() throws IOException {
    ModuleImportMap map = new ModuleImportMap(path -> path);
    List<String> offenders = new ArrayList<>();
    int pages = 0;
    try (Stream<Path> tree = Files.walk(TEMPLATES)) {
      for (Path template : tree.filter(p -> p.toString().endsWith(".html")).sorted().toList()) {
        String source = Files.readString(template, StandardCharsets.UTF_8);
        if (!source.contains("~{kernel/fragments/layout :: page(")) {
          continue;
        }
        pages++;
        String view =
            TEMPLATES.relativize(template).toString().replace('\\', '/').replaceAll("\\.html$", "");
        Set<String> linked = new TreeSet<>();
        Matcher tag = SCRIPT_TAG.matcher(source);
        while (tag.find()) {
          Matcher src = SCRIPT_SRC.matcher(tag.group(1));
          if (tag.group(1).contains("type=\"module\"") && src.find()) {
            linked.add(src.group(1));
          }
        }
        if (!map.entryModules(view).containsAll(linked)) {
          offenders.add(view + " does not find its module scripts " + linked);
        }
        Set<String> mapped = new TreeSet<>();
        Matcher json = Pattern.compile("\"(/js/[^\"]+)\":").matcher(map.json(view));
        while (json.find()) {
          mapped.add(json.group(1));
        }
        if (!mapped.equals(new TreeSet<>(map.importClosure(map.entryModules(view))))) {
          offenders.add(view + " renders a map that differs from its import closure");
        }
      }
    }

    assertThat(pages).as("pages composed through the layout").isGreaterThanOrEqualTo(80);
    assertThat(offenders).isEmpty();
  }

  @Test
  void aModuleImportsOnlyAbsolutePathsOfItsOwnDomainOrTheKernel() throws IOException {
    Set<String> classic = classicScripts();
    List<String> offenders = new ArrayList<>();
    for (String script : allScripts()) {
      String source = Files.readString(SCRIPTS.resolve(script), StandardCharsets.UTF_8);
      boolean isModule = !classic.contains(script);
      String domain = script.substring(0, script.indexOf('/'));
      if (!isModule) {
        if (!specifiers(source).isEmpty()) {
          offenders.add(script + " is classic but imports");
        }
        continue;
      }
      if (DYNAMIC_IMPORT.matcher(source).find()) {
        offenders.add(script + " uses a dynamic import()");
      }
      for (String specifier : specifiers(source)) {
        Matcher match = SPECIFIER.matcher(specifier);
        if (!match.matches()
            || !(match.group(1).equals(domain) || match.group(1).equals("kernel"))) {
          offenders.add(script + " imports " + specifier);
        } else if (!Files.isRegularFile(SCRIPTS.resolve(specifier.substring("/js/".length())))
            || classic.contains(specifier.substring("/js/".length()))) {
          offenders.add(script + " imports " + specifier + ", which is no module");
        }
      }
    }

    assertThat(offenders).as("module imports outside the boundary").isEmpty();
  }

  @Test
  void theImportGraphHasNoCycle() throws IOException {
    Map<String, Set<String>> graph = new LinkedHashMap<>();
    for (String script : allScripts()) {
      graph.put(
          "/js/" + script,
          specifiers(Files.readString(SCRIPTS.resolve(script), StandardCharsets.UTF_8)));
    }

    assertThat(cycle(graph)).as("import cycle").isEmpty();
  }

  @Test
  void theCycleCheckFindsAPlantedCycle() {
    Map<String, Set<String>> graph = new LinkedHashMap<>();
    graph.put("/js/a/x.js", Set.of("/js/a/y.js"));
    graph.put("/js/a/y.js", Set.of("/js/a/z.js"));
    graph.put("/js/a/z.js", Set.of("/js/a/x.js"));
    graph.put("/js/a/w.js", Set.of("/js/a/x.js"));

    assertThat(cycle(graph))
        .containsExactly("/js/a/x.js", "/js/a/y.js", "/js/a/z.js", "/js/a/x.js");
    assertThat(cycle(Map.of("/js/a/x.js", Set.of("/js/a/y.js"), "/js/a/y.js", Set.of()))).isEmpty();
  }

  @Test
  void theScanReadsEveryImportShape() {
    String source =
        """
        import { a, b } from '/js/bank/a.js';
        import c from "/js/bank/c.js";
        import '/js/bank/d.js';
        export { e } from '/js/bank/e.js';
        import {
            f,
        } from '/js/bank/f.js';
        export function notAnImport() {}
        """;

    assertThat(specifiers(source))
        .containsExactlyInAnyOrder(
            "/js/bank/a.js", "/js/bank/c.js", "/js/bank/d.js", "/js/bank/e.js", "/js/bank/f.js");
  }

  private static @NotNull Set<String> specifiers(@NotNull String source) {
    Set<String> found = new LinkedHashSet<>();
    for (Pattern pattern : List.of(BARE_IMPORT, FROM_IMPORT)) {
      Matcher matcher = pattern.matcher(source);
      while (matcher.find()) {
        found.add(matcher.group(2));
      }
    }
    return found;
  }

  private static @NotNull List<String> cycle(@NotNull Map<String, Set<String>> graph) {
    Set<String> done = new LinkedHashSet<>();
    for (String start : graph.keySet()) {
      List<String> path = new ArrayList<>();
      List<String> found = visit(start, graph, done, path);
      if (!found.isEmpty()) {
        return found;
      }
    }
    return List.of();
  }

  private static @NotNull List<String> visit(
      @NotNull String node,
      @NotNull Map<String, Set<String>> graph,
      @NotNull Set<String> done,
      @NotNull List<String> path) {
    int at = path.indexOf(node);
    if (at >= 0) {
      List<String> cycle = new ArrayList<>(path.subList(at, path.size()));
      cycle.add(node);
      return cycle;
    }
    if (done.contains(node)) {
      return List.of();
    }
    path.add(node);
    for (String next : new TreeSet<>(graph.getOrDefault(node, Set.of()))) {
      List<String> found = visit(next, graph, done, path);
      if (!found.isEmpty()) {
        return found;
      }
    }
    path.remove(path.size() - 1);
    done.add(node);
    return List.of();
  }

  private static @NotNull Set<String> classicScripts() throws IOException {
    Matcher matcher =
        Pattern.compile("\"([a-z0-9-]+/[A-Za-z0-9._/-]+\\.js)\"")
            .matcher(Files.readString(MANIFEST, StandardCharsets.UTF_8));
    Set<String> classic = new TreeSet<>();
    while (matcher.find()) {
      classic.add(matcher.group(1));
    }
    return classic;
  }

  private static @NotNull Set<String> allScripts() throws IOException {
    Set<String> scripts = new TreeSet<>();
    try (Stream<Path> tree = Files.walk(SCRIPTS)) {
      for (Path file : tree.filter(p -> p.toString().endsWith(".js")).toList()) {
        String relative = SCRIPTS.relativize(file).toString().replace('\\', '/');
        if (!relative.startsWith("vendor/")) {
          scripts.add(relative);
        }
      }
    }
    return scripts;
  }
}
