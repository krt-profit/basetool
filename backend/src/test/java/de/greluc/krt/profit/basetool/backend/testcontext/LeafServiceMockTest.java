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

package de.greluc.krt.profit.basetool.backend.testcontext;

import de.greluc.krt.profit.basetool.backend.integration.UexClient;
import de.greluc.krt.profit.basetool.backend.service.DataExportReportService;
import de.greluc.krt.profit.basetool.backend.service.DataExportService;
import de.greluc.krt.profit.basetool.backend.service.DeletionRequestService;
import de.greluc.krt.profit.basetool.backend.service.MaterialExternalAliasService;
import de.greluc.krt.profit.basetool.backend.service.PersonSearchService;
import de.greluc.krt.profit.basetool.backend.service.RefineryImportService;
import de.greluc.krt.profit.basetool.backend.service.SyncReportService;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Boots the whole application with one agreed set of leaf-service mocks, so every test that mocks
 * some of them shares a single cached context (REQ-OPS-041).
 *
 * <p>A test obtains a mock with {@code @Autowired} and stubs it explicitly. Only a bean that no
 * security bean, filter, converter or argument resolver reaches may join the set; {@code
 * LeafServiceMockSecurityTest} fails the build otherwise.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@SpringBootTest
@MockitoBean(
    types = {
      DataExportReportService.class,
      DataExportService.class,
      DeletionRequestService.class,
      MaterialExternalAliasService.class,
      PersonSearchService.class,
      RefineryImportService.class,
      SyncReportService.class,
      UexClient.class
    })
public @interface LeafServiceMockTest {}
