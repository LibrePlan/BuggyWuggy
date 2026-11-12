// BuggyWuggy — open source, self-hosted issue tracker
// Copyright (C) 2026 The BuggyWuggy contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { createBrowserRouter, Navigate } from 'react-router';
import { AppLayout } from './components/AppLayout';
import { RequireAuth } from './components/RequireAuth';
import { RouteError } from './components/RouteError';
import { IssuePage } from './pages/IssuePage';
import { LoginPage } from './pages/LoginPage';
import { AdminGroupPage } from './pages/admin/AdminGroupPage';
import { AdminGroupsPage } from './pages/admin/AdminGroupsPage';
import { AdminLayout } from './pages/admin/AdminLayout';
import { AdminUserPage } from './pages/admin/AdminUserPage';
import { AdminUsersPage } from './pages/admin/AdminUsersPage';
import { GlobalPermissionsPage } from './pages/admin/GlobalPermissionsPage';
import { NotFoundPage } from './pages/NotFoundPage';
import { ProfilePage } from './pages/ProfilePage';
import { ProjectPage } from './pages/ProjectPage';
import { ProjectSettingsPage } from './pages/ProjectSettingsPage';
import { ProjectsPage } from './pages/ProjectsPage';
import { SearchPage } from './pages/SearchPage';
import { SetupPage } from './pages/SetupPage';

export const router = createBrowserRouter([
  { path: '/setup', element: <SetupPage />, errorElement: <RouteError /> },
  { path: '/login', element: <LoginPage />, errorElement: <RouteError /> },
  {
    element: <RequireAuth />,
    errorElement: <RouteError />,
    children: [
      {
        element: <AppLayout />,
        children: [
          { index: true, element: <Navigate to="/projects" replace /> },
          { path: '/projects', element: <ProjectsPage /> },
          { path: '/projects/:projectKey', element: <ProjectPage /> },
          { path: '/projects/:projectKey/settings', element: <ProjectSettingsPage /> },
          { path: '/profile', element: <ProfilePage /> },
          {
            path: '/admin',
            element: <AdminLayout />,
            children: [
              { index: true, element: <Navigate to="/admin/users" replace /> },
              { path: 'users', element: <AdminUsersPage /> },
              { path: 'users/:userId', element: <AdminUserPage /> },
              { path: 'groups', element: <AdminGroupsPage /> },
              { path: 'groups/:groupId', element: <AdminGroupPage /> },
              { path: 'permissions', element: <GlobalPermissionsPage /> },
            ],
          },
          { path: '/browse/:issueKey', element: <IssuePage /> },
          { path: '/issues', element: <SearchPage /> },
          { path: '*', element: <NotFoundPage /> },
        ],
      },
    ],
  },
]);
