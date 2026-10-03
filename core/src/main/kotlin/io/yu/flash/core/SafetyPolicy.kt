package io.yu.flash.core

object SafetyPolicy {
    fun sameTarget(a: Partition, b: Partition): Boolean =
        a.name == b.name && a.alias == b.alias && a.device == b.device && a.identity == b.identity &&
            a.bytes == b.bytes && a.slot == b.slot && a.kind == b.kind && a.risk == b.risk && a.physical == b.physical
        // aliases are discovery/display metadata, not stable device identity.

    fun backup(target: Partition) {
        requireSafe(target.bytes > 0, "无法确定分区容量")
        requireSafe(target.device.startsWith("/dev/block/") && Regex("[0-9]+:[0-9]+").matches(target.identity), "块设备身份未知")
        requireSafe(target.risk != Risk.DATA, "禁止将用户数据分区备份回该分区承载的下载目录；请使用离线环境与独立存储")
        requireSafe(target.mounted == Truth.NO, "挂载状态不明或正在挂载；在线读取不保证一致性，拒绝")
        requireSafe(target.mapped == Truth.NO, "映射分区不在在线备份支持范围内")
    }

    fun writeTarget(target: Partition, env: Environment, settings: SafetySettings) {
        backup(target)
        requireSafe(env.toolsReady, "必要工具未通过检测")
        requireSafe(target.physical == Truth.YES && target.writable == Truth.YES, "未确认可写的独立物理分区（拒绝整盘、RPMB、只读设备）")
        val base = target.name.removeSuffix("_a").removeSuffix("_b")
        val supported = when (target.risk) {
            Risk.BOOT_CHAIN -> base in setOf("boot", "init_boot", "vendor_boot", "recovery") &&
                target.kind in setOf(ImageKind.BOOT, ImageKind.VENDOR_BOOT)
            Risk.FILESYSTEM -> base in setOf("system", "vendor", "product", "odm", "system_ext", "vendor_dlkm", "odm_dlkm", "system_dlkm") &&
                target.kind in setOf(ImageKind.EXT4, ImageKind.F2FS, ImageKind.EROFS)
            else -> false
        }
        requireSafe(supported, "仅支持已识别的 boot/recovery 或系统文件系统物理分区；数据、启动固件、未知与动态目标拒绝")
        if (target.slot != null || env.slot != null) {
            requireSafe(target.slot in listOf("a", "b") && env.slot in listOf("a", "b") && target.slot != env.slot,
                "A/B 设备只允许明确的非当前槽位，拒绝无槽别名及未知槽位")
        }
        requireSafe(env.unlocked == Truth.YES, "Bootloader 未确认解锁，Root 不能替代解锁")
        requireSafe(env.snapshotSafe == Truth.YES, "无法排除动态分区 / Virtual A/B OTA 快照；此版本拒绝")
        requireSafe(env.battery != null && env.battery >= settings.minBattery.coerceIn(50, 100), "电量不足或未知")
        requireSafe(env.temperatureC != null && env.temperatureC in 0.0..42.0, "温度超限或未知（应用策略上限 42°C）")
        requireSafe(!settings.requireCharging || env.charging == true, "请连接电源")
    }

    fun write(target: Partition, image: ImportedImage, env: Environment, settings: SafetySettings) {
        writeTarget(target, env, settings)
        requireSafe(image.kind == target.kind, "源与目标类型不一致")
        requireSafe(image.bytes > 0 && image.bytes == target.bytes, "仅接受完整等长 raw 镜像；不会补零、截断或清尾")
        requireSafe(Regex("[a-f0-9]{64}").matches(image.sha256), "镜像 SHA-256 无效")
        // AVB/rollback/vendor compatibility is NOT proved by these checks. UI requires explicit acknowledgement.
    }
}
