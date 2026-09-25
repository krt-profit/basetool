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

package de.greluc.krt.profit.basetool.backend.service;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.greluc.krt.profit.basetool.backend.model.dto.P4kImportResultDto;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Opt-in check that {@link P4kImportService#previewImport(byte[])} processes a real P4K catalog
 * without error, against Postgres.
 *
 * <p>Runs only when {@code backend/build/p4k-catalog-verify.json} exists and otherwise skips via
 * {@link Assumptions}. {@link JwtDecoder} is mocked.
 */
@SpringBootTest
@ActiveProfiles("test")
class P4kImportRealCatalogVerificationTest {

  /** Working-directory-relative, gitignored location an operator drops a real catalog into. */
  private static final Path CATALOG = Path.of("build", "p4k-catalog-verify.json");

  @Autowired private P4kImportService service;

  @MockitoBean private JwtDecoder jwtDecoder;

  @Test
  void preview_realCatalog_parsesAndReconcilesEntireFileWithoutError() throws Exception {
    Assumptions.assumeTrue(
        Files.isReadable(CATALOG),
        () ->
            "Opt-in verification skipped: drop a real P4K catalog JSON at "
                + CATALOG.toAbsolutePath()
                + " to run it.");

    byte[] bytes = Files.readAllBytes(CATALOG);
    assertTrue(bytes.length > 0, "the staged catalog must not be empty");

    P4kImportResultDto result = service.previewImport(bytes);

    assertNotNull(result, "preview returned a result");
    assertTrue(result.dryRun(), "preview is a dry run");

    int itemsTotal =
        result.items().matched() + result.items().created() + result.items().unmatched();
    assertTrue(itemsTotal > 0, "the preview classified item records from the catalog");

    System.out.println(
        "P4K real-catalog preview OK — manufacturers"
            + fmt(result.manufacturers())
            + " items"
            + fmt(result.items())
            + " ships"
            + fmt(result.ships())
            + " commodities"
            + fmt(result.commodities())
            + " blueprints"
            + fmt(result.blueprints())
            + " ingredientsResolved="
            + result.ingredientsResolved());
  }

  private static String fmt(P4kImportResultDto.Counts c) {
    return "[m=" + c.matched() + ",cr=" + c.created() + ",un=" + c.unmatched() + "]";
  }
}
