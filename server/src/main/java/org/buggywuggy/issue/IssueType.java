/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.issue;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Admin-editable issue type. {@code hierarchyLevel}: 1 = epic, 0 = standard, -1 = sub-task. */
@Entity
@Table(name = "issue_type")
public class IssueType {

    public static final int EPIC = 1;
    public static final int STANDARD = 0;
    public static final int SUBTASK = -1;

    @Id
    private Long id;
    private String name;
    private String description;
    private String icon;
    private short hierarchyLevel;
    private int position;

    protected IssueType() {
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getIcon() { return icon; }
    public int getHierarchyLevel() { return hierarchyLevel; }
    public int getPosition() { return position; }
    public boolean isSubtask() { return hierarchyLevel == SUBTASK; }
}
