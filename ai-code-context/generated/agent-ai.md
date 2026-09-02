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
- ... and 9 more

**references:**
- metadataPrompt → MAX_METADATA_CHARS
- permissionsToRequest → healthReadPermissions
- hasStepReadPermission → StepHealthConnectAccess
- hasHealthReadPermissions → StepHealthConnectAccess
- refresh → StepHealthConnectAccess
- StepStatisticsScreen → StepHealthConnectAccess
- commitMutation → UNDO_SCHEMA
- commitMutation → PLAN_SCHEMA
- reportHealthConnectOpenFailure → DETAIL_OPEN_HEALTH_CONNECT_FAILED
- refresh → DETAIL_PROVIDER_UPDATE_REQUIRED
- ... and 12 more

**Source Code**

> The code below is the **verbatim, current on-disk source** of these files — re-read from disk on this call and line-numbered, byte-for-byte identical to what the Read tool returns. It is NOT a summary, outline, or stale cache. Treat each block as a Read you have already performed: do not Read a file shown here.

**`android/app/src/main/java/com/deskcubby/app/ui/Navigation.kt`** — calls(calls), references(references), NavItemId(references), SettingsStartPage(references), VisualStyle(references), StepStatisticsScreen(calls), open(calls), DeskCubbyRoot(function), DeskCubbyTheme(calls)

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
477	                    composable(NavItemId.POETRY.route) {
478	                        val poetryBookViewModel: PoetryBookViewModel = hiltViewModel()
479	                        PoetryBookScreen(
480	                            padding = padding,
481	                            viewModel = poetryBookViewModel,
482	                            settings = settings,
483	                            onOpenSettings = {
484	                                navController.navigate(Routes.POETRY_SETTINGS)
485	                            },
486	                        )
487	                    }
488	                    composable(NavItemId.RSS.route) {
489	                        val rssViewModel: RssViewModel = hiltViewModel()
490	                        RssScreen(
491	                            padding = padding,
492	                            viewModel = rssViewModel,
493	                            onOpenArticle = { articleUrl ->
494	                                if (blogViewModel.openTrustedArticleUrl(articleUrl)) {
495	                                    navController.navigate(NavItemId.BLOG.route) {
496	                                        launchSingleTop = true
497	                                    }
498	                                    true
499	                                } else {
500	                                    false
501	                                }
502	                            },
503	                        )
504	                    }
505	                    composable(NavItemId.AI_CHAT.route) {
506	                        val aiChatViewModel: AiChatViewModel = hiltViewModel()
507	                        val initialPrompt = pendingAiPrompt
508	                        LaunchedEffect(initialPrompt) {
509	                            if (initialPrompt != null) pendingAiPrompt = null
510	                        }
511	                        AiChatScreen(
512	                            padding = padding,
513	                            viewModel = aiChatViewModel,
514	                            onOpenSettings = { navController.navigate(Routes.AI_SETTINGS) },
515	                            onOpenReview = { navController.navigate(Routes.AI_REVIEW) },
516	                            initialPrompt = initialPrompt,
517	                        )
518	                    }
519	                    composable(Routes.AI_REVIEW) {
520	                        val reviewViewModel: AgentReviewViewModel = hiltViewModel()
521	                        AgentReviewScreen(
522	                            padding = padding,
523	                            viewModel = reviewViewModel,
524	                            onBack = { navController.popBackStack() },
525	                        )
526	                    }
527	                    composable(NavItemId.VAULT.route) {
528	                        val vaultViewModel: VaultViewModel = hiltViewModel()
529	                        VaultScreen(padding = padding, viewModel = vaultViewModel, settings = settings)
530	                    }
531	                    composable(NavItemId.READER.route) {
532	                        val readerViewModel: ReaderViewModel = hiltViewModel()
533	                        ReaderScreen(
534	                            padding = padding,
535	                            viewModel = readerViewModel,
536	                            onReadingChanged = { readerOpen = it },
537	                            onTutorialTargetChanged = { childTutorialTarget = it },

... (output truncated to budget; the source above is complete and verbatim — treat it as already Read. For any area not covered, run another codegraph_explore with the specific names — do NOT Read these files.)

