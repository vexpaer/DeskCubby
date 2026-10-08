# CodeGraph exploration

Query: DeskCubby Windows and Tauri frontend/backend boundary, persistence, commands, and dependencies

**Exploration: DeskCubby Windows and Tauri frontend/backend boundary, persistence, commands, and dependencies**

Found 12 symbols across 2 files.

**Blast radius — what depends on these (update/verify before editing)**

- `command` (windows/src-tauri/src/rss.rs:189) — 4 callers in `windows/src-tauri/src/rss.rs`; ⚠️ no covering tests found
- `WindowInfo` (android/app/src/main/java/com/deskcubby/app/ui/components/AdaptiveLayout.kt:69) — 2 callers in `android/app/src/main/java/com/deskcubby/app/ui/components/AdaptiveLayout.kt`; tests: `android/app/src/test/java/com/deskcubby/app/ui/components/AdaptiveLayoutModeTest.kt`
- `CommandResult` (windows/src-tauri/src/security.rs:95) — 247 callers in `windows/src-tauri/src/ai.rs`, `windows/src-tauri/src/cloud_sync/commands.rs`, `windows/src-tauri/src/commands.rs`, `windows/src-tauri/src/games.rs` +3 more; ⚠️ no covering tests found
- `CommandPalette` (android/app/src/main/java/com/deskcubby/app/ui/components/CommandPalette.kt:65) — 2 callers in `android/app/src/main/java/com/deskcubby/app/ui/Navigation.kt`; ⚠️ no covering tests found

**Relationships**

**implements:**
- Load → Command
- Save → Command
- Finish → Command
- Statistics → Command
- Clear → Command

**calls:**
- DiaryEditPage → Save
- MealFilterPage → Save
- NoteEditor → Save
- SettingsPage → Save
- ConfigDialog → Save
- CloudSyncPage → Save
- DateRecordsPage → Save
- DiaryPage → Save
- GamesPage → Save
- NotesPage → Save
- ... and 226 more

**references:**
- command → SecurityErrorDto
- page → RssError
- article_url → RssError
- subscriptions_changed → RssError
- refresh → RssError
- fetch_subscription → RssError
- resolve_public_addresses → RssError
- download_feed → RssError
- map_reqwest_error → RssError
- validate_redirect → RssError
- ... and 62 more

**instantiates:**
- rememberWindowInfo → WindowInfo
- info → WindowInfo
- CommandPalette → Text

**imports:**
- com.deskcubby.app.ui.components → AppLanguage
- com.deskcubby.app.ui.components → LayoutMode
- com.deskcubby.app.ui.components → NavItemConfig
- com.deskcubby.app.ui.components → NavItemId
- com.deskcubby.app.ui.components → VisualStyle
- com.deskcubby.app.ui.components → iconFor
- com.deskcubby.app.ui.components → GlassPanel
- com.deskcubby.app.ui.components → LocalAppLanguage
- com.deskcubby.app.ui.components → LocalVisualStyle
- com.deskcubby.app.ui.components → PanelRole
- ... and 7 more

**Source Code**

> The code below is the **verbatim, current on-disk source** of these files — re-read from disk on this call and line-numbered, byte-for-byte identical to what the Read tool returns. It is NOT a summary, outline, or stale cache. Treat each block as a Read you have already performed: do not Read a file shown here.

**`android/app/src/main/java/com/deskcubby/app/ui/theme/Theme.kt`** — imports(imports), calls(calls), VisualStyle(references), references(references), com.deskcubby.app.ui.theme(namespace), AppLanguage(imports), VisualStyle(imports), LocalVisualStyle(constant), LocalAppLanguage(constant), DeskCubbyTheme(function)

