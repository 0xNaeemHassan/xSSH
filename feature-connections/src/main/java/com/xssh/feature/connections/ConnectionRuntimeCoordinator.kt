package com.xssh.feature.connections

/** Stops process-scoped work that still references a profile before it is deleted. */
interface ConnectionRuntimeCoordinator {
    suspend fun stopBeforeDelete(connectionId: String)
}
