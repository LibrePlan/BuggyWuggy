/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.issue;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "resolution")
public class Resolution {

    @Id
    private Long id;
    private String name;
    private String description;
    private int position;
    private boolean isDefault;

    protected Resolution() {
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public int getPosition() { return position; }
    public boolean isDefault() { return isDefault; }
}
