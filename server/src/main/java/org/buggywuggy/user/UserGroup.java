/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.user;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "user_group")
public class UserGroup {

    /** Built-in group whose members administer the installation. */
    public static final String ADMINISTRATORS = "administrators";
    /** Built-in group that every new user joins. */
    public static final String USERS = "users";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;
    private String description;

    @ManyToMany(mappedBy = "groups")
    private Set<UserAccount> members = new HashSet<>();

    protected UserGroup() {
    }

    public UserGroup(String name, String description) {
        this.name = name;
        this.description = description;
    }

    public boolean isBuiltIn() {
        return ADMINISTRATORS.equalsIgnoreCase(name) || USERS.equalsIgnoreCase(name);
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Set<UserAccount> getMembers() { return members; }
}
