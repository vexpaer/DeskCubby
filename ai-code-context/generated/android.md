# CodeGraph exploration

Query: DeskCubby Android UI, navigation, data layer, repositories, database, and background work

**Dynamic-dispatch links among your symbols**
(synthesized — the indirect hops grep/Read would reconstruct; the `@file:line` is the wiring site)

- AppShell → Navigation   [dynamic: renders <Navigation>]

> Full source for these symbols is below — the call flow among them, followed by their bodies.
**Exploration: DeskCubby Android UI, navigation, data layer, repositories, database, and background work**

Found 23 symbols across 1 file.

**Blast radius — what depends on these (update/verify before editing)**

- `DeskCubbyDataAPI` (android/plugin-api/core/src/main/java/com/deskcubby/plugin/api/core/api/DeskCubbyDataAPI.kt:9) — 7 callers in `android/app/src/main/java/com/deskcubby/app/agent/BuiltInAgentTools.kt`, `android/app/src/main/java/com/deskcubby/app/agent/DefaultAgentContextProvider.kt`, `android/app/src/main/java/com/deskcubby/app/di/AgentModule.kt`, `android/app/src/main/java/com/deskcubby/app/plugin/adapter/DeskCubbyDataApiAdapter.kt` +1 more; tests: `android/app/src/test/java/com/deskcubby/app/agent/AgentContextProviderTest.kt`, `android/plugin-api/core/src/test/java/com/deskcubby/plugin/api/core/PluginManagerTest.kt`
- `DeskCubbyDataPage` (android/plugin-api/core/src/main/java/com/deskcubby/plugin/api/core/api/DeskCubbyDataAPI.kt:47) — 3 callers in `android/app/src/main/java/com/deskcubby/app/plugin/adapter/DeskCubbyDataApiAdapter.kt`; tests: `android/app/src/test/java/com/deskcubby/app/agent/AgentContextProviderTest.kt`
- `DeskCubbyDataQuery` (android/plugin-api/core/src/main/java/com/deskcubby/plugin/api/core/api/DeskCubbyDataAPI.kt:37) — 4 callers in `android/app/src/main/java/com/deskcubby/app/agent/BuiltInAgentTools.kt`, `android/app/src/main/java/com/deskcubby/app/plugin/adapter/DeskCubbyDataApiAdapter.kt`; tests: `android/app/src/test/java/com/deskcubby/app/agent/AgentContextProviderTest.kt`
- `DeskCubbyDataEntry` (android/plugin-api/core/src/main/java/com/deskcubby/plugin/api/core/api/DeskCubbyDataAPI.kt:54) — 14 callers in `android/app/src/main/java/com/deskcubby/app/agent/BuiltInAgentTools.kt`, `android/app/src/main/java/com/deskcubby/app/plugin/adapter/DeskCubbyDataApiAdapter.kt`; tests: `android/app/src/test/java/com/deskcubby/app/agent/AgentContextProviderTest.kt`

**Relationships**

**references:**
- all → CloudSyncContent
- DeskCubbyRoot → NAVIGATION_SETTINGS
- routeTutorialTarget → NAVIGATION_SETTINGS
- DeskCubbyRoot → Routes
- routeTutorialTarget → Routes
- DeskCubbyRoot → STATISTICS_STRUCTURED
- routeTutorialTarget → STATISTICS_STRUCTURED
- DeskCubbyRoot → AI_REVIEW
- routeTutorialTarget → AI_REVIEW
- DeskCubbyRoot → MORE_PAGE_SETTINGS
- ... and 36 more

**imports:**
- com.deskcubby.app.data.sync → CloudSyncContent
- com.deskcubby.app.data.sync → SettingsRepository
- com.deskcubby.app.data.sync → ReaderBackground
- com.deskcubby.app.data.sync → ReaderBookType
- com.deskcubby.app.data.sync → ReaderChapterDetectionMode
- com.deskcubby.app.data.sync → ReaderPreferences
- com.deskcubby.app.data.sync → ReaderRepository
- com.deskcubby.app.data.sync → VaultEncryptedBackup
- com.deskcubby.app.data.sync → VaultEncryptedKeyBackup
- com.deskcubby.app.data.sync → VaultRepository
- ... and 27 more

**implements:**
- DeskCubbyDataApiAdapter → DeskCubbyDataAPI
- RecordingDataApi → DeskCubbyDataAPI

