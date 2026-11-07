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

package de.greluc.krt.profit.basetool.backend.catalogue.web;

import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemSourceSystem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.P4kImportJob;
import de.greluc.krt.profit.basetool.backend.catalogue.api.P4kImportJobKind;
import de.greluc.krt.profit.basetool.backend.catalogue.api.P4kImportJobRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.P4kImportJobStatus;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipTypeRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.P4kImportChangeDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.P4kImportJobPayload;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.P4kImportJobPayloadRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.P4kImportJobRunner;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.P4kImportJobService;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.P4kImportResultDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.P4kImportService;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.P4kPreviewReportCodec;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.P4kReviewCase;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.P4kReviewCaseRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.P4kReviewReason;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.P4kTestCatalogues;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kCompression;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kKind;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

/**
 * The P4K run list, the change reports, the review list and the mass-guard box over HTTP
 * (REQ-P4K-012, REQ-P4K-014…016).
 */
@SpringBootTest
@Transactional
class P4kImportReportControllerMvcTest {

  private static final String BASE = "/api/v1/catalog/admin/import/p4k";

  @Autowired private WebApplicationContext context;
  @Autowired private P4kImportService importService;
  @Autowired private P4kImportJobRepository jobRepository;
  @Autowired private P4kImportJobPayloadRepository payloadRepository;
  @Autowired private P4kImportJobService jobService;
  @Autowired private P4kPreviewReportCodec reportCodec;
  @Autowired private ShipTypeRepository shipTypeRepository;
  @Autowired private P4kReviewCaseRepository caseRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  /** The background runner stays out of the test transaction. */
  @MockitoBean private P4kImportJobRunner jobRunner;

  private MockMvc mockMvc;

  /** The signed-in admin; a real {@code app_user} row, since audit events name their actor. */
  private final UUID adminId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    jdbcTemplate.update(
        "INSERT INTO app_user (id, username, version) VALUES (?, ?, 0)",
        adminId,
        "p4k-mvc-" + adminId);
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  private MockHttpServletRequestBuilder asAdmin(MockHttpServletRequestBuilder request) {
    return request.with(
        jwt()
            .jwt(token -> token.subject(adminId.toString()))
            .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")));
  }

  private UUID apply() {
    P4kImportJob job = job(P4kImportJobKind.APPLY);
    P4kImportResultDto result =
        importService.applyImport(
            P4kCompression.toStoredPayload(P4kTestCatalogues.fixtureBytes()),
            new P4kImportService.P4kApplyRequest(job.getId(), job.getCreatedBy(), false, false));
    return result.runId();
  }

  private P4kImportJob job(P4kImportJobKind kind) {
    P4kImportJob job = new P4kImportJob();
    job.setKind(kind);
    job.setStatus(P4kImportJobStatus.SUCCEEDED);
    job.setCreatedBy(UUID.randomUUID());
    return jobRepository.save(job);
  }

  @Test
  void theApplyBox_reachesTheJob() throws Exception {
    P4kImportJob preview = job(P4kImportJobKind.PREVIEW);
    P4kImportJobPayload payload = new P4kImportJobPayload();
    payload.setJobId(preview.getId());
    payload.setContent(P4kCompression.toStoredPayload(P4kTestCatalogues.fixtureBytes()));
    payloadRepository.saveAndFlush(payload);

    mockMvc
        .perform(
            asAdmin(
                post(BASE + "/jobs/" + preview.getId() + "/apply")
                    .param("overrideMassGuard", "true")))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.overrideMassGuard").value(true))
        .andExpect(jsonPath("$.createItemsAndShips").value(false));
    verify(jobRunner).run(any(), eq(P4kImportJobKind.APPLY), any());
  }

  @Test
  void theRunList_andARunsReport_filteredByKindAndType() throws Exception {
    UUID runId = apply();

    mockMvc
        .perform(asAdmin(get(BASE + "/runs")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[*].id", hasItem(runId.toString())));
    mockMvc
        .perform(
            asAdmin(
                get(BASE + "/runs/" + runId + "/changes")
                    .param("kind", "SHIP")
                    .param("type", "UNMATCHED")
                    .param("size", "2")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()", is(2)))
        .andExpect(jsonPath("$.content[*].kind", everyItem(is("SHIP"))))
        .andExpect(jsonPath("$.content[*].changeType", everyItem(is("UNMATCHED"))));
  }

  @Test
  void aPreviewsReport_isReadFromItsJob() throws Exception {
    P4kImportJob preview = job(P4kImportJobKind.PREVIEW);
    P4kImportService.P4kPreview computed =
        importService.previewImport(
            P4kCompression.toStoredPayload(P4kTestCatalogues.fixtureBytes()));
    P4kImportJobPayload payload = new P4kImportJobPayload();
    payload.setJobId(preview.getId());
    payload.setContent(new byte[] {0});
    payloadRepository.saveAndFlush(payload);
    jobService.storePreviewReport(
        preview.getId(),
        reportCodec.encode(computed.changes().stream().map(P4kImportChangeDto::of).toList()));

    mockMvc
        .perform(asAdmin(get(BASE + "/jobs/" + preview.getId() + "/changes").param("kind", "ITEM")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[*].kind", everyItem(is("ITEM"))))
        .andExpect(
            jsonPath(
                "$.totalElements",
                is(
                    (int)
                        computed.changes().stream()
                            .filter(c -> c.getKind() == P4kKind.ITEM)
                            .count())));
  }

  @Test
  void theReviewList_resolvesACase_andAStaleVersionIsAConflict() throws Exception {
    ShipType seeded = new ShipType();
    seeded.setName("MVC seeded AI Hornet");
    seeded.setClassName(P4kTestCatalogues.AI_SHIP_CLASS);
    seeded.setSourceSystems(GameItemSourceSystem.P4K);
    shipTypeRepository.save(seeded);
    apply();
    P4kReviewCase reviewCase =
        caseRepository
            .findByKindAndGuidAndReason(
                P4kKind.SHIP, P4kTestCatalogues.AI_SHIP, P4kReviewReason.SEEDED_NOT_CREATABLE)
            .orElseThrow();

    mockMvc
        .perform(asAdmin(get(BASE + "/review-cases").param("size", "500")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[*].id", hasItem(reviewCase.getId().toString())));
    mockMvc
        .perform(
            asAdmin(
                post(BASE + "/review-cases/" + reviewCase.getId() + "/resolve")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"resolution\":\"HIDE\",\"version\":"
                            + (reviewCase.getVersion() + 1)
                            + "}")))
        .andExpect(status().isConflict());
    mockMvc
        .perform(
            asAdmin(
                post(BASE + "/review-cases/" + reviewCase.getId() + "/resolve")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"resolution\":\"HIDE\",\"version\":" + reviewCase.getVersion() + "}")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("REVIEWED"))
        .andExpect(jsonPath("$.resolution").value("HIDE"));
  }

  @Test
  void aMember_isRefused() throws Exception {
    mockMvc
        .perform(
            get(BASE + "/review-cases")
                .with(
                    jwt()
                        .jwt(token -> token.subject(UUID.randomUUID().toString()))
                        .authorities(new SimpleGrantedAuthority("ROLE_KRT_MEMBER"))))
        .andExpect(status().isForbidden());
  }
}
