# CodeGraph exploration

Query: DeskCubby Agent and AI context collection, permissions, background execution, data access, and UI integration

**Exploration: DeskCubby Agent and AI context collection, permissions, background execution, data access, and UI integration**

Found 2 symbols across 1 file.

**Blast radius — what depends on these (update/verify before editing)**

- `access` (android/app/src/main/java/com/deskcubby/app/ui/reader/PdfiumPdfReader.kt:70) — 3 callers in `android/app/src/main/java/com/deskcubby/app/ui/reader/PdfiumPdfReader.kt`, `windows/scripts/copy-portable.mjs`; ⚠️ no covering tests found
- `execute` (android/app/src/main/java/com/deskcubby/app/agent/AgentToolExecutor.kt:18) — 2 callers in `android/app/src/main/java/com/deskcubby/app/agent/AgentRuntime.kt`, `android/app/src/main/java/com/deskcubby/app/agent/AgentContracts.kt`; ⚠️ no covering tests found
- `DefaultAgentContextProvider` (android/app/src/main/java/com/deskcubby/app/agent/DefaultAgentContextProvider.kt:7) — 3 callers in `android/app/src/main/java/com/deskcubby/app/di/AgentModule.kt`; tests: `android/app/src/test/java/com/deskcubby/app/agent/AgentContextProviderTest.kt`
- `permissionsToRequest` (android/app/src/main/java/com/deskcubby/app/data/statistics/StepHealthConnectAccess.kt:97) — 1 caller in `android/app/src/main/java/com/deskcubby/app/data/statistics/StepStatisticsRepository.kt`; ⚠️ no covering tests found

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
- ... and 110 more

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
- refresh → DETAIL_HEALTH_CONNECT
- refresh → DETAIL_STEP_PERMISSION
- ... and 14 more

**Source Code**

> The code below is the **verbatim, current on-disk source** of these files — re-read from disk on this call and line-numbered, byte-for-byte identical to what the Read tool returns. It is NOT a summary, outline, or stale cache. Treat each block as a Read you have already performed: do not Read a file shown here.

**`android/app/src/main/java/com/deskcubby/app/ui/Navigation.kt`** — calls(calls), references(references), NavItemId(references), SettingsStartPage(references), VisualStyle(references), StepStatisticsScreen(calls), open(calls), DeskCubbyRoot(function), DeskCubbyTheme(calls)

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
481	                        val poetryBookViewModel: PoetryBookViewModel = hiltViewModel()
482	                        PoetryBookScreen(
483	                            padding = padding,
484	                            viewModel = poetryBookViewModel,
485	                            settings = settings,
486	                            onOpenSettings = {
487	                                navController.navigate(Routes.POETRY_SETTINGS)
488	                            },
489	                        )
490	                    }
491	                    composable(NavItemId.RSS.route) {
492	                        val rssViewModel: RssViewModel = hiltViewModel()
493	                        RssScreen(
494	                            padding = padding,
495	                            viewModel = rssViewModel,
496	                            onOpenArticle = { articleUrl ->
497	                                if (blogViewModel.openTrustedArticleUrl(articleUrl)) {
498	                                    navController.navigate(NavItemId.BLOG.route) {
499	                                        launchSingleTop = true
500	                                    }
501	                                    true
502	                                } else {
503	                                    false
504	                                }
505	                            },
506	                        )
507	                    }
508	                    composable(NavItemId.AI_CHAT.route) {
509	                        val aiChatViewModel: AiChatViewModel = hiltViewModel()
510	                        val initialPrompt = pendingAiPrompt
511	                        LaunchedEffect(initialPrompt) {
512	                            if (initialPrompt != null) pendingAiPrompt = null
513	                        }
514	                        AiChatScreen(
515	                            padding = padding,
516	                            viewModel = aiChatViewModel,
517	                            onOpenSettings = { navController.navigate(Routes.AI_SETTINGS) },
518	                            onOpenReview = { navController.navigate(Routes.AI_REVIEW) },
519	                            initialPrompt = initialPrompt,
520	                        )
521	                    }
522	                    composable(Routes.AI_REVIEW) {
523	                        val reviewViewModel: AgentReviewViewModel = hiltViewModel()
524	                        AgentReviewScreen(
525	                            padding = padding,
526	                            viewModel = reviewViewModel,
527	                            onBack = { navController.popBackStack() },
528	                        )
529	                    }
530	                    composable(NavItemId.VAULT.route) {
531	                        val vaultViewModel: VaultViewModel = hiltViewModel()
532	                        VaultScreen(padding = padding, viewModel = vaultViewModel, settings = settings)
533	                    }
534	                    composable(NavItemId.READER.route) {
535	                        val readerViewModel: ReaderViewModel = hiltViewModel()
536	                        ReaderScreen(
537	                            padding = padding,
538	                            viewModel = readerViewModel,
539	                            onReadingChanged = { readerOpen = it },
540	                            onTutorialTargetChanged = { childTutorialTarget = it },
541	                        )
542	                    }

... (output truncated to budget; the source above is complete and verbatim — treat it as already Read. For any area not covered, run another codegraph_explore with the specific names — do NOT Read these files.)

