/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.user;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import org.buggywuggy.common.PageResponse;
import org.buggywuggy.user.GroupResponse.CreateGroupRequest;
import org.buggywuggy.user.GroupResponse.UpdateGroupRequest;
import org.buggywuggy.user.UserDtos.UserResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Group administration. Requires the ADMINISTER global permission (see SecurityConfiguration). */
@RestController
@RequestMapping("/api/v1/admin/groups")
public class AdminGroupController {

    private final GroupService groupService;

    public AdminGroupController(GroupService groupService) {
        this.groupService = groupService;
    }

    @GetMapping
    public PageResponse<GroupResponse> list(
            @RequestParam(required = false) String query,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        return groupService.list(query, page, size);
    }

    @PostMapping
    public ResponseEntity<GroupResponse> create(@Valid @RequestBody CreateGroupRequest request) {
        GroupResponse created = groupService.create(request);
        return ResponseEntity.created(URI.create("/api/v1/admin/groups/" + created.id())).body(created);
    }

    @GetMapping("/{id}")
    public GroupResponse get(@PathVariable long id) {
        return groupService.get(id);
    }

    @PatchMapping("/{id}")
    public GroupResponse update(@PathVariable long id, @Valid @RequestBody UpdateGroupRequest request) {
        return groupService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        groupService.delete(id);
    }

    @GetMapping("/{id}/members")
    public PageResponse<UserResponse> members(@PathVariable long id,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        return groupService.members(id, page, size);
    }

    @PutMapping("/{id}/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void addMember(@PathVariable long id, @PathVariable long userId) {
        groupService.addMember(id, userId);
    }

    @DeleteMapping("/{id}/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeMember(@PathVariable long id, @PathVariable long userId) {
        groupService.removeMember(id, userId);
    }
}
