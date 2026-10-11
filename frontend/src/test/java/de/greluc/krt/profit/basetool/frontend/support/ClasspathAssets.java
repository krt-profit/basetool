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

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.jetbrains.annotations.NotNull;

/**
 * Locates the shipped template, script and stylesheet trees on the test classpath, anchored on one
 * file of the kernel folder of each tree, so a guard keeps finding its subject when files move
 * between domain folders.
 */
public final class ClasspathAssets {

  private static final String TEMPLATE_ANCHOR = "/templates/kernel/fragments/head.html";

  private static final String SCRIPT_ANCHOR = "/static/js/kernel/krt-fetch.js";

  private static final String STYLESHEET_ANCHOR = "/static/css/kernel/styles.css";

  /** Not instantiable. */
  private ClasspathAssets() {}

  /**
   * The template root, {@code templates/}, of the main resources.
   *
   * @return the directory holding the domain folders of the templates
   */
  public static @NotNull Path templates() {
    return up(anchor(TEMPLATE_ANCHOR), 3);
  }

  /**
   * The script root, {@code static/js/}, of the main resources.
   *
   * @return the directory holding the domain folders of the scripts
   */
  public static @NotNull Path scripts() {
    return up(anchor(SCRIPT_ANCHOR), 2);
  }

  /**
   * The stylesheet root, {@code static/css/}, of the main resources.
   *
   * @return the directory holding the domain folders and {@code pages/} of the stylesheets
   */
  public static @NotNull Path stylesheets() {
    return up(anchor(STYLESHEET_ANCHOR), 2);
  }

  /**
   * The main resources root, the directory holding {@code static/} and {@code templates/}.
   *
   * @return the resources root on the test classpath
   */
  public static @NotNull Path resources() {
    return up(anchor(STYLESHEET_ANCHOR), 4);
  }

  /**
   * The root of the tree a classpath file belongs to: its nearest ancestor named {@code templates},
   * {@code js} or {@code css}.
   *
   * @param anchor a classpath URL of a file below one of the trees
   * @return the tree's root directory
   */
  public static @NotNull Path rootOf(@NotNull URL anchor) {
    try {
      for (Path dir = Paths.get(anchor.toURI()).getParent(); dir != null; dir = dir.getParent()) {
        String name = String.valueOf(dir.getFileName());
        if ("templates".equals(name) || "js".equals(name) || "css".equals(name)) {
          return dir;
        }
      }
    } catch (URISyntaxException ex) {
      throw new IllegalStateException(ex);
    }
    throw new IllegalStateException("no asset tree above " + anchor);
  }

  private static @NotNull Path anchor(@NotNull String resource) {
    URL url = ClasspathAssets.class.getResource(resource);
    assertThat(url).as("%s classpath resource", resource).isNotNull();
    try {
      return Paths.get(url.toURI());
    } catch (URISyntaxException ex) {
      throw new IllegalStateException(ex);
    }
  }

  private static @NotNull Path up(@NotNull Path file, int levels) {
    Path path = file;
    for (int i = 0; i < levels; i++) {
      path = path.getParent();
    }
    return path;
  }
}
