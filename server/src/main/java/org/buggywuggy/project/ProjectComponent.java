/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.project;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.buggywuggy.user.UserAccount;

/** A component of a project (table {@code component}). */
@Entity
@Table(name = "component")
public class ProjectComponent {

    public enum DefaultAssignee { PROJECT_DEFAULT, UNASSIGNED, PROJECT_LEAD, COMPONENT_LEAD }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private long projectId;
    private String name;
    private String description;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lead_id")
    private UserAccount lead;

    private String defaultAssignee = DefaultAssignee.PROJECT_DEFAULT.name();

    protected ProjectComponent() {
    }

    public ProjectComponent(long projectId, String name) {
        this.projectId = projectId;
        this.name = name;
    }

    public Long getId() { return id; }
    public long getProjectId() { return projectId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public UserAccount getLead() { return lead; }
    public void setLead(UserAccount lead) { this.lead = lead; }
    public DefaultAssignee getDefaultAssignee() { return DefaultAssignee.valueOf(defaultAssignee); }
    public void setDefaultAssignee(DefaultAssignee value) { this.defaultAssignee = value.name(); }
}
