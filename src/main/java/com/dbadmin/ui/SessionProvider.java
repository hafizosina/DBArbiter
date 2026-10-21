package com.dbadmin.ui;

import com.dbadmin.model.ConnectionConfig;
import com.dbadmin.service.DatabaseSession;

import java.util.Optional;
import java.util.function.Consumer;

/** Hands out the open session for a connection, connecting first if needed. Callbacks run on the FX thread. */
interface SessionProvider {
    void withSession(ConnectionConfig config, Consumer<DatabaseSession> onReady, Consumer<Throwable> onError);

    /** The session if the connection is already open; never connects. */
    Optional<DatabaseSession> openSession(ConnectionConfig config);
}
