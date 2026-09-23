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

import static org.hamcrest.MatcherAssert.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.test.web.servlet.ResultMatcher;

/**
 * Asserts on the page stylesheets a rendered page links, the way the render tests used to assert on
 * its inline {@code <style>} block.
 *
 * <p>FE-PERF-02 moved every page {@code <style>} block into {@code static/css/pages/<page>.css},
 * linked where the block stood. The render tests that pin a load-bearing selector — the
 * zero-specificity {@code .form-group input:where(…)} exclusion above all — therefore read the
 * stylesheet the response links rather than the response itself. The link is taken from the
 * rendered HTML, so the assertion still fails when a page stops linking its stylesheet, and a
 * response that links none fails outright instead of letting a {@code not(…)} matcher pass
 * vacuously.
 */
public final class PageStylesheets {

  /** A page-stylesheet href, with or without the content hash the resource chain appends. */
  private static final Pattern LINK =
      Pattern.compile("/css/pages/([A-Za-z0-9._-]+?)(?:-[0-9a-f]{32})?\\.css");

  private PageStylesheets() {}

  /**
   * A result matcher applying {@code matcher} to the concatenated page stylesheets the response
   * links.
   *
   * @param matcher the matcher for the stylesheet text
   * @return the result matcher
   */
  public static ResultMatcher content(org.hamcrest.Matcher<? super String> matcher) {
    return result -> {
      String css = linkedCss(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
      assertThat("the page stylesheets linked by the response", css, matcher);
    };
  }

  /**
   * Reads every page stylesheet a rendered page links, in link order.
   *
   * @param html the rendered page
   * @return the stylesheets' text, concatenated
   * @throws AssertionError when the page links no page stylesheet at all
   */
  public static String linkedCss(String html) {
    Matcher link = LINK.matcher(html);
    List<String> sheets = new ArrayList<>();
    while (link.find()) {
      sheets.add(read("/static/css/pages/" + link.group(1) + ".css"));
    }
    if (sheets.isEmpty()) {
      throw new AssertionError("the response links no /css/pages/ stylesheet");
    }
    return String.join("\n", sheets);
  }

  private static String read(String resource) {
    try (InputStream in = PageStylesheets.class.getResourceAsStream(resource)) {
      if (in == null) {
        throw new AssertionError("linked page stylesheet missing on the classpath: " + resource);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
