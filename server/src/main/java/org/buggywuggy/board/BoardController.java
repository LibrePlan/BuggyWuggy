/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.board;

import jakarta.validation.Valid;
import org.buggywuggy.board.BoardDtos.BoardConfigRequest;
import org.buggywuggy.board.BoardDtos.BoardConfigResponse;
import org.buggywuggy.board.BoardDtos.BoardResponse;
import org.buggywuggy.board.BoardDtos.MoveRequest;
import org.buggywuggy.security.BuggyWuggyPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The kanban board of a project, and its columns for project administrators. */
@RestController
@RequestMapping("/api/v1/projects/{idOrKey}/board")
public class BoardController {

    private final BoardService boards;
    private final BoardMoveService moves;

    public BoardController(BoardService boards, BoardMoveService moves) {
        this.boards = boards;
        this.moves = moves;
    }

    @GetMapping
    public BoardResponse board(@AuthenticationPrincipal BuggyWuggyPrincipal user, @PathVariable String idOrKey) {
        return boards.board(user, idOrKey);
    }

    /** Moves an issue to another column, lane or place; see {@link MoveRequest}. */
    @PostMapping("/moves")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void move(@AuthenticationPrincipal BuggyWuggyPrincipal user, @PathVariable String idOrKey,
            @Valid @RequestBody MoveRequest request) {
        moves.move(user, idOrKey, request);
    }

    @GetMapping("/config")
    public BoardConfigResponse config(@AuthenticationPrincipal BuggyWuggyPrincipal user,
            @PathVariable String idOrKey) {
        return boards.projectConfig(user, idOrKey);
    }

    /** Gives the project columns of its own. Requires ADMINISTER_PROJECT. */
    @PutMapping("/config")
    public BoardConfigResponse saveConfig(@AuthenticationPrincipal BuggyWuggyPrincipal user,
            @PathVariable String idOrKey, @Valid @RequestBody BoardConfigRequest request) {
        return boards.saveProject(user, idOrKey, request);
    }

    /** The project uses the default board again. */
    @DeleteMapping("/config")
    public BoardConfigResponse resetConfig(@AuthenticationPrincipal BuggyWuggyPrincipal user,
            @PathVariable String idOrKey) {
        return boards.resetProject(user, idOrKey);
    }
}
