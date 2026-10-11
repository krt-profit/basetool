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

package de.greluc.krt.profit.basetool.frontend.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

/**
 * Finds the static assets a template links and resolves them against the trees the application
 * serves ({@code WebMvcConfig}), so a moved or renamed asset cannot leave a dead link behind.
 */
public final class AssetReferences {

  /** The main resources root, relative to the module directory Gradle runs tests in. */
  public static final Path MAIN_RESOURCES = Path.of("src", "main", "resources");

  /**
   * The served tree of each URL prefix and the resource directories backing it, as {@code
   * WebMvcConfig} registers them.
   */
  private static final Map<String, List<String>> TREES =
      Map.of(
          "css", List.of("static/css"),
          "js", List.of("static/js"),
          "fonts", List.of("static/fonts"),
          "images", List.of("META-INF/resources/images", "static/images"),
          "logos", List.of("META-INF/resources/logos"));

  /**
   * A linked asset: {@code th:src}, {@code th:href}, {@code src} or {@code href} on a served tree.
   */
  private static final Pattern REFERENCE =
      Pattern.compile(
          "(?:th:(?:src|href)\\s*=\\s*\"@\\{|\\b(?:src|href)\\s*=\\s*\")"
              + "(/(?:css|js|fonts|images|logos)/[^\"}]*)[}\"]");

  /** Not instantiable. */
  private AssetReferences() {}

  /**
   * One asset link of one template.
   *
   * @param template the template's path below the template root, with {@code /} separators
   * @param url the served URL, such as {@code /js/bank/bank.js}
   */
  public record Reference(@NotNull String template, @NotNull String url) {}

  /**
   * Finds every asset URL a template source links, in document order.
   *
   * @param source the template source
   * @return the served URLs, query and fragment removed; a URL with an expression keeps its {@code
   *     $}
   */
  public static @NotNull @Unmodifiable List<String> urls(@NotNull String source) {
    List<String> urls = new ArrayList<>();
    Matcher matcher = REFERENCE.matcher(source);
    while (matcher.find()) {
      String url = matcher.group(1);
      int cut = url.indexOf('?');
      urls.add(cut < 0 ? url : url.substring(0, cut));
    }
    return List.copyOf(urls);
  }

  /**
   * Finds every asset link of every template below a root.
   *
   * @param templateRoot the template root
   * @return the links, sorted by template
   */
  public static @NotNull @Unmodifiable List<Reference> inTemplates(@NotNull Path templateRoot) {
    List<Reference> references = new ArrayList<>();
    try (Stream<Path> tree = Files.walk(templateRoot)) {
      for (Path template : tree.filter(p -> p.toString().endsWith(".html")).sorted().toList()) {
        String name = templateRoot.relativize(template).toString().replace('\\', '/');
        String source = Files.readString(template, StandardCharsets.UTF_8);
        for (String url : urls(source)) {
          references.add(new Reference(name, url));
        }
      }
    } catch (IOException ex) {
      throw new UncheckedIOException(ex);
    }
    return List.copyOf(references);
  }

  /**
   * Resolves a served URL to the file backing it.
   *
   * @param url a served URL such as {@code /css/pages/bank/bank-grants.css}
   * @param resources the resources root holding {@code static/} and {@code META-INF/resources/}
   * @return {@code true} when a regular file backs the URL
   */
  public static boolean resolves(@NotNull String url, @NotNull Path resources) {
    int slash = url.indexOf('/', 1);
    if (!url.startsWith("/") || slash < 0 || url.contains("$") || url.contains("..")) {
      return false;
    }
    List<String> roots = TREES.get(url.substring(1, slash));
    if (roots == null) {
      return false;
    }
    String relative = url.substring(slash + 1);
    return roots.stream()
        .anyMatch(root -> Files.isRegularFile(resources.resolve(root).resolve(relative)));
  }
}
