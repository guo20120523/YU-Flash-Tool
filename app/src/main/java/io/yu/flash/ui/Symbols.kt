package io.yu.flash.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import io.yu.flash.R

/** Pinned official Material Symbols Rounded, wght=400 / GRAD=0. No icon font or network. */
internal enum class Symbol(@DrawableRes val outline: Int, @DrawableRes val filled: Int = outline) {
    Home(R.drawable.ms_home_24, R.drawable.ms_home_fill1_24),
    Tasks(R.drawable.ms_assignment_24, R.drawable.ms_assignment_fill1_24),
    Settings(R.drawable.ms_settings_24, R.drawable.ms_settings_fill1_24),
    Info(R.drawable.ms_info_24, R.drawable.ms_info_fill1_24),
    Flash(R.drawable.ms_flash_on_fill1_48),
    Refresh(R.drawable.ms_refresh_24),
    Search(R.drawable.ms_search_24),
    Download(R.drawable.ms_download_24),
    Upload(R.drawable.ms_upload_24),
    ExpandMore(R.drawable.ms_expand_more_24),
    Check(R.drawable.ms_check_circle_24),
    Warning(R.drawable.ms_warning_24),
    Delete(R.drawable.ms_delete_24),
    Copy(R.drawable.ms_content_copy_24),
    Description(R.drawable.ms_description_24),
    Folder(R.drawable.ms_folder_open_24),
    Sync(R.drawable.ms_sync_24),
    Error(R.drawable.ms_error_24),
    Cancel(R.drawable.ms_cancel_24)
}

@Composable
internal fun SymbolIcon(
    symbol: Symbol,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    tint: Color = LocalContentColor.current
) {
    Icon(painterResource(if (selected) symbol.filled else symbol.outline), contentDescription,
        modifier.size(24.dp), tint = tint)
}

/** Decorative icons accompany a visible label; do not make TalkBack announce it twice. */
@Composable
internal fun RowScope.ButtonSymbol(symbol: Symbol) {
    SymbolIcon(symbol, modifier = Modifier.size(18.dp))
    Spacer(Modifier.width(Spacing.small))
}
