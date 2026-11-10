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

package de.greluc.krt.profit.basetool.frontend.kernel.web;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.commonmark.node.Image;
import org.commonmark.node.Node;
import org.commonmark.renderer.NodeRenderer;
import org.commonmark.renderer.html.HtmlNodeRendererContext;
import org.commonmark.renderer.html.HtmlWriter;
import org.jetbrains.annotations.NotNull;

/**
 * Renders a Markdown image as a link to the image instead of an {@code <img>}.
 *
 * <p>The Content-Security-Policy allows images only from the page's own origin, so an embedded
 * third-party image would be blocked and shown broken; a link leaves fetching it to the reader. The
 * image's alt text becomes the link text, its URL when there is none.
 */
@RequiredArgsConstructor
final class MarkdownImageLinkRenderer implements NodeRenderer {

  /** The renderer context supplying the writer, URL sanitizer and attribute extension. */
  private final HtmlNodeRendererContext context;

  /**
   * Declares the one node type this renderer replaces.
   *
   * @return the Markdown image node type
   */
  @Override
  public Set<Class<? extends Node>> getNodeTypes() {
    return Set.of(Image.class);
  }

  /**
   * Writes the image node as {@code <a href="…" rel="noopener noreferrer nofollow">alt</a>}.
   *
   * @param node the Markdown image node
   */
  @Override
  public void render(@NotNull Node node) {
    Image image = (Image) node;
    String destination = image.getDestination();
    if (context.shouldSanitizeUrls()) {
      destination = context.urlSanitizer().sanitizeLinkUrl(destination);
    }
    Map<String, String> attributes = new LinkedHashMap<>();
    attributes.put("href", context.encodeUrl(destination));
    attributes.put("rel", "noopener noreferrer nofollow");
    if (image.getTitle() != null) {
      attributes.put("title", image.getTitle());
    }
    HtmlWriter html = context.getWriter();
    html.tag("a", context.extendAttributes(image, "a", attributes));
    if (image.getFirstChild() == null) {
      html.text(image.getDestination());
    }
    for (Node child = image.getFirstChild(); child != null; child = child.getNext()) {
      context.render(child);
    }
    html.tag("/a");
  }
}
