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

package de.greluc.krt.profit.basetool.frontend.promotion.web;

import de.greluc.krt.profit.basetool.frontend.kernel.security.Roles;
import de.greluc.krt.profit.basetool.frontend.promotion.client.PromotionBackendClient;
import de.greluc.krt.profit.basetool.frontend.promotion.model.MemberEvaluationDto;
import de.greluc.krt.profit.basetool.frontend.promotion.model.MemberEvaluationUpdateRequest;
import de.greluc.krt.profit.basetool.frontend.promotion.model.PromotionCategoryDto;
import de.greluc.krt.profit.basetool.frontend.promotion.model.PromotionCategoryWriteRequest;
import de.greluc.krt.profit.basetool.frontend.promotion.model.PromotionLevelContentDto;
import de.greluc.krt.profit.basetool.frontend.promotion.model.PromotionLevelContentWriteRequest;
import de.greluc.krt.profit.basetool.frontend.promotion.model.PromotionTopicDto;
import de.greluc.krt.profit.basetool.frontend.promotion.model.PromotionTopicWriteRequest;
import de.greluc.krt.profit.basetool.frontend.promotion.model.RankRequirementDto;
import de.greluc.krt.profit.basetool.frontend.promotion.model.RankRequirementWriteRequest;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Same-origin proxy through which the promotion admin pages reach the backend's {@code
 * /api/v1/promotion/...} endpoints via {@link PromotionBackendClient}; restricted to ADMIN and
 * OFFICER.
 */
@RestController
@RequestMapping("/api/proxy/promotion")
@RequiredArgsConstructor
public class PromotionProxyController {

  /** Sends the promotion writes to the backend. */
  private final PromotionBackendClient promotionClient;

  /**
   * Forwards a "create promotion topic" request to the backend.
   *
   * @param body the validated topic payload sent by the browser
   * @return the backend's response body, typically the created topic representation
   */
  @PostMapping("/topics")
  @PreAuthorize(Roles.ADMIN_OR_OFFICER)
  public PromotionTopicDto createTopic(@RequestBody @NotNull PromotionTopicWriteRequest body) {
    return promotionClient.createTopic(body);
  }

  /**
   * Forwards an "update promotion topic" request to the backend.
   *
   * @param id the persistent id of the topic to update
   * @param body the validated topic payload including the version for optimistic locking
   * @return the backend's response body for the updated topic
   */
  @PutMapping("/topics/{id}")
  @PreAuthorize(Roles.ADMIN_OR_OFFICER)
  public PromotionTopicDto updateTopic(
      @PathVariable @NotNull UUID id, @RequestBody @NotNull PromotionTopicWriteRequest body) {
    return promotionClient.updateTopic(id, body);
  }

  /**
   * Forwards a "delete promotion topic" request to the backend.
   *
   * @param id the persistent id of the topic to delete
   * @return {@code 204 No Content} on success
   */
  @DeleteMapping("/topics/{id}")
  @PreAuthorize(Roles.ADMIN_OR_OFFICER)
  public ResponseEntity<Void> deleteTopic(@PathVariable @NotNull UUID id) {
    promotionClient.deleteTopic(id);
    return ResponseEntity.noContent().build();
  }

  /**
   * Forwards a "create promotion category" request to the backend.
   *
   * @param body the validated category payload sent by the browser
   * @return the backend's response body, typically the created category representation
   */
  @PostMapping("/categories")
  @PreAuthorize(Roles.ADMIN_OR_OFFICER)
  public PromotionCategoryDto createCategory(
      @RequestBody @NotNull PromotionCategoryWriteRequest body) {
    return promotionClient.createCategory(body);
  }

  /**
   * Forwards an "update promotion category" request to the backend.
   *
   * @param id the persistent id of the category to update
   * @param body the validated category payload including the version for optimistic locking
   * @return the backend's response body for the updated category
   */
  @PutMapping("/categories/{id}")
  @PreAuthorize(Roles.ADMIN_OR_OFFICER)
  public PromotionCategoryDto updateCategory(
      @PathVariable @NotNull UUID id, @RequestBody @NotNull PromotionCategoryWriteRequest body) {
    return promotionClient.updateCategory(id, body);
  }