```kotlin
1	package com.deskcubby.app.ui.theme
2	
3	import android.app.Activity
4	import android.content.Context
5	import android.content.ContextWrapper
6	import android.os.Build
7	import androidx.compose.foundation.BorderStroke

... (gap) ...

21	import androidx.compose.material3.darkColorScheme
22	import androidx.compose.material3.lightColorScheme
23	import androidx.compose.runtime.Composable
24	import androidx.compose.runtime.CompositionLocalProvider
25	import androidx.compose.runtime.ProvidableCompositionLocal
26	import androidx.compose.runtime.SideEffect
27	import androidx.compose.runtime.staticCompositionLocalOf
28	import androidx.compose.ui.Modifier
29	import androidx.compose.ui.draw.clip
30	import androidx.compose.ui.draw.shadow
31	import androidx.compose.ui.draw.drawBehind
32	import androidx.compose.ui.graphics.Brush

... (gap) ...

46	import androidx.compose.ui.unit.Dp
47	import androidx.compose.ui.unit.TextUnit
48	import androidx.compose.ui.unit.dp
49	import com.deskcubby.app.data.model.AppSettings
50	import com.deskcubby.app.data.model.AppLanguage
51	import com.deskcubby.app.data.model.CustomThemeBaseStyle
52	import com.deskcubby.app.data.model.CustomThemeSettings
53	import com.deskcubby.app.data.model.DEFAULT_THEME_COLOR_ARGB
54	import com.deskcubby.app.data.model.DEFAULT_THEME_SECONDARY_COLORS_ARGB
55	import com.deskcubby.app.data.model.DarkMode
56	import com.deskcubby.app.data.model.MAX_APP_FONT_SCALE
57	import com.deskcubby.app.data.model.MIN_APP_FONT_SCALE
58	import com.deskcubby.app.data.model.VisualStyle
59	import com.deskcubby.app.data.model.normalized
60	import androidx.core.view.WindowCompat
61	
62	val LocalVisualStyle: ProvidableCompositionLocal<VisualStyle> =
63	    staticCompositionLocalOf { VisualStyle.MATERIAL }
64	
65	val LocalAppLanguage: ProvidableCompositionLocal<AppLanguage> =
66	    staticCompositionLocalOf { AppLanguage.CHINESE }
67	
68	/** Compact mode tightens list/settings paddings across the app. */
69	val LocalCompactMode: ProvidableCompositionLocal<Boolean> =

... (gap) ...

115	
116	private val DefaultShapes = Shapes()
117	
118	@Composable
119	fun DeskCubbyTheme(settings: AppSettings, content: @Composable () -> Unit) {
120	    val dark = when (settings.darkMode) {
121	        DarkMode.SYSTEM -> isSystemInDarkTheme()
122	        DarkMode.LIGHT -> false
123	        DarkMode.DARK -> true
124	    }
125	    val customTheme = settings.customTheme.normalized()
126	    val effectiveStyle = settings.visualStyle.effectiveBaseStyle(customTheme)
127	    val baseScheme = resolveColorScheme(
128	        visualStyle = settings.visualStyle,
129	        dark = dark,
130	        themeColorArgb = settings.themeColorArgb,
131	        themeSecondaryColorsArgb = settings.themeSecondaryColorsArgb,
132	        customTheme = customTheme,
133	    )
134	    // The app background layer lives below every navigation destination. Making only the
135	    // page-background role transparent keeps cards, dialogs, and controls readable while allowing
136	    // Scaffold canvases to reveal the user-selected image.
137	    val scheme = if (settings.backgroundImageUri != null) {
138	        baseScheme.copy(background = Color.Transparent)
139	    } else {
140	        baseScheme
141	    }
142	    val baseTypography = when (effectiveStyle) {
143	        VisualStyle.ORGANIC_FUTURE -> OrganicFutureTypography
144	        VisualStyle.LIQUID_GLASS -> GlassTypography
145	        else -> AppTypography
146	    }
147	    val reducedMotion = androidx.compose.runtime.remember { systemReducedMotion() }
148	    val typography = scaledTypography(baseTypography, settings.fontScale)
149	    val shapes = when (settings.visualStyle) {
150	        VisualStyle.CUSTOM -> customShapes(customTheme.cornerRadiusDp.dp)
151	        else -> if (effectiveStyle == VisualStyle.ORGANIC_FUTURE) OrganicFutureShapes else DefaultShapes
152	    }
153	    val visualTokens = if (settings.visualStyle == VisualStyle.CUSTOM) {
154	        customVisualTokens(effectiveStyle, customTheme)
155	    } else {
156	        visualTokensFor(effectiveStyle)
157	    }
158	    val view = LocalView.current
159	    if (!view.isInEditMode) {
160	        SideEffect {
161	            view.context.findActivity()?.window?.let { window ->
162	                WindowCompat.getInsetsController(window, view).apply {
163	                    isAppearanceLightStatusBars = !dark
164	                    isAppearanceLightNavigationBars = !dark
165	                }
166	                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
167	                    window.isNavigationBarContrastEnforced = false
168	                    window.isStatusBarContrastEnforced = false
169	                }
170	            }
171	        }
172	    }
173	    androidx.compose.runtime.CompositionLocalProvider(
174	        // Existing screens only need the rendering behavior. Keeping CUSTOM out of this local
175	        // lets every established Material/Glass/Organic branch continue to work unchanged.
176	        LocalVisualStyle provides effectiveStyle,
177	        LocalAppLanguage provides settings.appLanguage,
178	        LocalCompactMode provides settings.compactMode,
179	        LocalReducedMotion provides reducedMotion,
180	        LocalDeskCubbyVisuals provides visualTokens,
181	        LocalOrganicFuturePrimaryColor provides Color(settings.themeColorArgb or 0xFF000000.toInt()),
182	        LocalOrganicFutureAccentColors provides organicFutureAccentColors(
183	            settings.themeSecondaryColorsArgb,
184	        ),
185	    ) {
186	        MaterialTheme(
187	            colorScheme = scheme,
188	            typography = typography,
189	            shapes = shapes,
190	            content = content,
191	        )
192	    }
193	}
194	
195	internal fun resolveColorScheme(
196	    visualStyle: VisualStyle,
```

