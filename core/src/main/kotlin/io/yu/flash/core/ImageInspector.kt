package io.yu.flash.core

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

object ImageInspector {
    private fun readHeader(input: InputStream): ByteArray {
        val buffer = ByteArray(4096)
        var offset = 0
        while (offset < buffer.size) {
            val count = input.read(buffer, offset, buffer.size - offset)
            if (count < 0) break
            if (count == 0) continue
            offset += count
        }
        return buffer.copyOf(offset)
    }
    fun detect(header: ByteArray): ImageKind {
        fun magic(offset: Int, vararg values: Int) = header.size >= offset + values.size &&
            values.indices.all { (header[offset + it].toInt() and 255) == values[it] }
        fun text(value: String) = header.take(value.length).toByteArray().contentEquals(value.toByteArray(Charsets.US_ASCII))
        return when {
            magic(0, 0x3a, 0xff, 0x26, 0xed) -> ImageKind.SPARSE
            magic(0, 0x50, 0x4b) || magic(0, 0x1f, 0x8b) || magic(0, 0xfd, 0x37, 0x7a, 0x58, 0x5a) ||
                magic(0, 0x28, 0xb5, 0x2f, 0xfd) || text("7z") || text("BZh") || text("Rar!") ||
                (header.size >= 262 && String(header, 257, 5, Charsets.US_ASCII) == "ustar") -> ImageKind.ARCHIVE
            text("ANDROID!") -> ImageKind.BOOT
            text("VNDRBOOT") -> ImageKind.VENDOR_BOOT
            text("AVB0") || text("AVBf") -> ImageKind.AVB
            magic(1080, 0x53, 0xef) -> ImageKind.EXT4
            magic(1024, 0x10, 0x20, 0xf5, 0xf2) -> ImageKind.F2FS
            magic(1024, 0xe2, 0xe1, 0xf5, 0xe0) -> ImageKind.EROFS
            else -> ImageKind.UNKNOWN
        }
    }
    fun sha256(file: File): String = file.inputStream().use { hash(it) }
    fun hash(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1024 * 1024)
        while (true) { val n = input.read(buffer); if (n < 0) break; if (n > 0) digest.update(buffer, 0, n) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    fun inspect(file: File, name: String): ImportedImage {
        requireSafe(file.isFile && file.length() > 0, "镜像为空或导入未完成")
        val kind = file.inputStream().use { detect(readHeader(it)) }
        requireSafe(kind != ImageKind.SPARSE, "Android sparse 不能直接 dd 写入；首版不转换")
        requireSafe(kind != ImageKind.ARCHIVE, "不支持压缩包、压缩镜像或 OTA 包")
        requireSafe(kind != ImageKind.UNKNOWN, "镜像签名未识别，拒绝写入")
        if (kind == ImageKind.BOOT || kind == ImageKind.VENDOR_BOOT) validateBoot(file, kind)
        return ImportedImage(file, name.take(160), file.length(), sha256(file), kind)
    }
    private fun validateBoot(file: File, kind: ImageKind) {
        val h = file.inputStream().use { readHeader(it) }
        fun u32(offset: Int): Long {
            requireSafe(h.size >= offset + 4, "镜像头截断")
            return (0..3).fold(0L) { v, n -> v or ((h[offset + n].toLong() and 255) shl (n * 8)) }
        }
        fun aligned(n: Long, page: Long) = Math.multiplyExact((Math.addExact(n, page - 1) / page), page)
        val required: Long
        if (kind == ImageKind.BOOT) {
            val version = u32(40)
            requireSafe(version in 0L..4L, "不支持的 boot header 版本")
            val page = if (version >= 3) 4096L else u32(36)
            requireSafe(page in 2048..65536 && page and (page - 1) == 0L, "无效 boot page size")
            val kernel = u32(8)
            val ramdisk = u32(if (version >= 3) 12 else 16)
            requireSafe(kernel > 0 || version == 4L, "空 kernel / 不支持的 boot 结构")
            var size = Math.addExact(page, Math.addExact(aligned(kernel, page), aligned(ramdisk, page)))
            if (version < 3) size = Math.addExact(size, aligned(u32(24), page))
            if (version == 1L || version == 2L) {
                val recoverySize = u32(1632)
                val recoveryOffset = u32(1636) or (u32(1640) shl 32)
                requireSafe(recoveryOffset >= 0, "recovery DTBO 偏移溢出")
                if (recoverySize > 0) size = maxOf(size, Math.addExact(recoveryOffset, recoverySize))
                if (version == 2L) size = Math.addExact(aligned(size, page), aligned(u32(1648), page))
            }
            if (version == 4L) size = Math.addExact(size, u32(1580))
            required = size
        } else {
            val version = u32(8)
            requireSafe(version == 3L || version == 4L, "不支持的 vendor boot header")
            val page = u32(12)
            requireSafe(page in 2048..65536 && page and (page - 1) == 0L, "无效 vendor page size")
            val headerSize = u32(2096)
            requireSafe(headerSize >= if (version == 4L) 2128 else 2112, "vendor header 截断")
            var size = Math.addExact(aligned(headerSize, page), Math.addExact(aligned(u32(24), page), aligned(u32(2100), page)))
            if (version == 4L) size = Math.addExact(size, Math.addExact(aligned(u32(2112), page), aligned(u32(2124), page)))
            required = size
        }
        requireSafe(file.length() >= required, "镜像载荷截断：头声明的长度超出实际文件")
    }
}
