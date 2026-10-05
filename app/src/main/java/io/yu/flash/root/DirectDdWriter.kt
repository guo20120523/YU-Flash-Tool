package io.yu.flash.root

import io.yu.flash.core.*

/** No preflight, forced backup, helper process, retry or readback in the direct-dd route. */
internal class DirectDdWriter(private val shell: RootExecutor) : DirectWriter {
    override suspend fun copy(target: Partition, image: SelectedImage): CommandOutcome {
        val result = shell.run(DirectDdCommand.copy(image.file.path, target.device), 18000)
        return CommandOutcome(result.code, result.error)
    }
    override suspend fun sync(): CommandOutcome {
        val result = shell.run(DirectDdCommand.SYNC, 18000)
        return CommandOutcome(result.code, result.error)
    }
}
