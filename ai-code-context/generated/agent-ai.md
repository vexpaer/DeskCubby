# CodeGraph exploration

Query: DeskCubby Agent and AI context collection, permissions, background execution, data access, and UI integration

**Exploration: DeskCubby Agent and AI context collection, permissions, background execution, data access, and UI integration**

Found 2 symbols across 1 file.

**Blast radius — what depends on these (update/verify before editing)**

- `access` (android/app/src/main/java/com/deskcubby/app/ui/reader/PdfiumPdfReader.kt:70) — 3 callers in `android/app/src/main/java/com/deskcubby/app/ui/reader/PdfiumPdfReader.kt`, `windows/scripts/copy-portable.mjs`; ⚠️ no covering tests found
- `execute` (android/app/src/main/java/com/deskcubby/app/agent/AgentToolExecutor.kt:18) — 2 callers in `android/app/src/main/java/com/deskcubby/app/agent/AgentRuntime.kt`, `android/app/src/main/java/com/deskcubby/app/agent/AgentContracts.kt`; ⚠️ no covering tests found
- `DefaultAgentContextProvider` (android/app/src/main/java/com/deskcubby/app/agent/DefaultAgentContextProvider.kt:7) — 3 callers in `android/app/src/main/java/com/deskcubby/app/di/AgentModule.kt`; tests: `android/app/src/test/java/com/deskcubby/app/agent/AgentContextProviderTest.kt`
- `permissionsToRequest` (android/app/src/main/java/com/deskcubby/app/data/statistics/StepHealthConnectAccess.kt:101) — 1 caller in `android/app/src/main/java/com/deskcubby/app/data/statistics/StepStatisticsRepository.kt`; ⚠️ no covering tests found

**Relationships**

**implements:**
- DefaultAgentContextProvider → AgentContextProvider
- UndoTool → AgentTool
- DataMutationTool → AgentTool
- FileMutationTool → AgentTool
- AppSettingMutationTool → AgentTool
- FunctionalAgentTool → AgentTool
- MutationTool → AgentTool
- AgentToolExecutor → AgentToolExecutionGateway
- RecordingExecutor → AgentToolExecutionGateway
- DeskCubbyDataApiAdapter → DeskCubbyDataAPI
- ... and 2 more

**calls:**
- renderPdfiumPage → access
- extractPdfiumPageText → access
- renderPdfiumPage → recycle
- renderPdfiumPage → ensureWanted
- renderPdfiumPage → openPage
- renderPdfiumPage → readerPdfRenderSize
- renderPdfiumPage → own
- renderPdfiumPage → transfer
- renderPdfiumPage → releaseOwned
- PdfiumPage → renderPdfiumPage
- ... and 109 more

**instantiates:**
- PdfiumPdfReader → PdfiumDocumentSession
- renderPdfiumPage → ReaderPdfResourceOwner
- renderPdfiumPage → PdfiumRenderedPage
- execute → AgentExecutionUpdate
- execute → AgentToolResult
- contextRequestsMetadataOnlyForAuthorizedSources → DefaultAgentContextProvider
- emptyGrantDoesNotTouchAnyDataApi → DefaultAgentContextProvider
- tools → DataMutationTool
- toOutcome → AgentToolOutcome
- commitMutation → DeskCubbyMutationResult

**imports:**
- com.deskcubby.app.di → DefaultAgentContextProvider
- com.deskcubby.app.agent → DeskCubbyDataAPI
- com.deskcubby.app.di → AgentContextProvider
- com.deskcubby.app.di → AgentToolContributor
- com.deskcubby.app.di → AgentApprovalGateway
- com.deskcubby.app.di → AgentModelClient
- com.deskcubby.app.di → AgentPermissionManager
- com.deskcubby.app.di → AgentReviewRepository
- com.deskcubby.app.di → AgentReviewStore
- com.deskcubby.app.di → AgentToolExecutionGateway
- ... and 7 more

**references:**
- metadataPrompt → MAX_METADATA_CHARS
- permissionsToRequest → healthReadPermissions
- hasStepReadPermission → StepHealthConnectAccess
- hasHealthReadPermissions → StepHealthConnectAccess
- refresh → StepHealthConnectAccess
- StepStatisticsScreen → StepHealthConnectAccess
- commitMutation → UNDO_SCHEMA
- commitMutation → PLAN_SCHEMA
- healthReadPermissions → activeCaloriesReadPermission
- refresh → DETAIL_HEALTH_CONNECT
- ... and 15 more

**Source Code**

> The code below is the **verbatim, current on-disk source** of these files — re-read from disk on this call and line-numbered, byte-for-byte identical to what the Read tool returns. It is NOT a summary, outline, or stale cache. Treat each block as a Read you have already performed: do not Read a file shown here.

