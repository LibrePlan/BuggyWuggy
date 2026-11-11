/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.project;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import org.buggywuggy.user.UserAccount;

/**
 * A project. Note: {@code issue_counter} is deliberately not mapped. It is only changed with
 * an atomic SQL update when an issue is created; mapping it would let an update of the project
 * write back a stale value and cause issue numbers to be reused.
 */
@Entity
@Table(name = "project")
public class Project {

    public static final String KEY_PATTERN = "^[A-Za-z][A-Za-z0-9_]{1,9}$";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "key")
    private String projectKey;

    private String name;
    private String description;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "lead_id")
    private UserAccount lead;

    private String defaultAssignee = DefaultAssignee.UNASSIGNED.name();
    private long permissionSchemeId;
    private long workflowSchemeId;
    private boolean archived;
    private Instant createdAt;

    @Version
    private long version;

    public enum DefaultAssignee { UNASSIGNED, PROJECT_LEAD }

    protected Project() {
    }

    public Project(String projectKey, String name, UserAccount lead, long permissionSchemeId, long workflowSchemeId,
            Instant createdAt) {
        this.projectKey = projectKey;
        this.name = name;
        this.lead = lead;
        this.permissionSchemeId = permissionSchemeId;
        this.workflowSchemeId = workflowSchemeId;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public String getProjectKey() { return projectKey; }
    public void setProjectKey(String projectKey) { this.projectKey = projectKey; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public UserAccount getLead() { return lead; }
    public void setLead(UserAccount lead) { this.lead = lead; }
    public DefaultAssignee getDefaultAssignee() { return DefaultAssignee.valueOf(defaultAssignee); }
    public void setDefaultAssignee(DefaultAssignee value) { this.defaultAssignee = value.name(); }
    public long getPermissionSchemeId() { return permissionSchemeId; }
    public long getWorkflowSchemeId() { return workflowSchemeId; }
    public boolean isArchived() { return archived; }
    public void setArchived(boolean archived) { this.archived = archived; }
    public Instant getCreatedAt() { return createdAt; }
}
