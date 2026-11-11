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
import org.buggywuggy.user.UserGroup;

/** Membership of a user or a group in a project role, for one project. */
@Entity
@Table(name = "project_role_actor")
public class ProjectRoleActor {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private long projectId;
    private long projectRoleId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private UserAccount user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "group_id")
    private UserGroup group;

    protected ProjectRoleActor() {
    }

    public static ProjectRoleActor ofUser(long projectId, long roleId, UserAccount user) {
        ProjectRoleActor actor = new ProjectRoleActor();
        actor.projectId = projectId;
        actor.projectRoleId = roleId;
        actor.user = user;
        return actor;
    }

    public static ProjectRoleActor ofGroup(long projectId, long roleId, UserGroup group) {
        ProjectRoleActor actor = new ProjectRoleActor();
        actor.projectId = projectId;
        actor.projectRoleId = roleId;
        actor.group = group;
        return actor;
    }

    public Long getId() { return id; }
    public long getProjectId() { return projectId; }
    public long getProjectRoleId() { return projectRoleId; }
    public UserAccount getUser() { return user; }
    public UserGroup getGroup() { return group; }
}