**`android/app/src/main/java/com/deskcubby/app/ui/Navigation.kt`** — references(references), calls(calls), NavItemId(references), tr(calls), target(calls), instantiates(instantiates), VisualStyle(references), SettingsStartPage(references), PaletteEntryKind(references), filter(calls), +15 more

```kotlin
233	    const val POETRY_SETTINGS = "settings/poetry"
234	}
235	
236	@Composable
237	fun DeskCubbyRoot(
238	    settingsViewModel: SettingsViewModel = hiltViewModel(),
239	    diaryViewModel: DiaryViewModel = hiltViewModel(),
240	    thoughtViewModel: ThoughtViewModel = hiltViewModel(),
241	    notesViewModel: NotesViewModel = hiltViewModel(),
242	    blogViewModel: BlogViewModel = hiltViewModel(),
243	    homeViewModel: HomeViewModel = hiltViewModel(),
244	    dateRecordViewModel: DateRecordViewModel = hiltViewModel(),
245	    structuredRecordsViewModel: StructuredRecordsViewModel = hiltViewModel(),
246	    externalNavigationRoute: String? = null,
247	    externalDiaryUri: String? = null,
248	    externalGameId: String? = null,
249	    onExternalNavigationHandled: () -> Unit = {},
250	) {
251	    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
252	    val ready by settingsViewModel.ready.collectAsStateWithLifecycle()
253	    val cloudSyncStatus by settingsViewModel.cloudSyncStatus.collectAsStateWithLifecycle()
254	
255	    // Device-local orientation lock: AUTO follows the sensor, PORTRAIT/LANDSCAPE pin the
256	    // activity. This controls rotation only; LayoutMode below decides UI structure from
257	    // the resulting window geometry. It reads the same context used by the Reader orientation
258	    // effect and is cleared when this composable (and its reader) leaves composition.
259	    // The Reader owns a per-book orientation preference that must win over the app-level
260	    // preference while reading. The global lock is therefore applied below, after the reader
261	    // open state is known, and is suspended while the reader is active.
262	    DeskCubbyTheme(settings) {
263	        AppBackground(settings) {
264	        if (!ready) {
265	            Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
266	                AppLoadingIndicator()
267	            }
268	            return@AppBackground
269	        }
270	        // First launch: before the navigation graph, ask for the UI language once. The device-
271	        // local flag keeps this from ever showing again and is not backed up.
272	        val languageSelected by settingsViewModel.languageSelected.collectAsStateWithLifecycle()
273	        if (!languageSelected) {
274	            FirstLaunchLanguageScreen(onChoose = settingsViewModel::chooseFirstLaunchLanguage)
275	            return@AppBackground
276	        }
277	        val navController = rememberNavController()
278	        val aliasContext = LocalContext.current
279	        LaunchedEffect(settings.useChineseLauncherName, settings.launcherIcon) {
280	            syncLauncherAlias(
281	                aliasContext,
282	                settings.useChineseLauncherName,
283	                settings.launcherIcon,
284	            )
285	        }
286	        val orientationActivity = LocalContext.current.findActivityCompat()
287	        var settingsSubpageOpen by remember { mutableStateOf(false) }
288	        var readerOpen by remember { mutableStateOf(false) }
289	        // Apply the app-level orientation lock only while the reader is not the active
290	        // surface; the reader's per-book orientation effect handles rotation while reading.
291	        OrientationPreferenceEffect(
292	            activity = orientationActivity,
293	            preference = settings.orientationPreference,
294	            suspendWhileReaderOpen = readerOpen,
295	        )
296	        var gameOpen by remember { mutableStateOf(false) }
297	        var requestedGameId by remember { mutableStateOf<String?>(null) }
298	        // One-shot prompt forwarded to the AI Chat screen (e.g. Desk's "总结今天"). Consumed by the
299	        // AI chat composable; the ViewModel guards against re-sending on rotation.
300	        var pendingAiPrompt by remember { mutableStateOf<String?>(null) }
301	        var childTutorialTarget by remember { mutableStateOf<PageTutorialTarget?>(null) }
302	        var tutorialConfirmedThisSession by remember { mutableStateOf(emptySet<String>()) }
303	        val initialStartDestination = remember { settings.defaultPage.route }
304	        val systemAnimationsEnabled = remember { ValueAnimator.areAnimatorsEnabled() }
305	        val resolvedVisualStyle = LocalVisualStyle.current
306	        val rootVisuals = deskCubbyVisuals
307	        val customMotionDisabled = rootVisuals.customized && rootVisuals.transitionMillis == 0
308	        val organicMotionEnabled = resolvedVisualStyle == VisualStyle.ORGANIC_FUTURE &&
309	            systemAnimationsEnabled && !customMotionDisabled
310	        val organicEnterMillis = if (rootVisuals.customized) rootVisuals.transitionMillis else 340
311	        val organicExitMillis = if (rootVisuals.customized) {
312	            (rootVisuals.transitionMillis * 300 / 340f).roundToInt()
313	        } else {
314	            300
315	        }
316	        val standardMotionMillis = if (rootVisuals.customized) rootVisuals.transitionMillis else 700
317	        // Uncustomized Material and Liquid Glass get their own page choreography: Material lifts a
318	        // new sheet of paper into place; Liquid Glass swells in on a spring like a droplet.
319	        // Custom themes keep their explicit transition duration and system "remove animations"
320	        // keeps the existing fade (which the platform already shortens to instant).
321	        val expressiveMotion = systemAnimationsEnabled && !rootVisuals.customized
322	        val materialPages = expressiveMotion && resolvedVisualStyle == VisualStyle.MATERIAL
323	        val glassPages = expressiveMotion && resolvedVisualStyle == VisualStyle.LIQUID_GLASS
324	        // Backdrop shared by page content (source) and the floating Liquid Glass bar (effect).
325	        val navHazeState = rememberHazeState()
326	        val backStack by navController.currentBackStackEntryAsState()
327	        val route = backStack?.destination?.route
328	        val windowInfo = rememberWindowInfo()
329	        val layoutMode = resolveLayoutMode(windowInfo)
330	        // Navigation placement follows ORIENTATION only: portrait -> bottom bar, landscape -> left
331	        // rail. LayoutMode (width) independently drives multi-pane content structure, so a portrait
332	        // tablet gets a bottom bar with two-pane content instead of a left navigation rail.
333	        val visibleTabs = settings.navItems.filter { it.visible || it.id == NavItemId.SETTINGS }
334	        val bottomSelectedRoute = route.takeIf { currentRoute ->
335	            visibleTabs.any { it.id.route == currentRoute }
336	        } ?: NavItemId.MORE.route.takeIf {
337	            route != null && settings.navItems.any { item ->
338	                item.id.route == route && item.showInMore
339	            }
340	        }
341	        val showBottomBar = !windowInfo.isLandscape &&
342	            route in NavItemId.entries.map { it.route } &&
343	            !(route == NavItemId.SETTINGS.route && settingsSubpageOpen) &&
344	            !(route == NavItemId.READER.route && readerOpen) &&
345	            !(route == NavItemId.GAMES.route && gameOpen) &&
346	            !WindowInsets.isImeVisible
347	        // The rail is shown in landscape on top-level destinations.
348	        val showWorkspaceRail = windowInfo.isLandscape &&
349	            route in NavItemId.entries.map { it.route }
350	        // Global command palette: pages, quick actions and diary entries in one fuzzy search.
351	        var paletteOpen by remember { mutableStateOf(false) }
352	        val paletteDiaries by homeViewModel.diaries.collectAsStateWithLifecycle()
353	        // Never offered over a settings sub-page, so unsaved drafts keep their exit confirmation.
354	        val paletteAllowed = route in NavItemId.entries.map { it.route } &&
355	            !(route == NavItemId.SETTINGS.route && settingsSubpageOpen)
356	        val navigateMain: (String) -> Unit = { destination ->
357	            navController.navigate(destination) {
358	                // Keep only the graph itself, so no tab can restore another tab's nested page.
359	                popUpTo(navController.graph.id) { saveState = false }
360	                launchSingleTop = true
361	                restoreState = false
362	            }
363	        }
364	        LaunchedEffect(externalNavigationRoute, externalDiaryUri, externalGameId) {
365	            when {
366	                !externalDiaryUri.isNullOrBlank() -> {
367	                    diaryViewModel.open(externalDiaryUri)
368	                    navController.navigate(Routes.EDITOR)
369	                }
370	                externalGameId != null && externalGameId in HOME_GAME_SHORTCUT_IDS -> {
371	                    requestedGameId = externalGameId
372	                    navController.navigate(Routes.GAME_SHORTCUT)
373	                }
374	                externalNavigationRoute == Routes.DAILY_RECORDS_TODAY ->
375	                    navController.navigate(Routes.DAILY_RECORDS_TODAY)
376	                externalNavigationRoute != null &&
377	                    NavItemId.entries.any { it.route == externalNavigationRoute } ->
378	                    navigateMain(externalNavigationRoute)
379	            }
380	            if (
381	                externalNavigationRoute != null || externalDiaryUri != null || externalGameId != null
382	            ) {
383	                onExternalNavigationHandled()
384	            }
385	        }
386	
387	        CompositionLocalProvider(LocalLayoutMode provides layoutMode) {
388	        Scaffold(
389	            modifier = Modifier
390	                .fillMaxSize()
391	                .onPreviewKeyEvent { event ->
392	                    val shortcut = event.type == KeyEventType.KeyDown &&
393	                        (event.isCtrlPressed || event.isMetaPressed) &&
394	                        event.key == Key.K
395	                    if (shortcut && paletteAllowed) {
396	                        paletteOpen = true
397	                        true
398	                    } else {
399	                        false
400	                    }
401	                },
402	            containerColor = Color.Transparent,
403	            contentWindowInsets = WindowInsets(0, 0, 0, 0),
404	            bottomBar = {
405	                if (showBottomBar) {
406	                    DeskBottomBar(
407	                        items = visibleTabs,
408	                        selectedRoute = bottomSelectedRoute,
409	                        showLabels = settings.bottomNavShowLabels,
410	                        musicVisualizerEnabled = settings.musicVisualizerEnabled,
411	                        musicVisualizerStyle = settings.musicVisualizerStyle,
412	                        musicVisualizerFrequencyMode = settings.musicVisualizerFrequencyMode,
413	                        musicVisualizerMinFrequencyHz = settings.musicVisualizerMinFrequencyHz,
414	                        musicVisualizerMaxFrequencyHz = settings.musicVisualizerMaxFrequencyHz,
415	                        onSelected = { item -> navigateMain(item.id.route) },
416	                        hazeState = navHazeState,
417	                    )
418	                }
419	            },
420	        ) { padding ->
421	            Row(Modifier.fillMaxSize()) {
422	                if (showWorkspaceRail) {
423	                    DeskCubbyNavigationRail(
424	                        items = visibleTabs,
425	                        selectedRoute = bottomSelectedRoute,
426	                        onSelected = { item -> navigateMain(item.id.route) },
427	                        onOpenSettings = { navigateMain(NavItemId.SETTINGS.route) },
428	                    )
429	                }
430	                // Drawer sheets animate into negative local X while closed. Clip the content
431	                // column so that hidden/dragging pixels can never paint over the sibling rail.
432	                // The open sheet still begins at this column's x=0, flush with the rail.
433	                Box(Modifier.weight(1f).fillMaxSize().clipToBounds().hazeSource(navHazeState)) {
434	                NavHost(
435	                    navController = navController,
436	                    startDestination = initialStartDestination,
437	                    modifier = Modifier.fillMaxSize(),
438	                    enterTransition = {
439	                        when {
440	                            organicMotionEnabled -> fadeIn(tween(organicEnterMillis)) +
441	                                slideInHorizontally(tween(organicEnterMillis)) { it / 20 } +
442	                                scaleIn(tween(organicEnterMillis), initialScale = 0.992f)
443	                            resolvedVisualStyle == VisualStyle.ORGANIC_FUTURE || customMotionDisabled ->
444	                                EnterTransition.None
445	                            materialPages -> fadeIn(tween(220, delayMillis = 60)) +
446	                                slideInVertically(tween(320, easing = FastOutSlowInEasing)) { it / 18 }
447	                            glassPages -> fadeIn(tween(200)) +
448	                                scaleIn(spring(dampingRatio = 0.74f, stiffness = 320f), initialScale = 0.93f)
449	                            else -> fadeIn(tween(standardMotionMillis))
450	                        }
451	                    },
452	                    exitTransition = {
453	                        when {
454	                            organicMotionEnabled -> fadeOut(tween(organicExitMillis)) +
455	                                slideOutHorizontally(tween(organicEnterMillis)) { -it / 28 } +
456	                                scaleOut(tween(organicEnterMillis), targetScale = 1.008f)
457	                            resolvedVisualStyle == VisualStyle.ORGANIC_FUTURE || customMotionDisabled ->
458	                                ExitTransition.None
459	                            materialPages -> fadeOut(tween(120))
460	                            glassPages -> fadeOut(tween(160)) +
461	                                scaleOut(tween(220), targetScale = 1.04f)
462	                            else -> fadeOut(tween(standardMotionMillis))
463	                        }
464	                    },
465	                    popEnterTransition = {
466	                        when {
467	                            organicMotionEnabled -> fadeIn(tween(organicEnterMillis)) +
468	                                slideInHorizontally(tween(organicEnterMillis)) { -it / 20 } +
469	                                scaleIn(tween(organicEnterMillis), initialScale = 0.992f)
470	                            resolvedVisualStyle == VisualStyle.ORGANIC_FUTURE || customMotionDisabled ->
471	                                EnterTransition.None
472	                            materialPages -> fadeIn(tween(220, delayMillis = 60))
473	                            glassPages -> fadeIn(tween(200)) +
474	                                scaleIn(spring(dampingRatio = 0.74f, stiffness = 320f), initialScale = 1.05f)
475	                            else -> fadeIn(tween(standardMotionMillis))
476	                        }
477	                    },
478	                    popExitTransition = {
479	                        when {
480	                            organicMotionEnabled -> fadeOut(tween(organicExitMillis)) +
481	                                slideOutHorizontally(tween(organicEnterMillis)) { it / 28 } +
482	                                scaleOut(tween(organicEnterMillis), targetScale = 1.008f)
483	                            resolvedVisualStyle == VisualStyle.ORGANIC_FUTURE || customMotionDisabled ->
484	                                ExitTransition.None
485	                            materialPages -> fadeOut(tween(140)) +

... (output truncated to budget; the source above is complete and verbatim — treat it as already Read. For any area not covered, run another codegraph_explore with the specific names — do NOT Read these files.)

