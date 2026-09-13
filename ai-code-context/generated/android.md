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
- DeskCubbyRoot → STEPS_SETTINGS
- routeTutorialTarget → STEPS_SETTINGS
- DeskCubbyRoot → MEAL_FILTER_SETTINGS
- routeTutorialTarget → MEAL_FILTER_SETTINGS
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
92	import androidx.navigation.compose.composable
93	import androidx.navigation.compose.currentBackStackEntryAsState
94	import androidx.navigation.compose.rememberNavController
95	import com.deskcubby.app.data.model.NavItemConfig
96	import com.deskcubby.app.data.model.NavItemId
97	import com.deskcubby.app.data.model.HOME_GAME_SHORTCUT_IDS
98	import com.deskcubby.app.data.model.normalizeMorePageOrder
99	import com.deskcubby.app.data.model.AppLanguage
100	import com.deskcubby.app.data.model.AppSettings
101	import com.deskcubby.app.data.model.LayoutMode
102	import com.deskcubby.app.data.model.OrientationPreference
103	import com.deskcubby.app.data.model.VisualStyle
104	import com.deskcubby.app.data.model.MusicVisualizerStyle
105	import com.deskcubby.app.data.model.MusicVisualizerFrequencyMode
106	import com.deskcubby.app.ui.blog.BlogScreen
107	import com.deskcubby.app.ui.blog.BlogViewModel
108	import com.deskcubby.app.ui.components.AppLoadingIndicator
109	import com.deskcubby.app.ui.components.AppBackground
110	import com.deskcubby.app.ui.components.DeskCubbyNavigationRail
111	import com.deskcubby.app.ui.components.LocalLayoutMode
112	import com.deskcubby.app.ui.components.rememberWindowInfo
113	import com.deskcubby.app.ui.components.resolveLayoutMode
114	import com.deskcubby.app.ui.components.PageTutorialOverlay
115	import com.deskcubby.app.ui.components.PageTutorialTarget
116	import com.deskcubby.app.ui.components.MusicVisualizerLayer
117	import com.deskcubby.app.ui.diary.DiaryEditorScreen
118	import com.deskcubby.app.ui.diary.DiaryListScreen
119	import com.deskcubby.app.ui.diary.DiaryViewModel
120	import com.deskcubby.app.ui.diary.MealCalendarScreen
121	import com.deskcubby.app.ui.diary.CalorieEstimationProgressScreen
122	import com.deskcubby.app.ui.diary.filter.MealPhotoFilterSettingsScreen
123	import com.deskcubby.app.ui.structuredrecords.StructuredRecordsScreen
124	import com.deskcubby.app.ui.structuredrecords.StructuredRecordsViewModel
125	import com.deskcubby.app.ui.structuredstats.StructuredStatisticsScreen
126	import com.deskcubby.app.ui.structuredstats.StructuredStatisticsViewModel
127	import com.deskcubby.app.ui.date.DateRecordScreen
128	import com.deskcubby.app.ui.date.DateRecordViewModel
129	import com.deskcubby.app.ui.home.HomeScreen
130	import com.deskcubby.app.ui.home.HomeViewModel
131	import com.deskcubby.app.ui.desk.DeskScreen
132	import com.deskcubby.app.ui.desk.DeskViewModel
133	import com.deskcubby.app.ui.more.MoreHubScreen
134	import com.deskcubby.app.ui.notes.NoteEditorScreen
135	import com.deskcubby.app.ui.notes.NotesScreen
136	import com.deskcubby.app.ui.notes.NotesViewModel
137	import com.deskcubby.app.ui.poetry.PoetryBookScreen
138	import com.deskcubby.app.ui.poetry.PoetryBookViewModel
139	import com.deskcubby.app.ui.reader.ReaderScreen
140	import com.deskcubby.app.ui.reader.ReaderViewModel
141	import com.deskcubby.app.ui.settings.SettingsScreen
142	import com.deskcubby.app.ui.settings.SettingsStartPage
143	import com.deskcubby.app.ui.settings.SettingsViewModel
144	import com.deskcubby.app.ui.rss.RssScreen
145	import com.deskcubby.app.ui.rss.RssViewModel
146	import com.deskcubby.app.ui.steps.StepStatisticsScreen
147	import com.deskcubby.app.ui.steps.StepStatisticsViewModel
148	import com.deskcubby.app.ui.sleep.SleepStatisticsScreen
149	import com.deskcubby.app.ui.sleep.SleepStatisticsViewModel
150	import com.deskcubby.app.ui.statshub.StatisticsHubScreen
151	import com.deskcubby.app.ui.statshub.StatisticsHubViewModel
152	import com.deskcubby.app.ui.usage.UsageStatisticsScreen
153	import com.deskcubby.app.ui.usage.UsageStatisticsViewModel
154	import com.deskcubby.app.ui.ai.AiChatScreen
155	import com.deskcubby.app.ui.ai.AgentReviewScreen
156	import com.deskcubby.app.ui.ai.AgentReviewViewModel
157	import com.deskcubby.app.ui.ai.AiChatViewModel
158	import com.deskcubby.app.ui.theme.DeskCubbyTheme
159	import com.deskcubby.app.ui.theme.GlassPanel
160	import com.deskcubby.app.ui.theme.LocalAppLanguage
161	import com.deskcubby.app.ui.theme.LocalVisualStyle
162	import com.deskcubby.app.ui.theme.PanelRole
163	import com.deskcubby.app.ui.theme.deskCubbyVisuals
164	import com.deskcubby.app.ui.theme.tr
165	import com.deskcubby.app.ui.games.GamesScreen
166	import com.deskcubby.app.ui.games.GamesViewModel
167	import com.deskcubby.app.ui.thought.ThoughtScreen
168	import com.deskcubby.app.ui.thought.ThoughtTrashScreen
169	import com.deskcubby.app.ui.thought.ThoughtViewModel
170	import com.deskcubby.app.ui.vault.VaultScreen
171	import com.deskcubby.app.ui.vault.VaultViewModel
172	import com.deskcubby.app.ui.widgets.DesktopWidgetsScreen
173	import com.deskcubby.app.ui.widgets.DesktopWidgetsViewModel
174	import com.deskcubby.app.data.statistics.StepHealthConnectAccess
175	import kotlin.math.roundToInt
176	
177	object Routes {
178	    const val EDITOR = "diary_editor"
179	    const val NOTE_EDITOR = "note_editor"
180	    const val GAME_SHORTCUT = "game_shortcut"
181	    const val MEAL_CALENDAR = "meal_calendar"
182	    const val CALORIE_ESTIMATION_PROGRESS = "meal_calendar/calorie_progress"
183	    const val MEAL_FILTER_SETTINGS = "meal_filter_settings"
184	    const val THOUGHT_TRASH = "thought_trash"
185	    const val DAILY_RECORDS = "daily_records"
186	    const val DAILY_RECORDS_TODAY = "daily_records/today"
187	    const val NAVIGATION_SETTINGS = "settings/navigation"
188	    const val MORE_PAGE_SETTINGS = "settings/more-page"
189	    const val USAGE_SETTINGS = "settings/usage-statistics"
190	    const val STEPS_SETTINGS = "settings/step-statistics"
191	    const val STATISTICS_USAGE = "statistics/screen-time"
192	    const val STATISTICS_HEALTH = "statistics/health"
193	    const val STATISTICS_STRUCTURED = "statistics/structured"
194	    const val AI_SETTINGS = "settings/ai"
195	    const val AI_REVIEW = "ai/review"
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

... (output truncated to budget; the source above is complete and verbatim — treat it as already Read. For any area not covered, run another codegraph_explore with the specific names — do NOT Read these files.)

