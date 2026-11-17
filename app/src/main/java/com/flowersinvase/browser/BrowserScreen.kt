@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.flowersinvase.browser

import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.view.Window
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandIn
import androidx.compose.animation.shrinkOut
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

private enum class BrowserPanel { None, Tabs, PageActions }

@Composable
internal fun BrowserScreen(session: BrowserSession, window: Window) {
    val colors = MaterialTheme.colorScheme
    val tab = session.active
    val url = tab.url
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val addressFocus = remember(tab.id) { FocusRequester() }
    val density = LocalDensity.current
    val keyboardBottom = WindowInsets.ime.getBottom(density)
    val keyboardOpen = keyboardBottom > 0
    var panel by rememberSaveable { mutableStateOf(BrowserPanel.None) }
    val showTabs = panel == BrowserPanel.Tabs
    val panelOpen = panel != BrowserPanel.None
    var pageEdgeColor by remember(tab.id) { mutableStateOf<Color?>(null) }
    val statusBackdrop = pageEdgeColor?.takeUnless { tab.failed } ?: colors.background
    val darkStatusIcons = statusBackdrop.luminance() > .179f
    SideEffect {
        WindowCompat.getInsetsController(window, window.decorView)
            .isAppearanceLightStatusBars = darkStatusIcons
    }
    var compactToolbar by remember(tab.id) { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var address by remember(tab.id) { mutableStateOf(TextFieldValue()) }
    var addressKeyboardSession by remember { mutableStateOf(false) }
    var addressKeyboardSeen by remember { mutableStateOf(false) }
    var addressChanged by remember { mutableStateOf(false) }
    val suggestions = if (editing && addressChanged) session.suggestions(address.text) else emptyList()
    val keyboardExpansion = if (addressKeyboardSession) {
        val navigationBottom = WindowInsets.navigationBars.getBottom(density)
        val keyboardHeight = maxOf(
            keyboardBottom,
            WindowInsets.imeAnimationSource.getBottom(density),
            WindowInsets.imeAnimationTarget.getBottom(density)
        )
        ((keyboardBottom - navigationBottom).toFloat() /
            (keyboardHeight - navigationBottom).coerceAtLeast(1)).coerceIn(0f, 1f)
    } else 0f

    fun stopEditing() {
        editing = false
        addressKeyboardSeen = false
        focusManager.clearFocus()
        keyboard?.hide()
    }

    fun editAddress() {
        val initial = url.orEmpty()
        address = TextFieldValue(initial, TextRange(0, initial.length))
        addressChanged = false
        panel = BrowserPanel.None
        addressKeyboardSession = true
        editing = true
    }

    fun goBack() {
        when {
            editing -> stopEditing()
            panelOpen -> panel = BrowserPanel.None
            else -> session.back()
        }
    }

    fun newTab() {
        stopEditing()
        panel = BrowserPanel.None
        session.add()
    }

    fun openAddress(destination: String) {
        stopEditing()
        compactToolbar = false
        session.navigate(destination)
    }

    fun submit() {
        resolveAddress(address.text)?.let(::openAddress)
    }

    LaunchedEffect(session) {
        snapshotFlow { session.snapshot() }.collect { session.save(it) }
    }
    LaunchedEffect(editing) {
        if (editing) {
            addressFocus.requestFocus()
            keyboard?.show()
        }
    }
    LaunchedEffect(keyboardOpen, editing) {
        if (editing && keyboardOpen) addressKeyboardSeen = true
        if (editing && !keyboardOpen && addressKeyboardSeen) stopEditing()
        if (!editing && !keyboardOpen) addressKeyboardSession = false
    }

    BackHandler(enabled = editing || panelOpen || url != null, onBack = ::goBack)

    val clipLabel = stringResource(R.string.copy_link)
    val surfaceColor = colors.surface.toArgb()
    BlurScene(
        Modifier.fillMaxSize(),
        content = {
            if (url != null && !tab.rendererGone) {
                BrowserWebView(
                    tab, window, surfaceColor, visible = !tab.failed, accessible = !panelOpen && !editing,
                    onVerticalDrag = { downPage ->
                        if (!panelOpen && !editing && !keyboardOpen) {
                            compactToolbar = downPage
                        }
                    },
                    onPageEdgeColor = { page, color ->
                        if (tab.url == page) pageEdgeColor = Color(color)
                    },
                    onVisited = session::recordSite,
                    modifier = Modifier.fillMaxSize()
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(
                            WindowInsetsSides.Top + WindowInsetsSides.Horizontal
                        ))
                        .then(if (addressKeyboardSession) Modifier else Modifier.imePadding())
                )
            }
        },
        overlay = {
            if (tab.failed && !panelOpen && !editing) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    BlurSurface(Modifier.padding(horizontal = 24.dp).widthIn(max = 440.dp)) {
                        PageError(tab.errorMessage, session::reload)
                    }
                }
            }
            if (panelOpen || editing) {
                val dismissLabel = stringResource(if (editing) R.string.cancel_edit else R.string.close_panel)
                Box(
                    Modifier.matchParentSize()
                        .clickable(interactionSource = null, indication = null,
                            onClickLabel = dismissLabel, onClick = ::goBack)
                )
            }
            if (!keyboardOpen || addressKeyboardSession) {
                BoxWithConstraints(
                    Modifier.align(Alignment.BottomCenter)
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(
                            WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom
                        ))
                        .padding(
                            start = 20.dp, end = 20.dp, top = 10.dp, bottom = 16.dp
                        )
                        .fillMaxWidth()
                ) {
                    val sheetHeight = maxHeight * .56f
                    val availableWidth = maxWidth
                    val toolbarWidth = minOf(availableWidth, 352.dp)
                    val stripWidth = lerp(toolbarWidth, availableWidth, keyboardExpansion)
                    Column(
                        Modifier.align(Alignment.BottomCenter).width(stripWidth),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (panel == BrowserPanel.PageActions) {
                            PageActions(
                                modifier = Modifier.align(Alignment.Start),
                                canGoForward = tab.canGoForward,
                                canCopy = url != null,
                                onForward = {
                                    panel = BrowserPanel.None
                                    session.forward()
                                },
                                onNewTab = ::newTab,
                                onCopy = {
                                    if (url != null) {
                                        context.getSystemService(ClipboardManager::class.java)
                                            .setPrimaryClip(ClipData.newPlainText(clipLabel, url))
                                        panel = BrowserPanel.None
                                    }
                                }
                            )
                        }
                        if (suggestions.isNotEmpty()) {
                            BlurSurface(Modifier.fillMaxWidth().heightIn(max = sheetHeight)) {
                                LazyColumn(Modifier.fillMaxWidth().padding(6.dp)) {
                                    itemsIndexed(suggestions, key = { _, site -> site }) { _, site ->
                                        val label = Uri.parse(site).encodedSchemeSpecificPart.removePrefix("//")
                                            .removePrefix("www.").trimEnd('/')
                                        PageAction(Glyph.Search, label, { openAddress(site) })
                                    }
                                }
                            }
                        }
                        val collapsed = compactToolbar && url != null && !tab.failed && !panelOpen && !editing
                        val collapse by animateFloatAsState(
                            targetValue = if (collapsed) 1f else 0f,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioNoBouncy,
                                stiffness = 105f,
                                visibilityThreshold = .001f
                            ),
                            label = "Toolbar collapse"
                        )
                        val buttonOpacity = (1f - collapse / .62f).coerceIn(0f, 1f)
                        val expandedAddressWidth = (toolbarWidth - 124.dp).coerceAtLeast(48.dp)
                        val browsingAddressWidth = lerp(expandedAddressWidth, minOf(136.dp, toolbarWidth), collapse)
                        val addressWidth = lerp(browsingAddressWidth, availableWidth, keyboardExpansion)
                        val addressHeight = lerp(52.dp, 44.dp, collapse)
                        Box(Modifier.fillMaxWidth().height(addressHeight)) {
                            val keyboardButtonOpacity = (1f - keyboardExpansion / .56f).coerceIn(0f, 1f)
                            val sideButtonOpacity = buttonOpacity * keyboardButtonOpacity
                            val buttonShift = 40.dp * collapse + 36.dp * keyboardExpansion
                            if (sideButtonOpacity > 0f) {
                                GlassButton(
                                    Glyph.Left, stringResource(R.string.back), ::goBack,
                                    enabled = !addressKeyboardSession && !collapsed && (panelOpen || url != null),
                                    modifier = Modifier.align(Alignment.CenterStart)
                                        .offset(x = buttonShift).alpha(sideButtonOpacity)
                                )
                                GlassButton(
                                    Glyph.Tabs, stringResource(R.string.tabs_count, session.tabs.size),
                                    onClick = { panel = if (showTabs) BrowserPanel.None else BrowserPanel.Tabs },
                                    enabled = !addressKeyboardSession && !collapsed,
                                    modifier = Modifier.align(Alignment.CenterEnd)
                                        .offset(x = -buttonShift).alpha(sideButtonOpacity)
                                )
                            }
                            BlurSurface(
                                Modifier.align(Alignment.Center).width(addressWidth).fillMaxHeight(),
                                shape = CircleShape
                            ) {
                                AddressRow(
                                    url, editing, address, {
                                        if (address.text != it.text) addressChanged = true
                                        address = it
                                    }, addressFocus,
                                    edit = { if (collapsed) compactToolbar = false else editAddress() },
                                    submit = ::submit,
                                    reload = session::reload,
                                    progress = tab.progress,
                                    collapsed = collapsed,
                                    collapse = collapse,
                                    buttonOpacity = buttonOpacity,
                                    onMenu = {
                                        panel = if (panel == BrowserPanel.PageActions) BrowserPanel.None
                                        else BrowserPanel.PageActions
                                    }
                                )
                            }
                        }
                    }
                    Box(Modifier.align(Alignment.BottomCenter).width(toolbarWidth)) {
                        TabsReveal(
                            visible = showTabs,
                            modifier = Modifier.align(Alignment.BottomEnd),
                            width = toolbarWidth,
                            maxHeight = sheetHeight,
                            tabs = session.tabs,
                            active = tab,
                            dismiss = { panel = BrowserPanel.None },
                            onSelect = {
                                session.select(it)
                                panel = BrowserPanel.None
                            },
                            onNewTab = ::newTab,
                            onClose = session::close
                        )
                    }
                }
            }
            Box(Modifier.align(Alignment.TopCenter).fillMaxWidth()
                .windowInsetsTopHeight(WindowInsets.safeDrawing).background(statusBackdrop))
        }
    )
}

