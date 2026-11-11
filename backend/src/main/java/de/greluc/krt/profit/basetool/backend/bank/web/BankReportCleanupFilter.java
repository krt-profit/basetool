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

package de.greluc.krt.profit.basetool.backend.bank.web;

import de.greluc.krt.profit.basetool.backend.bank.internal.BankPdfExport;
import de.greluc.krt.profit.basetool.backend.platform.api.PdfResponses;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Releases private Bank export files after synchronous response conversion, including disconnects.
 */
@Component
public class BankReportCleanupFilter extends OncePerRequestFilter {
  private static final String EXPORT_ATTRIBUTE =
      BankReportCleanupFilter.class.getName() + ".export";

  /**
   * Attaches a completed export to its request lifecycle before response conversion begins.
   *
   * @param request the serving request
   * @param export the reopenable PDF and its private storage
   * @param filename browser download filename
   * @return the PDF attachment response
   */
  public static ResponseEntity<Resource> attachment(
      @NotNull HttpServletRequest request,
      @NotNull BankPdfExport export,
      @NotNull String filename) {
    if (request.getAttribute(EXPORT_ATTRIBUTE) != null) {
      export.close();
      throw new IllegalStateException("One request cannot own multiple Bank PDF exports");
    }
    request.setAttribute(EXPORT_ATTRIBUTE, export);
    return PdfResponses.pdfAttachment(export, filename);
  }

  /** Closes the request-owned export after conversion or any filter-chain failure. */
  @Override
  protected void doFilterInternal(
      @NotNull HttpServletRequest request,
      @NotNull HttpServletResponse response,
      @NotNull FilterChain chain)
      throws ServletException, IOException {
    try (var _ = new RequestExportScope(request)) {
      chain.doFilter(request, response);
    }
  }

  /** The export currently owned by a request, acquired during handler execution. */
  @RequiredArgsConstructor
  private static final class RequestExportScope implements AutoCloseable {
    private final HttpServletRequest request;

    /** Releases the resource acquired by the request while its handler ran. */
    @Override
    public void close() {
      if (request.getAttribute(EXPORT_ATTRIBUTE) instanceof BankPdfExport export) {
        request.removeAttribute(EXPORT_ATTRIBUTE);
        export.close();
      }
    }
  }
}
