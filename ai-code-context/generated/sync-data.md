# CodeGraph exploration

Query: DeskCubby sync, export, diary, structured records, notes, poems, categories, and their main call paths

**Exploration: DeskCubby sync, export, diary, structured records, notes, poems, categories, and their main call paths**

Found 4 symbols across 2 files.

**Blast radius — what depends on these (update/verify before editing)**

- `Record` (android/plugin-api/core/src/main/java/com/deskcubby/plugin/api/core/PluginManager.kt:14) — 1 caller in `android/plugin-api/core/src/main/java/com/deskcubby/plugin/api/core/PluginManager.kt`; ⚠️ no covering tests found

**Relationships**

**implements:**
- Category → NormalizedFieldValue
- Category → PoetryCategoryFilter
- Category → ThoughtCategoryFilter
- Category → ValueKind
- Word → NormalizedFieldValue
- Number → NormalizedFieldValue
- Time → NormalizedFieldValue
- Duration → NormalizedFieldValue
- Word → ValueKind
- Number → ValueKind
- ... and 6 more

**instantiates:**
- android_float_conversion_boundaries_are_reproduced → Number
- register → Record
- providePluginManager → PluginManager
- testPluginCanBeRegisteredLoadedAndUnloaded → PluginManager
- duplicatePluginIdIsRejectedBeforeLifecycleStarts → PluginManager
- failedLoadCleansOwnerContextAndDoesNotBlockOtherPlugins → PluginManager
- snapshots → PluginSnapshot
- coordinator → CloudSyncCoordinator
- newEngine → DiaryCloudSyncLocalStore
- newEngine → NotesCloudSyncLocalStore
- ... and 2 more

**references:**
- PoetryBookScreen → PoetryCategoryFilter
- PoetryCategoryFilterBar → PoetryCategoryFilter
- move → PoetryCategoryFilter
- PoetryBookScreen → PoetryOperationFailure
- PoetryBookScreen → PoemEditContentStatus
- ThoughtCategoryDrawer → ThoughtCategoryFilter
- ThoughtCategoryPickerDialog → ThoughtCategoryFilter
- categoryLabel → ThoughtCategoryFilter
- ThoughtScreen → ThoughtCategoryFilter
- move → ThoughtCategoryFilter
- ... and 12 more

**imports:**
- com.deskcubby.app.ui.poetry → PoetryCategoryEntity
- com.deskcubby.app.ui.poetry → SavedPoemEntity
- com.deskcubby.app.ui.poetry → PoemEditContentStatus
- com.deskcubby.app.ui.poetry → PoetryBookRepository
- com.deskcubby.app.ui.poetry → PoetryPresetCategorySummary
- com.deskcubby.app.ui.poetry → PoetryPresetImportResult
- com.deskcubby.app.ui.poetry → PoetryRepository
- com.deskcubby.app.ui.poetry → launch
- com.deskcubby.app.ui.home → ThoughtCategoryFilter
- com.deskcubby.app.ui.thought → FlashThoughtEntity
- ... and 17 more

**calls:**
- PoetryBookScreen → tr
- PoetryBookScreen → filter
- PoetryBookScreen → rememberPoetryFontFamily
- PoetryBookScreen → PoetryCategoryFilterBar
- PoetryBookScreen → findDragTargetIndex
- PoetryBookScreen → move
- register → toValidatedDescriptor
- testPluginCanBeRegisteredLoadedAndUnloaded → register
- duplicatePluginIdIsRejectedBeforeLifecycleStarts → register
- failedLoadCleansOwnerContextAndDoesNotBlockOtherPlugins → register
- ... and 138 more

**Source Code**

> The code below is the **verbatim, current on-disk source** of these files — re-read from disk on this call and line-numbered, byte-for-byte identical to what the Read tool returns. It is NOT a summary, outline, or stale cache. Treat each block as a Read you have already performed: do not Read a file shown here.

**`android/app/src/main/java/com/deskcubby/app/ui/theme/Theme.kt`** — calls(calls), references(references), DeskCubbyTheme(function), tr(function)

