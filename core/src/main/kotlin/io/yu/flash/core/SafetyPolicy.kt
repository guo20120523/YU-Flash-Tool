package io.yu.flash.core

object SafetyPolicy {
    fun sameTarget(a: Partition, b: Partition): Boolean =
        a.name == b.name && a.alias == b.alias && a.device == b.device && a.identity == b.identity &&
            a.bytes == b.bytes && a.slot == b.slot && a.kind == b.kind
        // aliases are discovery/display metadata, not stable device identity.

    fun backup(target: Partition) {
        requireSafe(target.bytes > 0, "无法确定分区容量")
        requireSafe(target.device.startsWith("/dev/block/") && target.identity.isNotBlank(), "块设备身份未知")
        requireSafe(target.risk != Risk.DATA, "禁止将用户数据分区备份回该分区承载的下载目录；请使用离线环境与独立存储")
        requireSafe(target.mounted == Truth.NO, "挂载状态不明或正在挂载；在线读取不保证一致性，首版拒绝")
        requireSafe(target.mapped == Truth.NO, "映射分区不在首版备份支持范围内")
    }

    fun write(target: Partition, image: ImportedImage, env: Environment, settings: SafetySettings, registry: ProfileRegistry) {
        backup(target)
        requireSafe(env.toolsReady, "必要工具未通过检测")
        requireSafe(target.risk == Risk.BOOT_CHAIN, "高风险或未知目标禁止在线写入")
        requireSafe(image.kind in setOf(ImageKind.BOOT, ImageKind.VENDOR_BOOT), "首版仅为受审核 boot 类镜像提供写入接口")
        requireSafe(image.bytes > 0 && image.bytes <= target.bytes, "镜像为空或超出分区容量")
        requireSafe(image.kind == target.kind, "源与目标类型不一致")
        requireSafe(env.slot in listOf("a", "b") && target.slot in listOf("a", "b"), "非 A/B 或槽位未知：未支持")
        requireSafe(env.unlocked == Truth.YES, "Bootloader 未确认解锁，Root 不能替代解锁")
        requireSafe(env.snapshotSafe == Truth.YES, "OTA 快照状态未确认安全")
        requireSafe(env.avbCompatible == Truth.YES, "AVB / 回滚保护兼容性未确认")
        requireSafe(env.battery != null && env.battery >= settings.minBattery.coerceIn(50, 100), "电量不足或未知")
        requireSafe(env.temperatureC != null && env.temperatureC in 0.0..42.0, "温度超限或未知（应用策略上限 42°C）")
        requireSafe(!settings.requireCharging || env.charging == true, "请连接电源")
        val profile = registry.find(target, image, env) ?: throw SafetyException("没有经验证的设备适配配置；此构建不放行真实分区写入")
        requireSafe(profile.fingerprint == env.fingerprint && profile.partition == target.name &&
            profile.deviceIdentity == target.identity && profile.kind == image.kind &&
            profile.imageSha256 == image.sha256 && profile.rollbackChecked, "设备配置或镜像哈希不匹配")
        requireSafe(!profile.inactiveOnly || target.slot != env.slot, "禁止写入当前槽位")
        // Initial qualified strategy requires a complete raw partition image: no tail ambiguity.
        requireSafe(image.bytes == target.bytes, "首版写入策略只接受完整等长 raw 镜像；不会补零、截断或清尾")
    }
}