**`android/app/src/main/java/com/deskcubby/app/ui/Navigation.kt`** — calls(calls), references(references), NavItemId(references), VisualStyle(references), SettingsStartPage(references), instantiates(instantiates), PaletteEntryKind(references), StepStatisticsScreen(calls), open(calls), DeskCubbyRoot(function), +1 more

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
486	                                slideOutVertically(tween(220)) { it / 18 }
487	                            glassPages -> fadeOut(tween(160)) +
488	                                scaleOut(tween(220), targetScale = 0.93f)
489	                            else -> fadeOut(tween(standardMotionMillis))
490	                        }
491	                    },
492	                ) {
493	                    composable(NavItemId.HOME.route) {
494	                        HomeScreen(
495	                            padding = padding,
496	                            settings = settings,
497	                            cloudSyncStatus = cloudSyncStatus,
498	                            viewModel = homeViewModel,
499	                            onOpenDiary = { uri -> diaryViewModel.open(uri); navController.navigate(Routes.EDITOR) },
500	                            onOpenThoughts = { navController.navigate(NavItemId.THOUGHT.route) },
501	                            onOpenWebsite = { navController.navigate(NavItemId.BLOG.route) },
502	                            onOpenDateRecords = { navController.navigate(NavItemId.DATE.route) },
503	                            onOpenDailyRecords = { navController.navigate(Routes.DAILY_RECORDS_TODAY) },
504	                            onOpenNotes = { navController.navigate(NavItemId.NOTES.route) },
505	                            onOpenGame = { gameId ->
506	                                requestedGameId = gameId
507	                                navController.navigate(Routes.GAME_SHORTCUT)
508	                            },
509	                            onOpenStatistics = {
510	                                navController.navigate(NavItemId.STATISTICS.route)
511	                            },
512	                            onOpenDesk = { navController.navigate(NavItemId.DESK.route) },
513	                            onOpenPalette = { paletteOpen = true },
514	                        )
515	                    }
516	                    composable(NavItemId.DESK.route) {
517	                        val deskViewModel: DeskViewModel = hiltViewModel()
518	                        DeskScreen(
519	                            padding = padding,
520	                            viewModel = deskViewModel,
521	                            onOpenDiary = { uri -> diaryViewModel.open(uri); navController.navigate(Routes.EDITOR) },
522	                            onOpenTodayDiary = { diaryViewModel.enterToday { navController.navigate(Routes.EDITOR) } },
523	                            onOpenIdea = { navController.navigate(NavItemId.THOUGHT.route) },
524	                            onOpenPhoto = { item ->
525	                                item.diaryUri?.let { diaryViewModel.open(it); navController.navigate(Routes.EDITOR) }
526	                            },
527	                            onOpenEvent = { navController.navigate(NavItemId.DATE.route) },
528	                            onOpenAi = { prompt ->
529	                                pendingAiPrompt = prompt
530	                                navController.navigate(NavItemId.AI_CHAT.route)
531	                            },
532	                            onOpenPalette = { paletteOpen = true },
533	                        )
534	                    }
535	                    composable(NavItemId.DIARY.route) {
536	                        DiaryListScreen(
537	                            padding = padding,
538	                            viewModel = diaryViewModel,
539	                            onOpen = { uri -> diaryViewModel.open(uri); navController.navigate(Routes.EDITOR) },
540	                            onOpenToday = { diaryViewModel.enterToday { navController.navigate(Routes.EDITOR) } },
541	                            onOpenMealCalendar = { navController.navigate(Routes.MEAL_CALENDAR) },
542	                            onOpenSettings = { navigateMain(NavItemId.SETTINGS.route) },
543	                        )
544	                    }
545	                    composable(NavItemId.BLOG.route) {
546	                        BlogScreen(
547	                            padding = padding,
548	                            viewModel = blogViewModel,
549	                            onCloseTrustedArticle = { navController.popBackStack() },
550	                        )
551	                    }
552	                    composable(NavItemId.THOUGHT.route) {
553	                        ThoughtScreen(
554	                            padding = padding,
555	                            viewModel = thoughtViewModel,
556	                            onTrash = { navController.navigate(Routes.THOUGHT_TRASH) },
557	                        )
558	                    }
559	                    composable(NavItemId.DATE.route) {
560	                        DateRecordScreen(padding = padding, viewModel = dateRecordViewModel)
561	                    }
562	                    composable(NavItemId.POETRY.route) {
563	                        val poetryBookViewModel: PoetryBookViewModel = hiltViewModel()

... (output truncated to budget; the source above is complete and verbatim — treat it as already Read. For any area not covered, run another codegraph_explore with the specific names — do NOT Read these files.)