```kotlin
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

**`android/app/src/main/java/com/deskcubby/app/ui/Navigation.kt`** — references(references), calls(calls), SettingsStartPage(references), StructuredRecordsScreen(calls), DeskCubbyRoot(function), DeskCubbyTheme(calls), filter(calls), ThoughtScreen(calls), PoetryBookScreen(calls)

```kotlin
196	    const val POETRY_SETTINGS = "settings/poetry"
197	}
198	
199	@Composable
200	fun DeskCubbyRoot(
201	    settingsViewModel: SettingsViewModel = hiltViewModel(),
202	    diaryViewModel: DiaryViewModel = hiltViewModel(),
203	    thoughtViewModel: ThoughtViewModel = hiltViewModel(),
204	    notesViewModel: NotesViewModel = hiltViewModel(),
205	    blogViewModel: BlogViewModel = hiltViewModel(),
206	    homeViewModel: HomeViewModel = hiltViewModel(),
207	    dateRecordViewModel: DateRecordViewModel = hiltViewModel(),
208	    structuredRecordsViewModel: StructuredRecordsViewModel = hiltViewModel(),
209	    externalNavigationRoute: String? = null,
210	    externalDiaryUri: String? = null,
211	    externalGameId: String? = null,
212	    onExternalNavigationHandled: () -> Unit = {},
213	) {
214	    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
215	    val ready by settingsViewModel.ready.collectAsStateWithLifecycle()
216	    val cloudSyncStatus by settingsViewModel.cloudSyncStatus.collectAsStateWithLifecycle()
217	
218	    // Device-local orientation lock: AUTO follows the sensor, PORTRAIT/LANDSCAPE pin the
219	    // activity. This controls rotation only; LayoutMode below decides UI structure from
220	    // the resulting window geometry. It reads the same context used by the Reader orientation
221	    // effect and is cleared when this composable (and its reader) leaves composition.
222	    // The Reader owns a per-book orientation preference that must win over the app-level
223	    // preference while reading. The global lock is therefore applied below, after the reader
224	    // open state is known, and is suspended while the reader is active.
225	    DeskCubbyTheme(settings) {
226	        AppBackground(settings) {
227	        if (!ready) {
228	            Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
229	                AppLoadingIndicator()
230	            }
231	            return@AppBackground
232	        }
233	        // First launch: before the navigation graph, ask for the UI language once. The device-
234	        // local flag keeps this from ever showing again and is not backed up.
235	        val languageSelected by settingsViewModel.languageSelected.collectAsStateWithLifecycle()
236	        if (!languageSelected) {
237	            FirstLaunchLanguageScreen(onChoose = settingsViewModel::chooseFirstLaunchLanguage)
238	            return@AppBackground
239	        }
240	        val navController = rememberNavController()
241	        val aliasContext = LocalContext.current
242	        LaunchedEffect(settings.useChineseLauncherName, settings.launcherIcon) {
243	            syncLauncherAlias(
244	                aliasContext,
245	                settings.useChineseLauncherName,
246	                settings.launcherIcon,
247	            )
248	        }
249	        val orientationActivity = LocalContext.current.findActivityCompat()
250	        var settingsSubpageOpen by remember { mutableStateOf(false) }
251	        var readerOpen by remember { mutableStateOf(false) }
252	        // Apply the app-level orientation lock only while the reader is not the active
253	        // surface; the reader's per-book orientation effect handles rotation while reading.
254	        OrientationPreferenceEffect(
255	            activity = orientationActivity,
256	            preference = settings.orientationPreference,
257	            suspendWhileReaderOpen = readerOpen,
258	        )
259	        var gameOpen by remember { mutableStateOf(false) }
260	        var requestedGameId by remember { mutableStateOf<String?>(null) }
261	        // One-shot prompt forwarded to the AI Chat screen (e.g. Desk's "总结今天"). Consumed by the
262	        // AI chat composable; the ViewModel guards against re-sending on rotation.
263	        var pendingAiPrompt by remember { mutableStateOf<String?>(null) }
264	        var childTutorialTarget by remember { mutableStateOf<PageTutorialTarget?>(null) }
265	        var tutorialConfirmedThisSession by remember { mutableStateOf(emptySet<String>()) }
266	        val initialStartDestination = remember { settings.defaultPage.route }
267	        val systemAnimationsEnabled = remember { ValueAnimator.areAnimatorsEnabled() }
268	        val resolvedVisualStyle = LocalVisualStyle.current
269	        val rootVisuals = deskCubbyVisuals
270	        val customMotionDisabled = rootVisuals.customized && rootVisuals.transitionMillis == 0
271	        val organicMotionEnabled = resolvedVisualStyle == VisualStyle.ORGANIC_FUTURE &&
272	            systemAnimationsEnabled && !customMotionDisabled
273	        val organicEnterMillis = if (rootVisuals.customized) rootVisuals.transitionMillis else 340
274	        val organicExitMillis = if (rootVisuals.customized) {
275	            (rootVisuals.transitionMillis * 300 / 340f).roundToInt()
276	        } else {
277	            300
278	        }
279	        val standardMotionMillis = if (rootVisuals.customized) rootVisuals.transitionMillis else 700
280	        val backStack by navController.currentBackStackEntryAsState()
281	        val route = backStack?.destination?.route
282	        val windowInfo = rememberWindowInfo()
283	        val layoutMode = resolveLayoutMode(windowInfo)
284	        // Navigation placement follows ORIENTATION only: portrait -> bottom bar, landscape -> left
285	        // rail. LayoutMode (width) independently drives multi-pane content structure, so a portrait
286	        // tablet gets a bottom bar with two-pane content instead of a left navigation rail.
287	        val visibleTabs = settings.navItems.filter { it.visible || it.id == NavItemId.SETTINGS }
288	        val bottomSelectedRoute = route.takeIf { currentRoute ->
289	            visibleTabs.any { it.id.route == currentRoute }
290	        } ?: NavItemId.MORE.route.takeIf {
291	            route != null && settings.navItems.any { item ->
292	                item.id.route == route && item.showInMore
293	            }
294	        }
295	        val showBottomBar = !windowInfo.isLandscape &&
296	            route in NavItemId.entries.map { it.route } &&
297	            !(route == NavItemId.SETTINGS.route && settingsSubpageOpen) &&
298	            !(route == NavItemId.READER.route && readerOpen) &&
299	            !(route == NavItemId.GAMES.route && gameOpen) &&
300	            !WindowInsets.isImeVisible
301	        // The rail is shown in landscape on top-level destinations.
302	        val showWorkspaceRail = windowInfo.isLandscape &&
303	            route in NavItemId.entries.map { it.route }
304	        val navigateMain: (String) -> Unit = { destination ->
305	            navController.navigate(destination) {
306	                // Keep only the graph itself, so no tab can restore another tab's nested page.
307	                popUpTo(navController.graph.id) { saveState = false }
308	                launchSingleTop = true
309	                restoreState = false
310	            }
311	        }
312	        LaunchedEffect(externalNavigationRoute, externalDiaryUri, externalGameId) {
313	            when {
314	                !externalDiaryUri.isNullOrBlank() -> {
315	                    diaryViewModel.open(externalDiaryUri)
316	                    navController.navigate(Routes.EDITOR)
317	                }
318	                externalGameId != null && externalGameId in HOME_GAME_SHORTCUT_IDS -> {
319	                    requestedGameId = externalGameId
320	                    navController.navigate(Routes.GAME_SHORTCUT)
321	                }
322	                externalNavigationRoute == Routes.DAILY_RECORDS_TODAY ->
323	                    navController.navigate(Routes.DAILY_RECORDS_TODAY)
324	                externalNavigationRoute != null &&
325	                    NavItemId.entries.any { it.route == externalNavigationRoute } ->
326	                    navigateMain(externalNavigationRoute)
327	            }
328	            if (
329	                externalNavigationRoute != null || externalDiaryUri != null || externalGameId != null
330	            ) {
331	                onExternalNavigationHandled()
332	            }
333	        }
334	
335	        CompositionLocalProvider(LocalLayoutMode provides layoutMode) {
336	        Scaffold(
337	            modifier = Modifier.fillMaxSize(),
338	            containerColor = Color.Transparent,
339	            contentWindowInsets = WindowInsets(0, 0, 0, 0),
340	            bottomBar = {
341	                if (showBottomBar) {
342	                    DeskBottomBar(
343	                        items = visibleTabs,
344	                        selectedRoute = bottomSelectedRoute,
345	                        showLabels = settings.bottomNavShowLabels,
346	                        musicVisualizerEnabled = settings.musicVisualizerEnabled,
347	                        musicVisualizerStyle = settings.musicVisualizerStyle,
348	                        musicVisualizerFrequencyMode = settings.musicVisualizerFrequencyMode,
349	                        musicVisualizerMinFrequencyHz = settings.musicVisualizerMinFrequencyHz,
350	                        musicVisualizerMaxFrequencyHz = settings.musicVisualizerMaxFrequencyHz,
351	                        onSelected = { item -> navigateMain(item.id.route) },
352	                    )
353	                }
354	            },
355	        ) { padding ->
356	            Row(Modifier.fillMaxSize()) {
357	                if (showWorkspaceRail) {
358	                    DeskCubbyNavigationRail(
359	                        items = visibleTabs,
360	                        selectedRoute = bottomSelectedRoute,
361	                        onSelected = { item -> navigateMain(item.id.route) },
362	                        onOpenSettings = { navigateMain(NavItemId.SETTINGS.route) },
363	                    )
364	                }
365	                // Drawer sheets animate into negative local X while closed. Clip the content
366	                // column so that hidden/dragging pixels can never paint over the sibling rail.
367	                // The open sheet still begins at this column's x=0, flush with the rail.
368	                Box(Modifier.weight(1f).fillMaxSize().clipToBounds()) {
369	                NavHost(
370	                    navController = navController,
371	                    startDestination = initialStartDestination,
372	                    modifier = Modifier.fillMaxSize(),
373	                    enterTransition = {
374	                        when {
375	                            organicMotionEnabled -> fadeIn(tween(organicEnterMillis)) +
376	                                slideInHorizontally(tween(organicEnterMillis)) { it / 20 } +
377	                                scaleIn(tween(organicEnterMillis), initialScale = 0.992f)
378	                            resolvedVisualStyle == VisualStyle.ORGANIC_FUTURE || customMotionDisabled ->
379	                                EnterTransition.None
380	                            else -> fadeIn(tween(standardMotionMillis))
381	                        }
382	                    },
383	                    exitTransition = {
384	                        when {
385	                            organicMotionEnabled -> fadeOut(tween(organicExitMillis)) +
386	                                slideOutHorizontally(tween(organicEnterMillis)) { -it / 28 } +
387	                                scaleOut(tween(organicEnterMillis), targetScale = 1.008f)
388	                            resolvedVisualStyle == VisualStyle.ORGANIC_FUTURE || customMotionDisabled ->
389	                                ExitTransition.None
390	                            else -> fadeOut(tween(standardMotionMillis))
391	                        }
392	                    },
393	                    popEnterTransition = {
394	                        when {
395	                            organicMotionEnabled -> fadeIn(tween(organicEnterMillis)) +
396	                                slideInHorizontally(tween(organicEnterMillis)) { -it / 20 } +
397	                                scaleIn(tween(organicEnterMillis), initialScale = 0.992f)
398	                            resolvedVisualStyle == VisualStyle.ORGANIC_FUTURE || customMotionDisabled ->
399	                                EnterTransition.None
400	                            else -> fadeIn(tween(standardMotionMillis))
401	                        }
402	                    },
403	                    popExitTransition = {
404	                        when {
405	                            organicMotionEnabled -> fadeOut(tween(organicExitMillis)) +
406	                                slideOutHorizontally(tween(organicEnterMillis)) { it / 28 } +
407	                                scaleOut(tween(organicEnterMillis), targetScale = 1.008f)
408	                            resolvedVisualStyle == VisualStyle.ORGANIC_FUTURE || customMotionDisabled ->
409	                                ExitTransition.None
410	                            else -> fadeOut(tween(standardMotionMillis))
411	                        }
412	                    },
413	                ) {
414	                    composable(NavItemId.HOME.route) {
415	                        HomeScreen(
416	                            padding = padding,
417	                            settings = settings,
418	                            cloudSyncStatus = cloudSyncStatus,
419	                            viewModel = homeViewModel,
420	                            onOpenDiary = { uri -> diaryViewModel.open(uri); navController.navigate(Routes.EDITOR) },
421	                            onOpenThoughts = { navController.navigate(NavItemId.THOUGHT.route) },
422	                            onOpenWebsite = { navController.navigate(NavItemId.BLOG.route) },
423	                            onOpenDateRecords = { navController.navigate(NavItemId.DATE.route) },
424	                            onOpenDailyRecords = { navController.navigate(Routes.DAILY_RECORDS_TODAY) },
425	                            onOpenNotes = { navController.navigate(NavItemId.NOTES.route) },
426	                            onOpenGame = { gameId ->
427	                                requestedGameId = gameId
428	                                navController.navigate(Routes.GAME_SHORTCUT)
429	                            },
430	                            onOpenStatistics = {
431	                                navController.navigate(NavItemId.STATISTICS.route)
432	                            },
433	                        )
434	                    }
435	                    composable(NavItemId.DESK.route) {
436	                        val deskViewModel: DeskViewModel = hiltViewModel()
437	                        DeskScreen(
438	                            padding = padding,
439	                            viewModel = deskViewModel,
440	                            onOpenDiary = { uri -> diaryViewModel.open(uri); navController.navigate(Routes.EDITOR) },
441	                            onOpenTodayDiary = { diaryViewModel.enterToday { navController.navigate(Routes.EDITOR) } },
442	                            onOpenIdea = { navController.navigate(NavItemId.THOUGHT.route) },
443	                            onOpenPhoto = { item ->
444	                                item.diaryUri?.let { diaryViewModel.open(it); navController.navigate(Routes.EDITOR) }
445	                            },
446	                            onOpenEvent = { navController.navigate(NavItemId.DATE.route) },
447	                            onOpenAi = { prompt ->
448	                                pendingAiPrompt = prompt
449	                                navController.navigate(NavItemId.AI_CHAT.route)
450	                            },
451	                        )
452	                    }
453	                    composable(NavItemId.DIARY.route) {
454	                        DiaryListScreen(
455	                            padding = padding,
456	                            viewModel = diaryViewModel,
457	                            onOpen = { uri -> diaryViewModel.open(uri); navController.navigate(Routes.EDITOR) },
458	                            onOpenToday = { diaryViewModel.enterToday { navController.navigate(Routes.EDITOR) } },
459	                            onOpenMealCalendar = { navController.navigate(Routes.MEAL_CALENDAR) },
460	                            onOpenSettings = { navigateMain(NavItemId.SETTINGS.route) },
461	                        )
462	                    }
463	                    composable(NavItemId.BLOG.route) {
464	                        BlogScreen(
465	                            padding = padding,
466	                            viewModel = blogViewModel,
467	                            onCloseTrustedArticle = { navController.popBackStack() },
468	                        )
469	                    }
470	                    composable(NavItemId.THOUGHT.route) {
471	                        ThoughtScreen(
472	                            padding = padding,
473	                            viewModel = thoughtViewModel,
474	                            onTrash = { navController.navigate(Routes.THOUGHT_TRASH) },
475	                        )
476	                    }
477	                    composable(NavItemId.DATE.route) {
478	                        DateRecordScreen(padding = padding, viewModel = dateRecordViewModel)
479	                    }
480	                    composable(NavItemId.POETRY.route) {

... (output truncated to budget; the source above is complete and verbatim — treat it as already Read. For any area not covered, run another codegraph_explore with the specific names — do NOT Read these files.)

