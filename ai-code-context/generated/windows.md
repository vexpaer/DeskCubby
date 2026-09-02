# CodeGraph exploration

Query: DeskCubby Windows and Tauri frontend/backend boundary, persistence, commands, and dependencies

**Exploration: DeskCubby Windows and Tauri frontend/backend boundary, persistence, commands, and dependencies**

Found 7 symbols across 2 files.

**Blast radius — what depends on these (update/verify before editing)**

- `command` (windows/src-tauri/src/rss.rs:189) — 4 callers in `windows/src-tauri/src/rss.rs`; ⚠️ no covering tests found
- `WindowInfo` (android/app/src/main/java/com/deskcubby/app/ui/components/AdaptiveLayout.kt:69) — 2 callers in `android/app/src/main/java/com/deskcubby/app/ui/components/AdaptiveLayout.kt`; tests: `android/app/src/test/java/com/deskcubby/app/ui/components/AdaptiveLayoutModeTest.kt`
- `CommandResult` (windows/src-tauri/src/security.rs:95) — 247 callers in `windows/src-tauri/src/ai.rs`, `windows/src-tauri/src/cloud_sync/commands.rs`, `windows/src-tauri/src/commands.rs`, `windows/src-tauri/src/games.rs` +3 more; ⚠️ no covering tests found

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
- ... and 243 more

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
- ... and 57 more

**instantiates:**
- rememberWindowInfo → WindowInfo
- info → WindowInfo

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
- ... and 2 more

**Source Code**

> The code below is the **verbatim, current on-disk source** of these files — re-read from disk on this call and line-numbered, byte-for-byte identical to what the Read tool returns. It is NOT a summary, outline, or stale cache. Treat each block as a Read you have already performed: do not Read a file shown here.

**`android/app/src/main/java/com/deskcubby/app/ui/theme/Theme.kt`** — calls(calls), VisualStyle(references), references(references), LocalVisualStyle(constant), LocalAppLanguage(constant), DeskCubbyTheme(function), tr(function), LocalAppLanguage(references)

```kotlin
55	import com.deskcubby.app.data.model.normalized
56	import androidx.core.view.WindowCompat
57	
58	val LocalVisualStyle: ProvidableCompositionLocal<VisualStyle> =
59	    staticCompositionLocalOf { VisualStyle.MATERIAL }
60	
61	val LocalAppLanguage: ProvidableCompositionLocal<AppLanguage> =
62	    staticCompositionLocalOf { AppLanguage.CHINESE }
63	
64	/** Compact mode tightens list/settings paddings across the app. */
65	val LocalCompactMode: ProvidableCompositionLocal<Boolean> =

... (gap) ...

111	
112	private val DefaultShapes = Shapes()
113	
114	@Composable
115	fun DeskCubbyTheme(settings: AppSettings, content: @Composable () -> Unit) {
116	    val dark = when (settings.darkMode) {
117	        DarkMode.SYSTEM -> isSystemInDarkTheme()
118	        DarkMode.LIGHT -> false
119	        DarkMode.DARK -> true
120	    }
121	    val customTheme = settings.customTheme.normalized()
122	    val effectiveStyle = settings.visualStyle.effectiveBaseStyle(customTheme)
123	    val baseScheme = resolveColorScheme(
124	        visualStyle = settings.visualStyle,
125	        dark = dark,
126	        themeColorArgb = settings.themeColorArgb,
127	        themeSecondaryColorsArgb = settings.themeSecondaryColorsArgb,
128	        customTheme = customTheme,
129	    )
130	    // The app background layer lives below every navigation destination. Making only the
131	    // page-background role transparent keeps cards, dialogs, and controls readable while allowing
132	    // Scaffold canvases to reveal the user-selected image.
133	    val scheme = if (settings.backgroundImageUri != null) {
134	        baseScheme.copy(background = Color.Transparent)
135	    } else {
136	        baseScheme
137	    }
138	    val baseTypography = if (effectiveStyle == VisualStyle.ORGANIC_FUTURE) {
139	        OrganicFutureTypography
140	    } else {
141	        AppTypography
142	    }
143	    val typography = scaledTypography(baseTypography, settings.fontScale)
144	    val shapes = when (settings.visualStyle) {
145	        VisualStyle.CUSTOM -> customShapes(customTheme.cornerRadiusDp.dp)
146	        else -> if (effectiveStyle == VisualStyle.ORGANIC_FUTURE) OrganicFutureShapes else DefaultShapes
147	    }
148	    val visualTokens = if (settings.visualStyle == VisualStyle.CUSTOM) {
149	        customVisualTokens(effectiveStyle, customTheme)
150	    } else {
151	        visualTokensFor(effectiveStyle)
152	    }
153	    val view = LocalView.current
154	    if (!view.isInEditMode) {
155	        SideEffect {
156	            view.context.findActivity()?.window?.let { window ->
157	                WindowCompat.getInsetsController(window, view).apply {
158	                    isAppearanceLightStatusBars = !dark
159	                    isAppearanceLightNavigationBars = !dark
160	                }
161	                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
162	                    window.isNavigationBarContrastEnforced = false
163	                    window.isStatusBarContrastEnforced = false
164	                }
165	            }
166	        }
167	    }
168	    androidx.compose.runtime.CompositionLocalProvider(
169	        // Existing screens only need the rendering behavior. Keeping CUSTOM out of this local
170	        // lets every established Material/Glass/Organic branch continue to work unchanged.
171	        LocalVisualStyle provides effectiveStyle,
172	        LocalAppLanguage provides settings.appLanguage,
173	        LocalCompactMode provides settings.compactMode,
174	        LocalDeskCubbyVisuals provides visualTokens,
175	        LocalOrganicFuturePrimaryColor provides Color(settings.themeColorArgb or 0xFF000000.toInt()),
176	        LocalOrganicFutureAccentColors provides organicFutureAccentColors(
177	            settings.themeSecondaryColorsArgb,
178	        ),
179	    ) {
180	        MaterialTheme(
181	            colorScheme = scheme,
182	            typography = typography,
183	            shapes = shapes,
184	            content = content,
185	        )
186	    }
187	}
188	
189	internal fun resolveColorScheme(
190	    visualStyle: VisualStyle,

... (gap) ...

367	private fun TextUnit.scaledBy(scale: Float): TextUnit =
368	    if (this == TextUnit.Unspecified) this else this * scale
369	
370	@Composable
371	fun tr(chinese: String, english: String): String =
372	    translate(chinese, english, LocalAppLanguage.current)
373	
374	/**
375	 * Language resolution shared by Compose screens ([tr]) and widget/RemoteViews rendering that
```

