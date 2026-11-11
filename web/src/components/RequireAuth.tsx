// BuggyWuggy — open source, self-hosted issue tracker
// Copyright (C) 2026 The BuggyWuggy contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { Center, Loader } from '@mantine/core';
import { Navigate, Outlet, useLocation } from 'react-router';
import { useMyself, useSetupStatus } from '../api/queries';
import { ErrorAlert } from './QueryState';

/** Shows the child routes only to a logged-in user; otherwise goes to setup or login. */
export function RequireAuth() {
  const location = useLocation();
  const setup = useSetupStatus();
  const myself = useMyself();

  if (setup.isPending || myself.isPending) {
    return (
      <Center h="100vh">
        <Loader />
      </Center>
    );
  }
  if (setup.isError || myself.isError) {
    return <ErrorAlert error={setup.error ?? myself.error} m="xl" />;
  }
  if (setup.data.setupRequired) return <Navigate to="/setup" replace />;
  if (!myself.data) {
    const next = location.pathname + location.search;
    return <Navigate to={`/login?next=${encodeURIComponent(next)}`} replace />;
  }
  return <Outlet />;
}