  /**
   * Forwards a "delete promotion category" request to the backend.
   *
   * @param id the persistent id of the category to delete
   * @return {@code 204 No Content} on success
   */
  @DeleteMapping("/categories/{id}")
  @PreAuthorize(Roles.ADMIN_OR_OFFICER)
  public ResponseEntity<Void> deleteCategory(@PathVariable @NotNull UUID id) {
    promotionClient.deleteCategory(id);
    return ResponseEntity.noContent().build();
  }

  /**
   * Forwards a "create rank requirement" request to the backend.
   *
   * @param body the validated requirement payload sent by the browser
   * @return the backend's response body, typically the created requirement representation
   */
  @PostMapping("/rank-requirements")
  @PreAuthorize(Roles.ADMIN_OR_OFFICER)
  public RankRequirementDto createRankRequirement(
      @RequestBody @NotNull RankRequirementWriteRequest body) {
    return promotionClient.createRankRequirement(body);
  }

  /**
   * Forwards an "update rank requirement" request to the backend.
   *
   * @param id the persistent id of the requirement to update
   * @param body the validated requirement payload including the version for optimistic locking
   * @return the backend's response body for the updated requirement
   */
  @PutMapping("/rank-requirements/{id}")
  @PreAuthorize(Roles.ADMIN_OR_OFFICER)
  public RankRequirementDto updateRankRequirement(
      @PathVariable @NotNull UUID id, @RequestBody @NotNull RankRequirementWriteRequest body) {
    return promotionClient.updateRankRequirement(id, body);
  }

  /**
   * Forwards a "delete rank requirement" request to the backend.
   *
   * @param id the persistent id of the requirement to delete
   * @return {@code 204 No Content} on success
   */
  @DeleteMapping("/rank-requirements/{id}")
  @PreAuthorize(Roles.ADMIN_OR_OFFICER)
  public ResponseEntity<Void> deleteRankRequirement(@PathVariable @NotNull UUID id) {
    promotionClient.deleteRankRequirement(id);
    return ResponseEntity.noContent().build();
  }

  /**
   * Forwards a "create level content" request to the backend.
   *
   * @param body the validated level-content payload sent by the browser
   * @return the backend's response body, typically the created entry representation
   */
  @PostMapping("/level-contents")
  @PreAuthorize(Roles.ADMIN_OR_OFFICER)
  public PromotionLevelContentDto createLevelContent(
      @RequestBody @NotNull PromotionLevelContentWriteRequest body) {
    return promotionClient.createLevelContent(body);
  }

  /**
   * Forwards an "update level content" request to the backend.
   *
   * @param id the persistent id of the entry to update
   * @param body the validated payload including the version for optimistic locking
   * @return the backend's response body for the updated entry
   */
  @PutMapping("/level-contents/{id}")
  @PreAuthorize(Roles.ADMIN_OR_OFFICER)
  public PromotionLevelContentDto updateLevelContent(
      @PathVariable @NotNull UUID id,
      @RequestBody @NotNull PromotionLevelContentWriteRequest body) {
    return promotionClient.updateLevelContent(id, body);
  }

  /**
   * Forwards a "delete level content" request to the backend.
   *
   * @param id the persistent id of the entry to delete
   * @return {@code 204 No Content} on success
   */
  @DeleteMapping("/level-contents/{id}")
  @PreAuthorize(Roles.ADMIN_OR_OFFICER)
  public ResponseEntity<Void> deleteLevelContent(@PathVariable @NotNull UUID id) {
    promotionClient.deleteLevelContent(id);
    return ResponseEntity.noContent().build();
  }

  /**
   * Forwards an upsert of a member's evaluation in one category to the backend.
   *
   * @param userId the evaluated member's JWT subject
   * @param categoryId the category the evaluation applies to
   * @param body the validated payload including the optimistic-lock version
   * @return the backend's response for the upserted evaluation
   */
  @PutMapping("/evaluations/user/{userId}/category/{categoryId}")
  @PreAuthorize(Roles.ADMIN_OR_OFFICER)
  public MemberEvaluationDto updateEvaluation(
      @PathVariable @NotNull UUID userId,
      @PathVariable @NotNull UUID categoryId,
      @RequestBody @NotNull MemberEvaluationUpdateRequest body) {
    return promotionClient.upsertEvaluation(userId, categoryId, body);
  }
}
