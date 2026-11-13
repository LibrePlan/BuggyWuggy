/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.board;

import jakarta.validation.Valid;
import org.buggywuggy.board.BoardDtos.BoardConfigRequest;
import org.buggywuggy.board.BoardDtos.BoardConfigResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The default board: the columns of every project without columns of its own. Requires the
 * ADMINISTER global permission (see SecurityConfiguration).
 */
@RestController
@RequestMapping("/api/v1/admin/board")
public class AdminBoardController {

    private final BoardService boards;

    public AdminBoardController(BoardService boards) {
        this.boards = boards;
    }

    @GetMapping
    public BoardConfigResponse config() {
        return boards.defaultConfig();
    }

    @PutMapping
    public BoardConfigResponse save(@Valid @RequestBody BoardConfigRequest request) {
        return boards.saveDefault(request);
    }
}
