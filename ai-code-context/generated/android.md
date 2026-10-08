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
- DeskCubbyRoot → MORE_PAGE_SETTINGS
- routeTutorialTarget → MORE_PAGE_SETTINGS
- DeskCubbyRoot → USAGE_SETTINGS
- routeTutorialTarget → USAGE_SETTINGS
- DeskCubbyRoot → STATISTICS_STRUCTURED
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

**`android/app/src/main/java/com/deskcubby/app/ui/Navigation.kt`** — calls(calls), imports(imports), Routes(references), NavItemId(references), VisualStyle(references), instantiates(instantiates), SettingsStartPage(references), PaletteEntryKind(references), references(references), NavItemId(imports), +28 more

```kotlin
114	import androidx.navigation.compose.composable
115	import androidx.navigation.compose.currentBackStackEntryAsState
116	import androidx.navigation.compose.rememberNavController
117	import com.deskcubby.app.data.model.NavItemConfig
118	import com.deskcubby.app.data.model.NavItemId
119	import com.deskcubby.app.data.model.HOME_GAME_SHORTCUT_IDS
120	import com.deskcubby.app.data.model.normalizeMorePageOrder
121	import com.deskcubby.app.data.model.AppLanguage
122	import com.deskcubby.app.data.model.AppSettings
123	import com.deskcubby.app.data.model.LayoutMode
124	import com.deskcubby.app.data.model.OrientationPreference
125	import com.deskcubby.app.data.model.VisualStyle
126	import com.deskcubby.app.data.model.MusicVisualizerStyle
127	import com.deskcubby.app.data.model.MusicVisualizerFrequencyMode
128	import com.deskcubby.app.ui.blog.BlogScreen
129	import com.deskcubby.app.ui.blog.BlogViewModel
130	import com.deskcubby.app.ui.components.AppLoadingIndicator
131	import com.deskcubby.app.ui.components.AppBackground
132	import com.deskcubby.app.ui.theme.translate
133	import androidx.compose.ui.input.key.Key
134	import androidx.compose.ui.input.key.KeyEventType
135	import androidx.compose.ui.input.key.isCtrlPressed
136	import androidx.compose.ui.input.key.isMetaPressed
137	import androidx.compose.ui.input.key.key
138	import androidx.compose.ui.input.key.onPreviewKeyEvent
139	import androidx.compose.ui.input.key.type
140	import com.deskcubby.app.ui.components.CommandPalette
141	import com.deskcubby.app.ui.components.PaletteEntry
142	import com.deskcubby.app.ui.components.PaletteEntryKind
143	import com.deskcubby.app.ui.components.NavItemSpan
144	import com.deskcubby.app.ui.components.NavSelectionIndicator
145	import com.deskcubby.app.ui.theme.DeskMotion
146	import com.deskcubby.app.ui.theme.rememberDeskHaptics
147	import com.deskcubby.app.ui.components.DeskCubbyNavigationRail
148	import com.deskcubby.app.ui.components.LocalLayoutMode
149	import com.deskcubby.app.ui.components.rememberWindowInfo
150	import com.deskcubby.app.ui.components.resolveLayoutMode
151	import com.deskcubby.app.ui.components.PageTutorialOverlay
152	import com.deskcubby.app.ui.components.PageTutorialTarget
153	import com.deskcubby.app.ui.components.MusicVisualizerLayer
154	import com.deskcubby.app.ui.diary.DiaryEditorScreen
155	import com.deskcubby.app.ui.diary.DiaryListScreen
156	import com.deskcubby.app.ui.diary.DiaryViewModel
157	import com.deskcubby.app.ui.diary.MealCalendarScreen
158	import com.deskcubby.app.ui.diary.CalorieEstimationProgressScreen
159	import com.deskcubby.app.ui.diary.filter.MealPhotoFilterSettingsScreen
160	import com.deskcubby.app.ui.structuredrecords.StructuredRecordsScreen
161	import com.deskcubby.app.ui.structuredrecords.StructuredRecordsViewModel
162	import com.deskcubby.app.ui.structuredstats.StructuredStatisticsScreen
163	import com.deskcubby.app.ui.structuredstats.StructuredStatisticsViewModel
164	import com.deskcubby.app.ui.date.DateRecordScreen
165	import com.deskcubby.app.ui.date.DateRecordViewModel
166	import com.deskcubby.app.ui.home.HomeScreen
167	import com.deskcubby.app.ui.home.HomeViewModel
168	import com.deskcubby.app.ui.desk.DeskScreen
169	import com.deskcubby.app.ui.desk.DeskViewModel
170	import com.deskcubby.app.ui.more.MoreHubScreen
171	import com.deskcubby.app.ui.notes.NoteEditorScreen
172	import com.deskcubby.app.ui.notes.NotesScreen
173	import com.deskcubby.app.ui.notes.NotesViewModel
174	import com.deskcubby.app.ui.poetry.PoetryBookScreen
175	import com.deskcubby.app.ui.poetry.PoetryBookViewModel
176	import com.deskcubby.app.ui.reader.ReaderScreen
177	import com.deskcubby.app.ui.reader.ReaderViewModel
178	import com.deskcubby.app.ui.settings.SettingsScreen
179	import com.deskcubby.app.ui.settings.SettingsStartPage
180	import com.deskcubby.app.ui.settings.SettingsViewModel
181	import com.deskcubby.app.ui.rss.RssScreen
182	import com.deskcubby.app.ui.rss.RssViewModel
183	import com.deskcubby.app.ui.steps.StepStatisticsScreen
184	import com.deskcubby.app.ui.steps.StepStatisticsViewModel
185	import com.deskcubby.app.ui.sleep.SleepStatisticsScreen
186	import com.deskcubby.app.ui.sleep.SleepStatisticsViewModel
187	import com.deskcubby.app.ui.statshub.StatisticsHubScreen
188	import com.deskcubby.app.ui.statshub.StatisticsHubViewModel
189	import com.deskcubby.app.ui.usage.UsageStatisticsScreen
190	import com.deskcubby.app.ui.usage.UsageStatisticsViewModel
191	import com.deskcubby.app.ui.ai.AiChatScreen
192	import com.deskcubby.app.ui.ai.AgentReviewScreen
193	import com.deskcubby.app.ui.ai.AgentReviewViewModel
194	import com.deskcubby.app.ui.ai.AiChatViewModel
195	import com.deskcubby.app.ui.theme.DeskCubbyTheme
196	import com.deskcubby.app.ui.theme.GlassPanel
197	import com.deskcubby.app.ui.theme.LocalAppLanguage
198	import com.deskcubby.app.ui.theme.LocalVisualStyle
199	import com.deskcubby.app.ui.theme.PanelRole
200	import com.deskcubby.app.ui.theme.deskCubbyVisuals
201	import com.deskcubby.app.ui.theme.tr
202	import com.deskcubby.app.ui.games.GamesScreen
203	import com.deskcubby.app.ui.games.GamesViewModel
204	import com.deskcubby.app.ui.thought.ThoughtScreen
205	import com.deskcubby.app.ui.thought.ThoughtTrashScreen
206	import com.deskcubby.app.ui.thought.ThoughtViewModel
207	import com.deskcubby.app.ui.vault.VaultScreen
208	import com.deskcubby.app.ui.vault.VaultViewModel
209	import com.deskcubby.app.ui.widgets.DesktopWidgetsScreen
210	import com.deskcubby.app.ui.widgets.DesktopWidgetsViewModel
211	import com.deskcubby.app.data.statistics.StepHealthConnectAccess
212	import kotlin.math.roundToInt
213	
214	object Routes {
215	    const val EDITOR = "diary_editor"
216	    const val NOTE_EDITOR = "note_editor"
217	    const val GAME_SHORTCUT = "game_shortcut"
218	    const val MEAL_CALENDAR = "meal_calendar"
219	    const val CALORIE_ESTIMATION_PROGRESS = "meal_calendar/calorie_progress"
220	    const val MEAL_FILTER_SETTINGS = "meal_filter_settings"
221	    const val THOUGHT_TRASH = "thought_trash"
222	    const val DAILY_RECORDS = "daily_records"
223	    const val DAILY_RECORDS_TODAY = "daily_records/today"
224	    const val NAVIGATION_SETTINGS = "settings/navigation"
225	    const val MORE_PAGE_SETTINGS = "settings/more-page"
226	    const val USAGE_SETTINGS = "settings/usage-statistics"
227	    const val STEPS_SETTINGS = "settings/step-statistics"
228	    const val STATISTICS_USAGE = "statistics/screen-time"
229	    const val STATISTICS_HEALTH = "statistics/health"
230	    const val STATISTICS_STRUCTURED = "statistics/structured"
231	    const val AI_SETTINGS = "settings/ai"
232	    const val AI_REVIEW = "ai/review"
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

... (output truncated to budget; the source above is complete and verbatim — treat it as already Read. For any area not covered, run another codegraph_explore with the specific names — do NOT Read these files.)