**`android/app/src/main/java/com/deskcubby/app/ui/Navigation.kt`** — calls(calls), references(references), NavItemId(references), SettingsStartPage(references), VisualStyle(references), DeskCubbyRoot(function), DeskCubbyTheme(calls), LocalVisualStyle(references), rememberWindowInfo(calls), resolveLayoutMode(calls), +3 more

```kotlin
193	    const val POETRY_SETTINGS = "settings/poetry"
194	}
195	
196	@Composable
197	fun DeskCubbyRoot(
198	    settingsViewModel: SettingsViewModel = hiltViewModel(),
199	    diaryViewModel: DiaryViewModel = hiltViewModel(),
200	    thoughtViewModel: ThoughtViewModel = hiltViewModel(),
201	    notesViewModel: NotesViewModel = hiltViewModel(),
202	    blogViewModel: BlogViewModel = hiltViewModel(),
203	    homeViewModel: HomeViewModel = hiltViewModel(),
204	    dateRecordViewModel: DateRecordViewModel = hiltViewModel(),
205	    structuredRecordsViewModel: StructuredRecordsViewModel = hiltViewModel(),
206	    externalNavigationRoute: String? = null,
207	    externalDiaryUri: String? = null,
208	    externalGameId: String? = null,
209	    onExternalNavigationHandled: () -> Unit = {},
210	) {
211	    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
212	    val ready by settingsViewModel.ready.collectAsStateWithLifecycle()
213	    val cloudSyncStatus by settingsViewModel.cloudSyncStatus.collectAsStateWithLifecycle()
214	
215	    // Device-local orientation lock: AUTO follows the sensor, PORTRAIT/LANDSCAPE pin the
216	    // activity. This controls rotation only; LayoutMode below decides UI structure from
217	    // the resulting window geometry. It reads the same context used by the Reader orientation
218	    // effect and is cleared when this composable (and its reader) leaves composition.
219	    // The Reader owns a per-book orientation preference that must win over the app-level
220	    // preference while reading. The global lock is therefore applied below, after the reader
221	    // open state is known, and is suspended while the reader is active.
222	    DeskCubbyTheme(settings) {
223	        AppBackground(settings) {
224	        if (!ready) {
225	            Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
226	                AppLoadingIndicator()
227	            }
228	            return@AppBackground
229	        }
230	        // First launch: before the navigation graph, ask for the UI language once. The device-
231	        // local flag keeps this from ever showing again and is not backed up.
232	        val languageSelected by settingsViewModel.languageSelected.collectAsStateWithLifecycle()
233	        if (!languageSelected) {
234	            FirstLaunchLanguageScreen(onChoose = settingsViewModel::chooseFirstLaunchLanguage)
235	            return@AppBackground
236	        }
237	        val navController = rememberNavController()
238	        val aliasContext = LocalContext.current
239	        LaunchedEffect(settings.useChineseLauncherName, settings.launcherIcon) {
240	            syncLauncherAlias(
241	                aliasContext,
242	                settings.useChineseLauncherName,
243	                settings.launcherIcon,
244	            )
245	        }
246	        val orientationActivity = LocalContext.current.findActivityCompat()
247	        var settingsSubpageOpen by remember { mutableStateOf(false) }
248	        var readerOpen by remember { mutableStateOf(false) }
249	        // Apply the app-level orientation lock only while the reader is not the active
250	        // surface; the reader's per-book orientation effect handles rotation while reading.
251	        OrientationPreferenceEffect(
252	            activity = orientationActivity,
253	            preference = settings.orientationPreference,
254	            suspendWhileReaderOpen = readerOpen,
255	        )
256	        var gameOpen by remember { mutableStateOf(false) }
257	        var requestedGameId by remember { mutableStateOf<String?>(null) }
258	        // One-shot prompt forwarded to the AI Chat screen (e.g. Desk's "总结今天"). Consumed by the
259	        // AI chat composable; the ViewModel guards against re-sending on rotation.
260	        var pendingAiPrompt by remember { mutableStateOf<String?>(null) }
261	        var childTutorialTarget by remember { mutableStateOf<PageTutorialTarget?>(null) }
262	        var tutorialConfirmedThisSession by remember { mutableStateOf(emptySet<String>()) }
263	        val initialStartDestination = remember { settings.defaultPage.route }
264	        val systemAnimationsEnabled = remember { ValueAnimator.areAnimatorsEnabled() }
265	        val resolvedVisualStyle = LocalVisualStyle.current
266	        val rootVisuals = deskCubbyVisuals
267	        val customMotionDisabled = rootVisuals.customized && rootVisuals.transitionMillis == 0
268	        val organicMotionEnabled = resolvedVisualStyle == VisualStyle.ORGANIC_FUTURE &&
269	            systemAnimationsEnabled && !customMotionDisabled
270	        val organicEnterMillis = if (rootVisuals.customized) rootVisuals.transitionMillis else 340
271	        val organicExitMillis = if (rootVisuals.customized) {
272	            (rootVisuals.transitionMillis * 300 / 340f).roundToInt()
273	        } else {
274	            300
275	        }
276	        val standardMotionMillis = if (rootVisuals.customized) rootVisuals.transitionMillis else 700
277	        val backStack by navController.currentBackStackEntryAsState()
278	        val route = backStack?.destination?.route
279	        val windowInfo = rememberWindowInfo()
280	        val layoutMode = resolveLayoutMode(windowInfo)
281	        // Navigation placement follows ORIENTATION only: portrait -> bottom bar, landscape -> left
282	        // rail. LayoutMode (width) independently drives multi-pane content structure, so a portrait
283	        // tablet gets a bottom bar with two-pane content instead of a left navigation rail.
284	        val visibleTabs = settings.navItems.filter { it.visible || it.id == NavItemId.SETTINGS }
285	        val bottomSelectedRoute = route.takeIf { currentRoute ->
286	            visibleTabs.any { it.id.route == currentRoute }
287	        } ?: NavItemId.MORE.route.takeIf {
288	            route != null && settings.navItems.any { item ->
289	                item.id.route == route && item.showInMore
290	            }
291	        }
292	        val showBottomBar = !windowInfo.isLandscape &&
293	            route in NavItemId.entries.map { it.route } &&
294	            !(route == NavItemId.SETTINGS.route && settingsSubpageOpen) &&
295	            !(route == NavItemId.READER.route && readerOpen) &&
296	            !(route == NavItemId.GAMES.route && gameOpen) &&
297	            !WindowInsets.isImeVisible
298	        // The rail is shown in landscape on top-level destinations.
299	        val showWorkspaceRail = windowInfo.isLandscape &&
300	            route in NavItemId.entries.map { it.route }
301	        val navigateMain: (String) -> Unit = { destination ->
302	            navController.navigate(destination) {
303	                // Keep only the graph itself, so no tab can restore another tab's nested page.
304	                popUpTo(navController.graph.id) { saveState = false }
305	                launchSingleTop = true
306	                restoreState = false
307	            }
308	        }
309	        LaunchedEffect(externalNavigationRoute, externalDiaryUri, externalGameId) {
310	            when {
311	                !externalDiaryUri.isNullOrBlank() -> {
312	                    diaryViewModel.open(externalDiaryUri)
313	                    navController.navigate(Routes.EDITOR)
314	                }
315	                externalGameId != null && externalGameId in HOME_GAME_SHORTCUT_IDS -> {
316	                    requestedGameId = externalGameId
317	                    navController.navigate(Routes.GAME_SHORTCUT)
318	                }
319	                externalNavigationRoute == Routes.DAILY_RECORDS_TODAY ->
320	                    navController.navigate(Routes.DAILY_RECORDS_TODAY)
321	                externalNavigationRoute != null &&
322	                    NavItemId.entries.any { it.route == externalNavigationRoute } ->
323	                    navigateMain(externalNavigationRoute)
324	            }
325	            if (
326	                externalNavigationRoute != null || externalDiaryUri != null || externalGameId != null
327	            ) {
328	                onExternalNavigationHandled()
329	            }
330	        }
331	
332	        CompositionLocalProvider(LocalLayoutMode provides layoutMode) {
333	        Scaffold(
334	            modifier = Modifier.fillMaxSize(),
335	            containerColor = Color.Transparent,
336	            contentWindowInsets = WindowInsets(0, 0, 0, 0),
337	            bottomBar = {
338	                if (showBottomBar) {
339	                    DeskBottomBar(
340	                        items = visibleTabs,
341	                        selectedRoute = bottomSelectedRoute,
342	                        showLabels = settings.bottomNavShowLabels,
343	                        musicVisualizerEnabled = settings.musicVisualizerEnabled,
344	                        musicVisualizerStyle = settings.musicVisualizerStyle,
345	                        musicVisualizerFrequencyMode = settings.musicVisualizerFrequencyMode,
346	                        musicVisualizerMinFrequencyHz = settings.musicVisualizerMinFrequencyHz,
347	                        musicVisualizerMaxFrequencyHz = settings.musicVisualizerMaxFrequencyHz,
348	                        onSelected = { item -> navigateMain(item.id.route) },
349	                    )
350	                }
351	            },
352	        ) { padding ->
353	            Row(Modifier.fillMaxSize()) {
354	                if (showWorkspaceRail) {
355	                    DeskCubbyNavigationRail(
356	                        items = visibleTabs,
357	                        selectedRoute = bottomSelectedRoute,
358	                        onSelected = { item -> navigateMain(item.id.route) },
359	                        onOpenSettings = { navigateMain(NavItemId.SETTINGS.route) },
360	                    )
361	                }
362	                // Drawer sheets animate into negative local X while closed. Clip the content
363	                // column so that hidden/dragging pixels can never paint over the sibling rail.
364	                // The open sheet still begins at this column's x=0, flush with the rail.
365	                Box(Modifier.weight(1f).fillMaxSize().clipToBounds()) {
366	                NavHost(
367	                    navController = navController,
368	                    startDestination = initialStartDestination,
369	                    modifier = Modifier.fillMaxSize(),
370	                    enterTransition = {
371	                        when {
372	                            organicMotionEnabled -> fadeIn(tween(organicEnterMillis)) +
373	                                slideInHorizontally(tween(organicEnterMillis)) { it / 20 } +
374	                                scaleIn(tween(organicEnterMillis), initialScale = 0.992f)
375	                            resolvedVisualStyle == VisualStyle.ORGANIC_FUTURE || customMotionDisabled ->
376	                                EnterTransition.None
377	                            else -> fadeIn(tween(standardMotionMillis))
378	                        }
379	                    },
380	                    exitTransition = {
381	                        when {
382	                            organicMotionEnabled -> fadeOut(tween(organicExitMillis)) +
383	                                slideOutHorizontally(tween(organicEnterMillis)) { -it / 28 } +
384	                                scaleOut(tween(organicEnterMillis), targetScale = 1.008f)
385	                            resolvedVisualStyle == VisualStyle.ORGANIC_FUTURE || customMotionDisabled ->
386	                                ExitTransition.None
387	                            else -> fadeOut(tween(standardMotionMillis))
388	                        }
389	                    },
390	                    popEnterTransition = {
391	                        when {
392	                            organicMotionEnabled -> fadeIn(tween(organicEnterMillis)) +
393	                                slideInHorizontally(tween(organicEnterMillis)) { -it / 20 } +
394	                                scaleIn(tween(organicEnterMillis), initialScale = 0.992f)
395	                            resolvedVisualStyle == VisualStyle.ORGANIC_FUTURE || customMotionDisabled ->
396	                                EnterTransition.None
397	                            else -> fadeIn(tween(standardMotionMillis))
398	                        }
399	                    },
400	                    popExitTransition = {
401	                        when {
402	                            organicMotionEnabled -> fadeOut(tween(organicExitMillis)) +
403	                                slideOutHorizontally(tween(organicEnterMillis)) { it / 28 } +
404	                                scaleOut(tween(organicEnterMillis), targetScale = 1.008f)
405	                            resolvedVisualStyle == VisualStyle.ORGANIC_FUTURE || customMotionDisabled ->
406	                                ExitTransition.None
407	                            else -> fadeOut(tween(standardMotionMillis))
408	                        }
409	                    },
410	                ) {
411	                    composable(NavItemId.HOME.route) {
412	                        HomeScreen(
413	                            padding = padding,
414	                            settings = settings,
415	                            cloudSyncStatus = cloudSyncStatus,
416	                            viewModel = homeViewModel,
417	                            onOpenDiary = { uri -> diaryViewModel.open(uri); navController.navigate(Routes.EDITOR) },
418	                            onOpenThoughts = { navController.navigate(NavItemId.THOUGHT.route) },
419	                            onOpenWebsite = { navController.navigate(NavItemId.BLOG.route) },
420	                            onOpenDateRecords = { navController.navigate(NavItemId.DATE.route) },
421	                            onOpenDailyRecords = { navController.navigate(Routes.DAILY_RECORDS_TODAY) },
422	                            onOpenNotes = { navController.navigate(NavItemId.NOTES.route) },
423	                            onOpenGame = { gameId ->
424	                                requestedGameId = gameId
425	                                navController.navigate(Routes.GAME_SHORTCUT)
426	                            },
427	                            onOpenStatistics = {
428	                                navController.navigate(NavItemId.STATISTICS.route)
429	                            },
430	                        )
431	                    }
432	                    composable(NavItemId.DESK.route) {
433	                        val deskViewModel: DeskViewModel = hiltViewModel()
434	                        DeskScreen(
435	                            padding = padding,
436	                            viewModel = deskViewModel,
437	                            onOpenDiary = { uri -> diaryViewModel.open(uri); navController.navigate(Routes.EDITOR) },
438	                            onOpenTodayDiary = { diaryViewModel.enterToday { navController.navigate(Routes.EDITOR) } },
439	                            onOpenIdea = { navController.navigate(NavItemId.THOUGHT.route) },
440	                            onOpenPhoto = { item ->
441	                                item.diaryUri?.let { diaryViewModel.open(it); navController.navigate(Routes.EDITOR) }
442	                            },
443	                            onOpenEvent = { navController.navigate(NavItemId.DATE.route) },
444	                            onOpenAi = { prompt ->
445	                                pendingAiPrompt = prompt
446	                                navController.navigate(NavItemId.AI_CHAT.route)
447	                            },
448	                        )
449	                    }
450	                    composable(NavItemId.DIARY.route) {
451	                        DiaryListScreen(
452	                            padding = padding,
453	                            viewModel = diaryViewModel,
454	                            onOpen = { uri -> diaryViewModel.open(uri); navController.navigate(Routes.EDITOR) },
455	                            onOpenToday = { diaryViewModel.enterToday { navController.navigate(Routes.EDITOR) } },
456	                            onOpenMealCalendar = { navController.navigate(Routes.MEAL_CALENDAR) },
457	                            onOpenSettings = { navigateMain(NavItemId.SETTINGS.route) },
458	                        )
459	                    }
460	                    composable(NavItemId.BLOG.route) {
461	                        BlogScreen(
462	                            padding = padding,
463	                            viewModel = blogViewModel,
464	                            onCloseTrustedArticle = { navController.popBackStack() },
465	                        )
466	                    }
467	                    composable(NavItemId.THOUGHT.route) {
468	                        ThoughtScreen(
469	                            padding = padding,
470	                            viewModel = thoughtViewModel,
471	                            onTrash = { navController.navigate(Routes.THOUGHT_TRASH) },
472	                        )
473	                    }
474	                    composable(NavItemId.DATE.route) {
475	                        DateRecordScreen(padding = padding, viewModel = dateRecordViewModel)
476	                    }

... (output truncated to budget; the source above is complete and verbatim — treat it as already Read. For any area not covered, run another codegraph_explore with the specific names — do NOT Read these files.)

