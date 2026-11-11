/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.project;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A project role (e.g. Developers). Roles are global; their members are set per project. */
@Entity
@Table(name = "project_role")
public class ProjectRole {

    public static final String ADMINISTRATORS = "Administrators";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;
    private String description;

    protected ProjectRole() {
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
}