@Composable
private fun GlassButton(
    glyph: Glyph, label: String, onClick: () -> Unit, enabled: Boolean, modifier: Modifier
) {
    val color = MaterialTheme.colorScheme.onSurface
    BlurSurface(modifier.size(52.dp), shape = CircleShape) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            BrowserIconButton(
                glyph, label, onClick, size = 25, enabled = enabled,
                tint = if (enabled) color else color.copy(alpha = .32f)
            )
        }
    }
}

@Composable
private fun AddressRow(
    url: String?,
    editing: Boolean,
    address: TextFieldValue,
    change: (TextFieldValue) -> Unit,
    focus: FocusRequester,
    edit: () -> Unit,
    submit: () -> Unit,
    reload: () -> Unit,
    progress: Int,
    collapsed: Boolean,
    collapse: Float,
    buttonOpacity: Float,
    onMenu: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val type = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp)
    val hint = stringResource(R.string.search_hint)
    Box(Modifier.fillMaxSize()) {
        if (editing) {
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    BrowserIcon(Glyph.Search, colors.onSurfaceVariant, 18)
                }
                BasicTextField(
                    value = address,
                    onValueChange = change,
                    modifier = Modifier.weight(1f).focusRequester(focus),
                    singleLine = true,
                    textStyle = type.copy(
                        color = colors.onSurface,
                        fontWeight = FontWeight.Medium,
                        textDirection = TextDirection.Content
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { submit() }),
                    cursorBrush = SolidColor(colors.primary),
                    decorationBox = { field ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (address.text.isEmpty()) {
                                Text(hint, style = type, color = colors.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            field()
                        }
                    }
                )
                if (address.text.isNotBlank()) {
                    BrowserIconButton(Glyph.Submit, stringResource(R.string.open_address), submit, tint = colors.primary)
                } else {
                    Spacer(Modifier.width(48.dp))
                }
            }
        } else {
            val textInset = if (url != null) lerp(48.dp, 14.dp, collapse) else 12.dp
            val action = if (collapsed) R.string.expand_toolbar else R.string.edit_address
            Box(
                Modifier.fillMaxSize().padding(horizontal = textInset)
                    .clickable(
                        interactionSource = null,
                        indication = null,
                        role = Role.Button,
                        onClickLabel = stringResource(action),
                        onClick = edit
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    url?.let { Uri.parse(it).host ?: it } ?: hint,
                    style = if (url != null) type.copy(
                        textDirection = TextDirection.Ltr, fontWeight = FontWeight.Medium
                    ) else type,
                    color = if (url != null) colors.onSurface else colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (url != null && buttonOpacity > 0f) {
                BrowserIconButton(
                    Glyph.Menu, stringResource(R.string.page_actions), onMenu, size = 21, enabled = !collapsed,
                    modifier = Modifier.align(Alignment.CenterStart).alpha(buttonOpacity)
                )
                BrowserIconButton(
                    Glyph.Reload, stringResource(R.string.reload), reload, size = 21, enabled = !collapsed,
                    modifier = Modifier.align(Alignment.CenterEnd).alpha(buttonOpacity)
                )
            }
        }
        if (progress in 0..99 && url != null && !editing) {
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(progress / 100f)
                .height(2.dp).background(colors.primary))
        }
    }
}

