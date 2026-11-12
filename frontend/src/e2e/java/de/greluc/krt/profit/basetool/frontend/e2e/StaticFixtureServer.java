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

package de.greluc.krt.profit.basetool.frontend.e2e;

import com.microsoft.playwright.Route;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jetbrains.annotations.NotNull;

/**
 * Answers a Playwright route with a fixture page at {@code /} and the checkout's static assets
 * below it, so a layout check runs against the real stylesheets and scripts without a stack.
 */
final class StaticFixtureServer {

  /** The frontend module's {@code src/main/resources}, whichever directory Gradle runs from. */
  static final Path RESOURCES = resources();

  /** The static asset root; a request that resolves outside it is answered with 404. */
  private static final Path ASSETS = RESOURCES.resolve("static").toAbsolutePath().normalize();

  private StaticFixtureServer() {}

  /**
   * Answers one request: {@code html} for {@code /}, the static file for any other path, and 404
   * for a path that is missing or leaves the asset root.
   *
   * @param route the intercepted request
   * @param html the fixture page
   */
  static void serve(@NotNull Route route, @NotNull String html) {
    String path = URI.create(route.request().url()).getPath();
    if ("/".equals(path)) {
      route.fulfill(new Route.FulfillOptions().setContentType("text/html").setBody(html));
      return;
    }
    Path file = ASSETS.resolve(path.substring(1)).normalize();
    if (!file.startsWith(ASSETS) || !Files.isRegularFile(file)) {
      route.fulfill(new Route.FulfillOptions().setStatus(404));
      return;
    }
    route.fulfill(new Route.FulfillOptions().setContentType(contentType(path)).setPath(file));
  }

  /**
   * The media type of a static asset, by extension.
   *
   * @param path the request path
   * @return {@code text/css}, {@code application/javascript} or {@code font/woff2}
   */
  private static @NotNull String contentType(@NotNull String path) {
    if (path.endsWith(".css")) {
      return "text/css";
    }
    return path.endsWith(".js") ? "application/javascript" : "font/woff2";
  }

  /**
   * Resolves {@code src/main/resources} from the module directory or the repository root.
   *
   * @return the resources directory
   */
  private static @NotNull Path resources() {
    Path module = Path.of("src/main/resources");
    return Files.isDirectory(module) ? module : Path.of("frontend").resolve(module);
  }
}