**calls:**
- sources → sources
- sources → sources
- list → list
- list → list
- read → read
- read → read
- prepareMutation → prepareMutation
- prepareMutation → prepareMutation
- databaseUsesKey → getById
- databaseUsesKey → decrypt
- ... and 45 more

**instantiates:**
- list → DeskCubbyDataPage
- listEntriesTool → DeskCubbyDataQuery
- read → DeskCubbyDataEntry
- entriesFor → DeskCubbyDataEntry
- prepareDataMutation → DeskCubbyDataEntry
- usageEntry → DeskCubbyDataEntry
- statisticsEntries → DeskCubbyDataEntry
- toEntry → DeskCubbyDataEntry
- toEntry → DeskCubbyDataEntry
- toEntry → DeskCubbyDataEntry
- ... and 5 more

**Source Code**

> The code below is the **verbatim, current on-disk source** of these files — re-read from disk on this call and line-numbered, byte-for-byte identical to what the Read tool returns. It is NOT a summary, outline, or stale cache. Treat each block as a Read you have already performed: do not Read a file shown here.

**`android/app/src/main/java/com/deskcubby/app/ui/Navigation.kt`** — calls(calls), imports(imports), Routes(references), NavItemId(references), SettingsStartPage(references), VisualStyle(references), references(references), NavItemId(imports), VisualStyle(imports), SettingsStartPage(imports), +26 more

