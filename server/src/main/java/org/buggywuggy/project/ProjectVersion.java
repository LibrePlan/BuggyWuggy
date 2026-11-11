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
import java.time.LocalDate;

/** A version (release) of a project. */
@Entity
@Table(name = "project_version")
public class ProjectVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private long projectId;
    private String name;
    private String description;
    private LocalDate startDate;
    private LocalDate releaseDate;
    private boolean released;
    private boolean archived;
    private int position;

    protected ProjectVersion() {
    }

    public ProjectVersion(long projectId, String name, int position) {
        this.projectId = projectId;
        this.name = name;
        this.position = position;
    }

    public Long getId() { return id; }
    public long getProjectId() { return projectId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate startDate) { this.startDate = startDate; }
    public LocalDate getReleaseDate() { return releaseDate; }
    public void setReleaseDate(LocalDate releaseDate) { this.releaseDate = releaseDate; }
    public boolean isReleased() { return released; }
    public void setReleased(boolean released) { this.released = released; }
    public boolean isArchived() { return archived; }
    public void setArchived(boolean archived) { this.archived = archived; }
    public int getPosition() { return position; }
    public void setPosition(int position) { this.position = position; }
}
