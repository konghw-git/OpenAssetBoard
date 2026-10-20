package com.assetboard.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetState
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

/**
 * A page pushed over the tabs, on the same colour fields as they are, so its cards, chips and
 * header buttons are glass too (they turn glass by themselves through [LocalAmbient]). A Scaffold
 * with nothing of its own behind; pair it with [glassBarColors].
 */
@Composable
fun GlassScaffold(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    AmbientPage(modifier) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = topBar,
            bottomBar = bottomBar,
            snackbarHost = snackbarHost,
            floatingActionButton = floatingActionButton,
            content = content,
        )
    }
}

/** A top bar that lets a [GlassScaffold]'s colour show through, scrolled or not. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun glassBarColors(): TopAppBarColors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, scrolledContainerColor = Color.Transparent)

/**
 * A bottom sheet on colour fields of its own, so the cards and fields in it are glass like the
 * page under it. The sheet is a window of its own, which the page's colour cannot reach.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassSheet(
    onDismissRequest: () -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    content: @Composable ColumnScope.() -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val a = rememberAmbient()
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = cs.background,
        tonalElevation = 0.dp,
        dragHandle = null,
    ) {
        CompositionLocalProvider(LocalAmbient provides a) {
            Column(Modifier.fillMaxWidth().ambient(a)) {
                Box(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 14.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(width = 36.dp, height = 4.dp).clip(RoundedCornerShape(50)).background(cs.onSurface.copy(alpha = 0.18f)))
                }
                content()
            }
        }
    }
}

/** The rounded corners of text fields on glass. */
val FieldShape = RoundedCornerShape(16.dp)

/**
 * Text fields as frosted wells: a translucent body over the colour, a hairline edge that turns to
 * the theme colour when focused. Outside glass, a plain card fill.
 */
@Composable
fun glassFieldColors(): TextFieldColors {
    val cs = MaterialTheme.colorScheme
    val dark = cs.background.luminance() < 0.5f
    val body = when {
        LocalAmbient.current == null -> cs.surfaceContainer
        dark -> Color.White.copy(alpha = 0.06f)
        else -> Color.White.copy(alpha = 0.55f)
    }
    val edge = cs.onSurface.copy(alpha = if (dark) 0.16f else 0.12f)
    return OutlinedTextFieldDefaults.colors(
        focusedContainerColor = body,
        unfocusedContainerColor = body,
        disabledContainerColor = body,
        errorContainerColor = body,
        unfocusedBorderColor = edge,
        disabledBorderColor = edge,
        focusedBorderColor = cs.primary,
    )
}