```kotlin
91	import androidx.navigation.compose.composable
92	import androidx.navigation.compose.currentBackStackEntryAsState
93	import androidx.navigation.compose.rememberNavController
94	import com.deskcubby.app.data.model.NavItemConfig
95	import com.deskcubby.app.data.model.NavItemId
96	import com.deskcubby.app.data.model.HOME_GAME_SHORTCUT_IDS
97	import com.deskcubby.app.data.model.normalizeMorePageOrder
98	import com.deskcubby.app.data.model.AppLanguage
99	import com.deskcubby.app.data.model.AppSettings
100	import com.deskcubby.app.data.model.LayoutMode
101	import com.deskcubby.app.data.model.OrientationPreference
102	import com.deskcubby.app.data.model.VisualStyle
103	import com.deskcubby.app.data.model.MusicVisualizerStyle
104	import com.deskcubby.app.data.model.MusicVisualizerFrequencyMode
105	import com.deskcubby.app.ui.blog.BlogScreen
106	import com.deskcubby.app.ui.blog.BlogViewModel
107	import com.deskcubby.app.ui.components.AppLoadingIndicator
108	import com.deskcubby.app.ui.components.AppBackground
109	import com.deskcubby.app.ui.components.DeskCubbyNavigationRail
110	import com.deskcubby.app.ui.components.LocalLayoutMode
111	import com.deskcubby.app.ui.components.rememberWindowInfo
112	import com.deskcubby.app.ui.components.resolveLayoutMode
113	import com.deskcubby.app.ui.components.PageTutorialOverlay
114	import com.deskcubby.app.ui.components.PageTutorialTarget
115	import com.deskcubby.app.ui.components.MusicVisualizerLayer
116	import com.deskcubby.app.ui.diary.DiaryEditorScreen
117	import com.deskcubby.app.ui.diary.DiaryListScreen
118	import com.deskcubby.app.ui.diary.DiaryViewModel
119	import com.deskcubby.app.ui.diary.MealCalendarScreen
120	import com.deskcubby.app.ui.diary.CalorieEstimationProgressScreen
121	import com.deskcubby.app.ui.diary.filter.MealPhotoFilterSettingsScreen
122	import com.deskcubby.app.ui.structuredrecords.StructuredRecordsScreen
123	import com.deskcubby.app.ui.structuredrecords.StructuredRecordsViewModel
124	import com.deskcubby.app.ui.structuredstats.StructuredStatisticsScreen
125	import com.deskcubby.app.ui.structuredstats.StructuredStatisticsViewModel
126	import com.deskcubby.app.ui.date.DateRecordScreen
127	import com.deskcubby.app.ui.date.DateRecordViewModel
128	import com.deskcubby.app.ui.home.HomeScreen
129	import com.deskcubby.app.ui.home.HomeViewModel
130	import com.deskcubby.app.ui.desk.DeskScreen
131	import com.deskcubby.app.ui.desk.DeskViewModel
132	import com.deskcubby.app.ui.more.MoreHubScreen
133	import com.deskcubby.app.ui.notes.NoteEditorScreen
134	import com.deskcubby.app.ui.notes.NotesScreen
135	import com.deskcubby.app.ui.notes.NotesViewModel
136	import com.deskcubby.app.ui.poetry.PoetryBookScreen
137	import com.deskcubby.app.ui.poetry.PoetryBookViewModel
138	import com.deskcubby.app.ui.reader.ReaderScreen
139	import com.deskcubby.app.ui.reader.ReaderViewModel
140	import com.deskcubby.app.ui.settings.SettingsScreen
141	import com.deskcubby.app.ui.settings.SettingsStartPage
142	import com.deskcubby.app.ui.settings.SettingsViewModel
143	import com.deskcubby.app.ui.rss.RssScreen
144	import com.deskcubby.app.ui.rss.RssViewModel
145	import com.deskcubby.app.ui.steps.StepStatisticsScreen
146	import com.deskcubby.app.ui.steps.StepStatisticsViewModel
147	import com.deskcubby.app.ui.statshub.StatisticsHubScreen
148	import com.deskcubby.app.ui.statshub.StatisticsHubViewModel
149	import com.deskcubby.app.ui.usage.UsageStatisticsScreen
150	import com.deskcubby.app.ui.usage.UsageStatisticsViewModel
151	import com.deskcubby.app.ui.ai.AiChatScreen
152	import com.deskcubby.app.ui.ai.AgentReviewScreen
153	import com.deskcubby.app.ui.ai.AgentReviewViewModel
154	import com.deskcubby.app.ui.ai.AiChatViewModel
155	import com.deskcubby.app.ui.theme.DeskCubbyTheme
156	import com.deskcubby.app.ui.theme.GlassPanel
157	import com.deskcubby.app.ui.theme.LocalAppLanguage
158	import com.deskcubby.app.ui.theme.LocalVisualStyle
159	import com.deskcubby.app.ui.theme.PanelRole
160	import com.deskcubby.app.ui.theme.deskCubbyVisuals
161	import com.deskcubby.app.ui.theme.tr
162	import com.deskcubby.app.ui.games.GamesScreen
163	import com.deskcubby.app.ui.games.GamesViewModel
164	import com.deskcubby.app.ui.thought.ThoughtScreen
165	import com.deskcubby.app.ui.thought.ThoughtTrashScreen
166	import com.deskcubby.app.ui.thought.ThoughtViewModel
167	import com.deskcubby.app.ui.vault.VaultScreen
168	import com.deskcubby.app.ui.vault.VaultViewModel
169	import com.deskcubby.app.ui.widgets.DesktopWidgetsScreen
170	import com.deskcubby.app.ui.widgets.DesktopWidgetsViewModel
171	import com.deskcubby.app.data.statistics.StepHealthConnectAccess
172	import kotlin.math.roundToInt
173	
174	object Routes {
175	    const val EDITOR = "diary_editor"
176	    const val NOTE_EDITOR = "note_editor"
177	    const val GAME_SHORTCUT = "game_shortcut"
178	    const val MEAL_CALENDAR = "meal_calendar"
179	    const val CALORIE_ESTIMATION_PROGRESS = "meal_calendar/calorie_progress"
180	    const val MEAL_FILTER_SETTINGS = "meal_filter_settings"
181	    const val THOUGHT_TRASH = "thought_trash"
182	    const val DAILY_RECORDS = "daily_records"
183	    const val DAILY_RECORDS_TODAY = "daily_records/today"
184	    const val NAVIGATION_SETTINGS = "settings/navigation"
185	    const val MORE_PAGE_SETTINGS = "settings/more-page"
186	    const val USAGE_SETTINGS = "settings/usage-statistics"
187	    const val STEPS_SETTINGS = "settings/step-statistics"
188	    const val STATISTICS_USAGE = "statistics/screen-time"
189	    const val STATISTICS_HEALTH = "statistics/health"
190	    const val STATISTICS_STRUCTURED = "statistics/structured"
191	    const val AI_SETTINGS = "settings/ai"
192	    const val AI_REVIEW = "ai/review"
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

... (output truncated to budget; the source above is complete and verbatim — treat it as already Read. For any area not covered, run another codegraph_explore with the specific names — do NOT Read these files.)