@Composable
private fun PageActions(
    modifier: Modifier,
    canGoForward: Boolean,
    canCopy: Boolean,
    onForward: () -> Unit,
    onNewTab: () -> Unit,
    onCopy: () -> Unit
) {
    BlurSurface(modifier.widthIn(max = 236.dp)) {
        Column(Modifier.padding(8.dp)) {
            PageAction(Glyph.Right, stringResource(R.string.forward), onForward, canGoForward)
            PageAction(Glyph.Plus, stringResource(R.string.new_tab), onNewTab)
            PageAction(Glyph.Copy, stringResource(R.string.copy_link), onCopy, canCopy)
        }
    }
}

@Composable
private fun PageAction(glyph: Glyph, text: String, onClick: () -> Unit, enabled: Boolean = true) {
    val color = MaterialTheme.colorScheme.onSurface.let { if (enabled) it else it.copy(alpha = .42f) }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 50.dp).clip(RoundedCornerShape(18.dp))
            .clickable(role = Role.Button, enabled = enabled, onClick = onClick)
            .padding(horizontal = 15.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BrowserIcon(glyph, color, 19)
        Text(text, color = color, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun TabsReveal(
    visible: Boolean,
    modifier: Modifier,
    width: Dp,
    maxHeight: Dp,
    tabs: List<BrowserTab>,
    active: BrowserTab,
    dismiss: () -> Unit,
    onSelect: (BrowserTab) -> Unit,
    onNewTab: () -> Unit,
    onClose: (BrowserTab) -> Unit
) {
    val transition = updateTransition(visible, label = "Tabs from button")
    val motion = tween<Float>(360, easing = FastOutSlowInEasing)
    val progress by transition.animateFloat(transitionSpec = { motion }, label = "Tabs lift") {
        if (it) 1f else 0f
    }
    if (!transition.currentState && !transition.targetState) return

    val buttonSize = with(LocalDensity.current) { 52.dp.roundToPx() }
    val sizeMotion = tween<IntSize>(360, easing = FastOutSlowInEasing)
    BlurSurface(modifier.offset(y = -60.dp * progress).alpha((progress * 8f).coerceAtMost(1f))) {
        transition.AnimatedVisibility(
            visible = { it },
            enter = expandIn(sizeMotion, expandFrom = Alignment.BottomEnd) {
                IntSize(minOf(it.width, buttonSize), minOf(it.height, buttonSize))
            } + fadeIn(tween(220, delayMillis = 85)),
            exit = shrinkOut(sizeMotion, shrinkTowards = Alignment.BottomEnd) {
                IntSize(minOf(it.width, buttonSize), minOf(it.height, buttonSize))
            } + fadeOut(tween(160))
        ) {
            TabsPanel(
                tabs, active, dismiss, onSelect, onNewTab, onClose,
                modifier = Modifier.width(width).heightIn(max = maxHeight)
            )
        }
    }
}

@Composable
private fun TabsPanel(
    tabs: List<BrowserTab>,
    active: BrowserTab,
    dismiss: () -> Unit,
    onSelect: (BrowserTab) -> Unit,
    onNewTab: () -> Unit,
    onClose: (BrowserTab) -> Unit,
    modifier: Modifier
) {
    val colors = MaterialTheme.colorScheme
    val type = MaterialTheme.typography
    val newTabLabel = stringResource(R.string.new_tab)
    val closeLabel = stringResource(R.string.close_tab)
    Column(modifier.padding(start = 12.dp, top = 8.dp, end = 12.dp, bottom = 12.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.tabs), style = type.headlineSmall,
                fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            BrowserIconButton(Glyph.Plus, newTabLabel, onNewTab)
            BrowserIconButton(Glyph.Close, stringResource(R.string.close_tabs), dismiss)
        }
        LazyColumn {
            itemsIndexed(tabs, key = { _, tab -> tab.id }) { index, tab ->
                val top = if (index == 0) 22.dp else 0.dp
                val bottom = if (index == tabs.lastIndex) 22.dp else 0.dp
                val selected = tab === active
                val host = tab.url?.let { Uri.parse(it).host ?: it }
                val title = tab.title.ifBlank { host ?: newTabLabel }
                Row(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(top, top, bottom, bottom)).background(colors.onSurface.copy(alpha = .055f)),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        Modifier.weight(1f).heightIn(min = 64.dp)
                            .semantics { this.selected = selected }
                            .clickable(role = Role.Button) { onSelect(tab) }
                            .padding(start = 15.dp, top = 11.dp, bottom = 11.dp, end = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(13.dp)
                    ) {
                        BrowserIcon(if (selected) Glyph.Check else Glyph.Tab,
                            if (selected) colors.primary else colors.onSurfaceVariant, 20)
                        Column {
                            Text(title, style = type.bodyLarge.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (host != null && host != title) {
                                Text(host, style = type.bodySmall, color = colors.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    BrowserIconButton(Glyph.Close, closeLabel, { onClose(tab) }, tint = colors.onSurfaceVariant)
                    Spacer(Modifier.width(4.dp))
                }
            }
        }
    }
}

@Composable
private fun PageError(message: String?, reload: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Column(modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.page_error), style = MaterialTheme.typography.titleMedium)
        if (!message.isNullOrBlank()) {
            Text(message, color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp))
        }
        Box(
            Modifier.padding(top = 16.dp).heightIn(min = 48.dp).clip(RoundedCornerShape(18.dp))
                .background(colors.primary).clickable(role = Role.Button, onClick = reload)
                .padding(horizontal = 22.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(stringResource(R.string.retry), color = colors.onPrimary, style = MaterialTheme.typography.labelLarge)
        }
    }
}
