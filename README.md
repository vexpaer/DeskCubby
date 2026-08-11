# DeskCubby

**English** | [简体中文](README.zh-CN.md)

[Download the latest version from GitHub Releases](https://github.com/vexpaer/DeskCubby/releases)

DeskCubby is a local-first, cross-platform personal journaling and knowledge-management app. The Android client is built with Kotlin and Jetpack Compose; the Windows client uses Tauri 2, React/TypeScript, and Rust. Both clients keep Markdown journals and media in directories chosen by the user. Their application databases contain only structured records, settings, and rebuildable indexes.

Current versions: Android **0.13.0** and Windows **0.6.0**.

The repository is split by platform: the complete Android project lives in `android/` and the Windows project in `windows/`. Project-level documentation, including `README.md`, `README.zh-CN.md`, `TUTORIAL.md`, `overview.md`, and the license, remains at the repository root.

## Windows 0.6.0

The Windows client supports Windows 10/11 x64. Version 0.6.0 accepts Android data formats v1–v29 and always exports v29. On wide layouts, the sidebar scrolls independently at every window height while Settings stays pinned at the bottom; collapsing a group also collapses its page buttons, and narrow windows can still turn the sidebar into a drawer. The UI supports Chinese and English, system light/dark mode, font scaling, and the Material, Liquid Glass, and Organic Future themes. The app logo is a transparent 512×512 pixel-art image, with the full Windows icon set generated through nearest-neighbor scaling. PDFs are rendered inside the app with pdf.js because WebView2 does not include a PDF viewer.

The Android-only built-in browser and home-screen widget designer are not reproduced on Windows. All other major pages are available from the sidebar or More:

- Home, Journals, Meal Calendar, Daily Records, Quick Thoughts, Date Records, and Poetry Book preserve file-first data boundaries, categories and ordering, trash, external-edit conflict handling, daily poetry, and configurable home/game shortcuts. Journal and note files are edited in place only within roots selected by the user. Journal previews resolve relative Markdown images from the selected media root, including Chinese names and spaces; missing or invalid images show “Image unavailable” instead of remaining stuck in a loading state.
- Notes lets the user select a normal directory as an Obsidian-style vault, browse folders, create or rename Markdown files, edit source or preview rendered content, insert images, and resolve external edits. Rust rejects `..`, absolute paths, reserved device names, and links that escape the selected root.
- Reading provides a local TXT/PDF bookshelf, progress, table of contents, search, typography/background controls, and PDF zoom. pdf.js continuously renders PDFs in the app while data is fetched on demand through a restricted read-only protocol. Full-file fingerprints are identical to Android, and each cloud configuration can independently synchronize progress for the same book through `reading/v1/progress.json`. Windows paths and Android URIs are never written into one another.
- RSS manages HTTPS feeds, refreshes them, and opens articles for reading. Network access limits redirects, private-network addresses, DOCTYPE declarations, response size, concurrency, and time. Article lists are not long-term backup data.
- AI and Meal Calendar calories use the same OpenAI-compatible model configuration structure and plaintext API Key fields as Android v29. They support chat, history, journal/Quick Thoughts context, and food-photo estimation. Keys appear only in Authorization headers, never logs or errors. HTTP endpoints require explicit approval as trusted local services.
- Vault uses PBKDF2-HMAC-SHA256 with 120,000 iterations and AES-256-GCM. Passwords, plaintext, and derived keys remain behind the Rust boundary, and the unlocked key exists only in session memory. Windows and Android Vaults are separate, and the Windows Vault is excluded from automatic backups, restore points, and cloud sync.
- Games and Statistics include 4×4/5×5/6×6 2048, Snake, Tetris, Minesweeper, Spider Solitaire, and local two-player Go on 9×9, 13×13, or 19×19 boards. Go supports captures, suicide prevention, simple ko, and ending after two consecutive passes; it displays capture counts but does not adjudicate territory. Go saves, record captures, detailed statistics, and play time live in private Windows tables and are excluded from v29, restore points, automatic backups, and application-JSON cloud sync. The existing seven games/variants continue to round-trip through Android v29 fields. 2048 records total `moveAttempts`; legacy `losses` are read and preserved only for compatibility.
- Phone Usage and Health are **display-only and never collect data**. Phone Usage can import a compatible snapshot, maintain a read-only link, or download explicitly enabled per-device usage cloud objects. Health reads only a compatible file explicitly selected by the user. Windows never calls activity or health collection APIs and never writes, renames, or deletes source files; a failed refresh retains the last valid snapshot. Neither detail set enters Windows v29, automatic backups, restore points, or application-JSON uploads, and Windows never uploads usage objects.
- Settings and Backups mirror Android where practical: settings hierarchy, themes, background parameters, home modules, journal heading sizes, poetry/Meal Calendar controls, Vault row height, AI, app data, and the tutorial master switch. Desktop Navigation controls visibility, grouping, and order for all 18 main pages and can create, rename, reorder, or delete categories. Collapsed categories are removed from both layout and keyboard order. Editable subpages use local drafts, a top-right Save action, per-page defaults, and confirmation before leaving unsaved changes. Backups preview-import Android v1–v29 and always export v29.
- Meal Calendar display settings persist filter enablement, brightness, contrast, saturation, temperature, tint, images per row, captions, and one- or two-column date-card layout. Two-column mode splits the date list at its midpoint while keeping each day and all its meals together; narrow windows fall back to one column. Filters never rewrite source images.
- WebDAV/S3 can synchronize journals, media, application JSON, and reading progress in upload-only or bidirectional modes. The cloud page also provides confirmed Force Upload and Force Download actions. Force Upload may target several enabled endpoints; Force Download requires exactly one enabled source. Neither propagates deletions. Remote overwrites remain tied to scan versions, local overwrites to local snapshots, and downloaded application JSON remains pending until confirmation. Windows only downloads and merges multi-device usage objects—it never uploads them. Credentials are encrypted with DPAPI for the current Windows user and are never returned to the frontend or written to v29. HTTPS is required by default; HTTP needs explicit trusted-LAN approval. After conditional-request probing and same-byte read-back validation, the S3 path tolerates unquoted, weak, multiple, or missing ETags; WebDAV still requires exactly one strong ETag.
- About and Updates checks for updates roughly 60 seconds after startup only in production packages with a complete updater configuration, then waits at least 24 hours across restarts and only shows a notification. Downloading, validating the Tauri `.sig`, and installing all require user confirmation. Production GitHub Releases must carry a Tauri-updater signature. Authenticode is optional; without it, a valid release may still show “Unknown publisher” in Windows SmartScreen.

The Windows database is stored at `%LOCALAPPDATA%\com.deskcubby.windows\deskcubby.db` with WAL, foreign keys, transactional migrations, and a busy timeout enabled. Journal and media directories are not copied wholesale into private app storage. Before saving, DeskCubby compares a SHA-256 file version to detect external edits and offers Reload, Overwrite, or Save a Copy. If a file was deleted externally, Reload accepts the deletion and closes the editor, Overwrite safely recreates the file, and Save a Copy writes the draft under a new name.

### Windows v29 data compatibility

- Android v1–v29 JSON can be previewed and imported directly. Earlier versions are safely upgraded to v29 in memory, including defaults for the five `desktopWidgetConfigs` appearance fields and the `cloud_sync` → `cloud_sync_now` migration. Windows exports, automatic backups, and cloud application JSON always write `version: 29`. A JSON file is limited to 64 MiB and validated for array counts, string lengths, enums, duplicate IDs, relationships, and v29-specific structure.
- An import first displays statistics and warnings. Windows-managed settings and structured records are replaced only after the user confirms; failure rolls the transaction back and retains a pre-import restore point. Journal, media, note, and book contents are not replaced by this transaction.
- Android `content://` URIs remain opaque compatibility fields. Windows directories are stored in local settings and are never written back into those URI fields. Built-in-browser state and unknown future fields round-trip through a DPAPI-protected compatibility shadow bound to the current Windows user.
- Android v29 Vault `active`/`pending`/`items`, `usageDevices`, and health details are removed before data enters the Windows compatibility shadow. Windows Vault tables, usage/health caches, local source paths, and cloud credentials are likewise excluded from manual exports, automatic backups, restore points, and application-JSON cloud uploads.
- `cloudSyncConfigs` remains owned by the compatibility shadow until the user creates, edits, copies, or deletes a cloud configuration on Windows. Windows then replaces only the non-secret portion of that field while preserving unknown sibling fields with the same ID. Every shadow write and export still recursively strips WebDAV/S3 credentials, DPAPI payloads, source paths, and other local-only private values.
- v29 `CUSTOM/customTheme` is strictly validated and stored in the DPAPI compatibility shadow. Windows currently renders its `baseStyle` and, unless the user explicitly changes styles, exports `CUSTOM` without loss. Up to 500 root-level URI-free `readerProgress` entries merge into the Windows reading ledger by book fingerprint and timestamp. `moveAttempts` round-trips normally; legacy `losses` remain compatibility-only.
- By Android product design, an AI API Key is an ordinary plaintext field and is preserved through v29 import, export, and cloud application JSON. Treat every backup as sensitive and never place it in a public or untrusted location.

### Windows 0.6.0 platform boundaries

Windows does not implement Android's built-in browser or home-screen widget designer, and it does not read Android Room databases, `content://` URIs, or Android system permissions. Both platforms include Go, but games and records remain local to each device and are excluded from the v29 projection; Android's Go home shortcut is also local-only, while Windows enters Go from Games. Dedicated private Windows tables additionally exclude Go from restore points, automatic backups, and application-JSON cloud sync. Phone Usage and Health only display data brought to Windows by the user and are never collected or uploaded on Windows. Bookshelf paths, AI sessions, RSS article caches, Windows Vault, and usage/health caches are local data that do not migrate through v29. Only URI-free reading positions can merge through v29 or an optional reading-progress object.

## Android 0.13.0 features

Android includes an internal Kotlin Plugin API foundation. The independent `:plugin-api:core` module defines plugin lifecycle, a unified `PluginContext`, and APIs for journals, Markdown note libraries, media, sync, AI, Compose UI contributions, and plugin-private storage. Adapters in `:app` continue to delegate to existing repositories and services. This is a side-channel extension point for future features: there are currently no production plugins, and existing Screen, ViewModel, and Repository call paths have not been migrated. The Plugin API itself does not change existing pages, interactions, Room v12, Markdown files, or `dc-media.json` v2. See [android/plugin-api/README.md](android/plugin-api/README.md) for the architecture and extension workflow.

### Interface and navigation

- The Jetpack Compose UI offers Material, Liquid Glass, Organic Future, and a controlled Custom style. It supports light/dark mode, Chinese and English, RTL, and system safe areas. Custom styles still render through Compose theme roles and never load CSS, scripts, network resources, or arbitrary selectors.
- The three presets share one primary color and two to five accent colors. Custom separately configures light and dark page background/on-background, base/card/secondary surfaces, primary/secondary text, and borders, with Material, Glass, or Organic as its rendering base. It also controls 0–40 dp global radius, 0–4 dp panel border, 0–16 dp shadow, 65%–100% panel opacity, 75%–135% content spacing, and 0%–200% page-transition motion. Saving repairs unreadable low-contrast combinations.
- Primary/accent theme colors, Quick Thoughts category colors, and highlighted backgrounds all offer an HSV picker, compact inline slider labels, a honeycomb palette, and direct `#RRGGBB` input.
- Appearance settings include Compact Mode for tighter Home and Settings spacing. A global background image can be selected through SAF with independent 0%–100% visibility and 0–40 dp blur; the image is not copied into the app. Settings search at the top of the root Settings page jumps to pages by name or keyword.
- Home modules can be added, removed, ordered, and given individual title visibility. Available modules include Quick Thoughts, Daily Records, food photos, Notes, game shortcuts, journal/Quick Thoughts/date-record summaries, and separate Sync Now and Force Upload/Download cards. Both sync cards share queue, progress, last-completion, and pending application-JSON state; forced operations still require confirmation, and Force Download accepts only one enabled source. “Settings → Subpage settings → Home → Game shortcuts” can select any combination of three 2048 variants, Snake, Tetris, Minesweeper, Spider Solitaire, and Go. The upper-left greeting rotates deterministically by date through 24 short neutral defaults without truncation. “Settings → Subpage settings → Home → Home greetings” edits the user name, copies the `{name}` placeholder, and adds, edits, or deletes Chinese/English greetings.
- On Home, tapping the food-photo button opens the camera; long-pressing opens the image picker. The module no longer displays an instruction caption.
- Bottom navigation supports custom labels, icons, visibility, order, startup page, and label presentation; Settings cannot be hidden. The selected item no longer draws a pill background so the music visualizer remains continuous. The visualizer offers bars, waveform, or smooth curve. Spectrum styles can use adaptive frequencies or a manual 20–20,000 Hz range with logarithmic resampling, preventing all energy from clustering at the left. After Android recording permission is granted, it reads only the current system audio session's spectrum/waveform for live drawing, never stores or uploads audio, and stops in the background, without permission, or when system animations are disabled.
- The Navigation/More hub collects main pages not placed directly in the bottom bar. Bottom-bar visibility is managed under “Settings → Bottom navigation”; hub membership and descriptions live under “Settings → Subpage settings → Navigation” to avoid duplicate switches. Cards use a two-column masonry layout based on their true heights and can be reordered directly through four-dot handles.
- Page tutorials are enabled by default. The first visit to each main page, nested route, Settings subpage, reading state, or individual game shows a bilingual overlay that cannot be dismissed accidentally. Confirmations stay only on the current device; “Settings → About → Page tutorials” can disable the mode or reset all confirmations.
- First-run defaults stay compact: the bottom bar contains Home, Journals, Quick Thoughts, Navigation, and Settings. Home initially shows Today, Daily Poem, Quick Input, Food Photos, Year Progress, Notes, Games, Record Overview, Sync Now, and Force Upload/Download. A legacy single `cloud_sync` module expands into the two new entries in place; it is not re-added for users who had already removed cloud sync.
- Settings subpages save from the top-right. Reset restores every draft value on that page to its default before saving, and leaving with unsaved changes requires confirmation.
- The cold-start splash background is always black to avoid a white system-window flash.
- “Navigation → Widgets” can save multiple home-screen widget designs with custom dimensions from 1–6 cells, common presets, background/text colors, an SAF background image, replicas of all 21 Home modules, or another-app launch actions. Every widget instance independently stores its template ID and last valid snapshot. Saving a template immediately refreshes all instances still bound to it; separate templates and reconfigured instances do not leak state into one another, and deleting a template leaves placed instances on their last snapshot. App shortcuts prefer the target launcher Activity/alias icon, fall back to the application icon, and center it at the normal 48 dp launcher size. Large widgets reproduce the full calendar, up to four Date Records with add/view actions, direct links to recent journals and Quick Thoughts, actual input for up to four Daily Records plus management, direct random-journal and per-game actions, and two cloud-sync modules with running state, progress, last result, error, and pending JSON. Poetry, six meal-photo actions, and Quick Input retain direct actions. Unsuitable sizes such as 1×1 or 1×2 degrade to opening the app; real input that `RemoteViews` cannot host is handled by a private, non-exported Activity.

### Journals, media, and Daily Records

- Storage Access Framework grants persistent access to journal and media directories; `content://` URIs are never converted to file-system paths.
- When no journal directory is configured, the empty state offers “Set up default directories.” The system picker opens at local Documents and still requires the user to confirm SAF read/write access. DeskCubby then creates or reuses `Documents/deskcubby/diary` and `Documents/deskcubby/media`, verifies both children, and saves them together only after success. Manual directory selection remains available.
- Markdown journal scanning, month grouping, today's journal, templates, filename format, source editing, reading preview, and autosave are supported. The preview preserves CommonMark headings, bold, italic, lists, quotes, code, and safe links. “Settings → Subpage settings → Journals and media → Markdown reading preview” independently adjusts H1–H6 from 12–48 sp.
- Media on standalone Markdown lines can be reordered by dragging. Both source and preview provide media delete controls; after confirmation they remove every reference to that file from the current journal, remove the media file, and best-effort clean its sidecar metadata. SHA-256 external-edit checks still run first, and a failed primary journal or media action is never reported as successful.
- Journal settings can optionally save an uncompressed source photo to the system Gallery's DeskCubby album during import. API 29+ needs no permission; API 26–28 uses storage permission granted at installation. Gallery failure does not prevent the journal copy from being saved.
- Journals support soft delete, restore, and permanent delete. Soft delete first copies content into a separate trash area and verifies it by reading it back.
- Daily Records use multiline templates and multiline input. `xx` marks a region that can be selected quickly for replacement. The fully edited multiline value can be appended to the current journal or today's journal.
- Daily Records can be opened, filled, and submitted quickly from both the journal editor and Home.

### Obsidian-compatible Notes

- Notes is placed in Navigation by default. SAF can select an Obsidian vault or any regular folder; DeskCubby browses real files in place, does not copy the vault, and does not regroup notes by date or month.
- Folders appear before `.md` files, each in natural name order. Breadcrumb navigation and confirmed create, rename, and delete operations are available for folders and Markdown notes. Other file types remain untouched but are not listed.
- The note editor provides Markdown source and the shared reading preview, then autosaves roughly 900 ms after typing stops. Saving uses SHA-256 external-edit detection. If Obsidian changed the file concurrently, the user can load the disk version, explicitly overwrite it, or save a DeskCubby conflict copy.
- Standard Markdown images and Obsidian `![[Wiki embeds]]` are both previewed. Every media upload first selects an image and then asks the user to choose a destination folder inside the current note library. A relative link is inserted only after copy and read-back validation; the journal media directory is never reused.

### Reading

- Reading is available from Navigation/More and imports TXT or PDF through SAF. DeskCubby stores only persistent read permission and reading state; it never copies or rewrites the book. TXT falls back through UTF-8, UTF-16, and GB18030 decoding. The enhanced PDF view uses PDFium directly over an SAF file descriptor, continuously and lazily renders pages, and supports zoom, true page numbers, two-color mapping, text search with navigation, and text-based table-of-contents scanning. If PDFium cannot open the file, returns an invalid page count, fails to render the restored/first visible page, hits native-link or memory failure, or takes over 30 seconds to show the first page, DeskCubby switches safely to a continuous system `PdfRenderer` compatibility view. A banner can retry the enhanced view. Fallback never damages or rewrites the source file.
- Smart TXT rules scan the entire book and recognize more Chinese and English chapter/volume/section/act forms, spaced or decorated “Chapter 1” forms, Chapter/Part/Book/Section/Episode, Roman numerals, prologues/epilogues, numeric titles, Markdown headings, and bracketed titles. Consecutive table-of-contents entries and later body headings with the same name are merged while preserving the body location. Reading settings choose Smart only, Custom only, or Smart + Custom; accept a whole-line regular expression; and set the maximum heading length.
- TXT supports full-book search, previous/next result navigation, highlights, and long-press selection/copy. The enhanced PDF view supports pinch zoom and saves a 50%–300% baseline scale. PDFs with extractable text support search and automatic contents scanning; image-only scans do not fabricate text results. The system compatibility view still reads continuously by true page number/progress but lacks PDFium text search and contents.
- TXT and PDF offer five preset backgrounds or any custom background, with automatic or custom foreground/text color. PDF uses display-only two-color mapping, so a dark background with a light foreground can produce white text on black without changing the file. TXT additionally controls 12–38 sp font size, 1.0–2.4× line spacing, and 0–36 dp paragraph spacing. All pages in compatibility PDF mode share the same horizontal offset, so panning a zoomed document does not move only the current page.
- Optional Distraction-free Fullscreen expands content into safe areas and initially hides the book title, toolbar, page indicator, and system bars. Tapping the center shows or hides controls. Orientation can follow the system, lock portrait, or lock landscape.
- Bookshelf settings switch between a list and two-column covers and independently control the title below each cover and progress percentage. Hiding the title only removes the duplicated caption; a TXT without a custom image draws its title directly onto the default cover. Cover decoding, output pixels, and cache size are limited using the card's measured width. PDFs prefer a verified cache or a safe thumbnail from the document provider and otherwise show a type placeholder. Either TXT or PDF can use an SAF image as a manual cover; removing it restores the safe thumbnail/default without touching the book.
- Per-book URI, cover, page/paragraph position, and global reading preferences are stored in a private schema-v6 JSON file compatible with v1–v5. v6 adds only the title-below-cover option and defaults older state to visible. Foreground reading checkpoints every 30 seconds and saves again when leaving or backgrounding. A SHA-256 fingerprint combines all book bytes and its type, and a bounded ledger stores progress without URI, title, cover, or content. DeskCubby v29 JSON includes that ledger. On another device, whether restore happens before or after import, selecting a byte-identical TXT/PDF through SAF resumes from the newest position. Bookshelf entries, covers, display preferences, and reading time remain excluded from application JSON and Android system backup.

### Meal Calendar and AI calorie estimation

- Meal categories are fixed as Breakfast, Lunch, Afternoon Tea, Dinner, Fruit, and Late-night Snack, with default icons `🥪 🍱 🍹 🍜 🍊 🍤`.
- Meal Calendar groups food photos by date and controls maximum image height and caption visibility. Tapping opens a fullscreen viewer with pinch zoom and double-tap zoom; calories and capture location appear when available. Loading takes one SAF child-metadata snapshot each for the journal and media directories and reuses a bounded Markdown-image-reference cache. Returning from filters or estimation progress reuses loaded data. Only an app-originated journal/media change automatically invalidates it; manual refresh still forces external edits to be reread.
- A date range, inclusive at both ends, can be exported as a tall PNG. Export honors the current meal filter, image filter, images-per-row, and caption settings, checks height/pixel limits before generation, and verifies the file by reading it back after writing.
- Images can wrap as two per row, three per row, or “2+3 Auto.” Auto mixes rows of two and three without leaving a final empty slot: 4=2+2, 5=3+2, and 7=3+2+2.
- The funnel in the top-right filters meal categories for the current session—for example, selecting only Breakfast displays only breakfast photos.
- The wand button toggles photo filters on tap and opens settings on long press. Brightness, contrast, saturation, temperature, and tint affect only in-app rendering and never rewrite source images.
- After text and image-recognition models are configured, automatic calorie estimation can be enabled. Work is grouped by day: up to three photos are recognized in parallel for foods and portions, then one text-model request combines the day's note and all recognition results and returns total energy plus each food name, quantity, unit, and kJ for every photo. Confirmed duplicate angles of the same meal may be assigned 0 kJ. The built-in legacy text prompt migrates to the multi-image contract; custom prompts are not overwritten.
- Tapping Estimate All queues the current date; long-pressing opens progress directly. The progress page shows concurrent recognitions, completed images, and the Parallel image recognition / Whole-day text calculation / Save stages. Tapping the active card shows per-image and whole-day request duration, streamed reasoning, and streamed response. Work continues after leaving Meal Calendar, while dates still process sequentially. If any image request, combined calculation, or save fails, that day is not partially written and later dates continue.
- Results live in `dc-media.json` in the media directory. The legacy `deskcubby-media.json` is still read, but Markdown image titles are no longer rewritten; old titles such as `Lunch-800kJ` remain a read-only fallback. Tapping a date total/details opens the full date with entries such as Breakfast 1, Lunch 1, and Lunch 2. Photos without results consistently show “Estimation failed.” The calculator beside a photo reruns only that image while preserving a manual day total. A full-day rerun recognizes every photo in parallel, calculates once, replaces all details, and clears the manual total. Both preserve the note and send it only to the text model.
- `dc-media.json` v2 limits file size, entries, dates, food items, strings, and energy values. Updates use previous/pending copies, read-back verification, and a recovery copy. A damaged or oversized file is never treated as empty and overwritten.
- Journal settings can enable capture-location recording. Imported photos have EXIF coordinates read when available—gallery images may require media-location permission—then system geocoding writes the place beside calories in `dc-media.json`.
- Journal reading preview displays an existing capture location from `dc-media.json` below the photo, not only in the fullscreen Meal Calendar viewer.

### Quick Thoughts, browser, and other pages

- Quick Thoughts supports create, update, categorization, pinning, drag ordering, copy, share, soft delete, and trash. The first visit to All, Uncategorized, or any category automatically positions the list at the newest content at the bottom instead of the oldest entry.
- Long-pressing a thought marks or unmarks it as Important. Important entries use a customizable background. Category colors include presets and a custom picker; Organic Future presets span several hues instead of only green.
- The Change Category dialog opened from a long press can create a category and immediately apply it to the current thought. Category editing can export every thought in that category as plain text through the system share sheet.
- The keyboard opens only after tapping the input and closes only from the keyboard itself; scrolling the list no longer affects it. Quick Thoughts settings control the input's maximum height, after which the field scrolls internally.
- Thoughts can use one-line or full display and can reopen after restart at All or the last category. A top-right control beside Categories switches one-line/full display immediately.
- The multi-tab WebView browser supports horizontal address-bar tab switching, back/forward, refresh, home, find in page, bookmarks, history, uploads, downloads, and opening externally.
- Date Records, Poetry Book, and Daily Poem are included. Home stores the displayed poem's fingerprint by local date, so startup does not silently replace it on the same day. Refresh rotates among Jinrishici (`v2.jinrishici.com`), Hitokoto's poetry category (`v1.hitokoto.cn/?c=i`), and Gushi Ci (`api.gushi.ci/all.json`). If all sources fail or repeat today's content, it continues from 182 bundled poems instead of showing “No unseen poem available.” The Home detail view uses the poem's name as its title.
- Poetry Book filters All / Uncategorized / custom categories and supports category colors, create/edit/delete, per-poem reassignment, and a top-right sorting mode. Ordering correctly handles moving any item to the first position. Category deletion explicitly chooses between deleting only the category and moving poems to Uncategorized, or deleting both. Four-dot handles support drag and accessible move-up/move-down actions, and sorting cards collapse to a one-line preview beginning with the title. Automatic line breaks for seven-character verse apply only when at least two such lines are detected. Tapping the Home poem opens and bookmarks the complete work; long-pressing a card opens edit, recategorize, or delete. Settings still control a local font, font size, line spacing, alignment, source display, quotation decoration, and seven-character wrapping.
- RSS manages RSS 2.0 and Atom subscriptions with create/edit/delete, enable/disable, and refresh. Valid HTTPS articles open in the built-in multi-tab browser and can still be handed to the system browser.
- Vault is a password-protected private-text collection. A password-derived key (PBKDF2 + AES-GCM) encrypts entries stored in Room. New passwords may contain one or more Unicode code points and cannot be recovered. v29 backs up AES-GCM ciphertext, IV, salt, iteration count, and encrypted verifier exactly, but never the password, plaintext, or derived key; entering the original password after a device migration unlocks it. Cards do not show dates, show notes only when present, copy ordinary text on tap, and open safe HTTP(S) links in the system browser. Long press opens copy/edit/delete; four-dot handles reorder. “Settings → Subpage settings → Vault” controls minimum row height down to 48 dp.
- Games keeps independent saves for 4×4/5×5/6×6 2048, Snake, Tetris, custom Minesweeper, landscape single-suit Spider Solitaire, and local two-player Go. Go supports 9×9, 13×13, and 19×19 boards, captures, suicide prevention, and simple ko. Two consecutive passes end the game; capture counts are shown, but territory/area scoring is not automated. Since 0.12.0 every valid move or pass publishes a fresh game snapshot so Compose immediately redraws stones, move count, and turn. Touches snap to the nearest intersection across the full board, eliminating dead zones. Game text without a local override uses the current theme foreground and follows light/dark mode. The default 2048 palette also follows the theme unless the user chooses a day/night override. 2048 offers slow/standard/fast motion, per-animation switches, full-page four-direction swipes, and unlimited undo; values with five or more digits scale to one line. Minesweeper supports 6–30 rows, 6–30 columns, and any valid mine count. Spider supports deal, move, automatic K→A removal, undo, and landscape layout.
- Game-specific statistics include both 2048 total operations—every accepted directional input, even without board movement—and effective moves, plus merges, highest tile, and wins. Since 0.10.0, new 2048 losses/win rates are neither recorded nor shown; legacy Room/backup loss fields still round-trip. Go tracks stones placed, captures, passes, and completed games. Other games retain their own food, line, mine, move, and win/loss measures. The unified Statistics page covers journals, phone usage, health, reading, and all games, with cards leading to trend or metric charts. Go saves, record captures, detailed statistics, and Home shortcut remain local to Android and are excluded from v29; the existing seven games/variants retain their previous v29 behavior.
- Each game separately accumulates foreground play time. It checkpoints every 30 seconds and saves to private `engagement-times-v1.json` when leaving or backgrounding. Total time is excluded from Room game saves, v29 JSON, and Android system backup. Detailed statistics live in Room; the existing seven games/variants enter explicit v29 backup, while Go statistics stay only on Android.

### Phone Usage and Health

- Phone Usage reads foreground/background, screen-off, and lock events after Android Usage Access is granted, splits real intervals at local midnight, and finalizes daily totals and per-app durations. Refresh backfills still-accessible history only when event boundaries are complete. Version 0.3.7 rebuilt verifiable recent history to correct devices that repeated one daily aggregate across several days. App filtering prefers system labels and icons—for example, displaying Douyin instead of `aweme`—and orders apps by duration in the selected range.
- Health only aggregates daily steps, distance, and active calories read-only from authorized Health Connect data. Three metric buttons switch overview, chart, and details. Activity Recognition is no longer requested and `TYPE_STEP_COUNTER` is not used as a fallback. The Health Connect status/permission explanation appears below all statistics.
- Both pages show start date, days counted, total, daily average, and 7/30/90-day or All ranges. Three icon-only buttons on one row switch chart type. Tapping a bar, line point, or square floats its date and value above the point without opening another page. Bars use a height gradient, and bar/line charts overlay maximum/minimum axis labels inside the upper-left/lower-left.
- Phone Usage formats duration compactly with `H`/`M` and omits an “Overview” heading. Its cards add Highest Day and Past 7-day Average. The chart sits above range/type controls. Permission and history loading show a loading button instead of briefly flashing “Statistics disabled.” The old “Private local statistics” card has been removed.
- Every installation creates a stable random device ID unrelated to hardware identifiers and an editable device name. Phone Usage can display All Devices or one device. A device collects only its own system data; other-device history arrives through v29 import or cloud sync. All Devices sums by date and app and displays a short device ID to distinguish duplicate names. After enablement, the first app open each day also attempts collection.
- Android 0.4.0 removed the old manual “Export for Windows” canonical-v4 UI. Enabling Multi-device phone usage on a cloud configuration gives each device an independent `usage/v1/{deviceId}.json` object for bidirectional merge. FINAL days win over OPEN days; equal states choose the newer collection, preventing phones A and B from overwriting one another.
- Both features are disabled by default under “Settings → Subpage settings → Phone Usage” and “Settings → Subpage settings → Health.” Enabling still requires the corresponding system authorization. When either is enabled, WorkManager attempts backfill every six hours.
- The current day remains refreshable. A past day is finalized only after a complete successful read, then is not recomputed. Disabling collection retains local history.
- Room v12 is the sole runtime authority for phone usage, other-device usage cache, daily health statistics, and game-specific statistics. Upgrade transactionally and idempotently migrates `usage-statistics.json`, legacy device caches, and `step-statistics.json` into Room. Damaged files are preserved and do not hide other valid inputs. JSON codecs remain only for legacy migration, DeskCubby backup/import/export, and cloud sync, with external formats unchanged. To prevent Android system backup from moving health details embedded in the same database, `deskcubby.db` and its WAL/SHM/journal files are excluded from system backup. Explicit DeskCubby JSON/cloud sync remains the supported migration path.

### AI configuration and chat

- The AI configuration library stores multiple text/image models. Each configuration contains name, type, API endpoint, model name, API Key, temperature, system prompt, and HTTP permission.
- Tap a configuration for details; long-press to copy or delete. AI Chat and Meal Calendar estimation independently choose their active configurations.
- Details preview the real request-JSON structure with placeholders for text, image prompt, and image data. The API Key belongs in the Authorization header and is never part of the JSON preview.
- AI Chat calls a non-streaming OpenAI-compatible `chat/completions` endpoint. Sessions and messages live in Room with history, continuation, creation, rename, and deletion. A title is generated locally from the first message.
- The system file picker can attach one image. The provider/model must accept multimodal `image_url` data URLs.
- A single “+” menu to the left of the chat input offers image, journal context, and Quick Thoughts context; Send is inside the field. Journals are individually selectable. Quick Thoughts can be selected individually or imported/cleared by category. Legacy Date Record and Poetry context snapshots remain compatible but are no longer new-entry choices. Limits are 50 items, 64 KiB per item, and 256 KiB total; exceeding them rejects the change atomically instead of altering the current selection or silently truncating.
- Selected content is read and frozen only when Send is pressed. A snapshot contains source, title, date/attribution, and body, but no Room ID, `content://` URI, file hash, or credentials. It is stored locally with the conversation as untrusted reference data and is included in later requests for that conversation.
- While waiting, the UI shows “Thinking.” If the server explicitly returns `reasoning_content`, `reasoning`, `analysis`, or `<think>` content, it appears in a collapsible panel and is stored with the session. DeskCubby never invents model reasoning the service did not return.

> [!WARNING]
> Under the current product design, an AI API Key is stored in **plaintext** with its model configuration and is included in DeskCubby v29 JSON/automatic backups. v29 also contains custom themes, Markdown heading sizes, note-directory references, Home shortcuts/saves/detailed statistics for the existing seven games/variants, widget designs and their name/opacity/icon/alignment/text-size options, multi-device phone usage, URI-free reading progress, and Vault ciphertext/verifier metadata. Never publish or share backups, app-data directories, cloud-synchronized application JSON, or screenshots containing a Key. Go shortcuts, saves, and statistics are currently excluded from v29.

### About and updates

- “Settings → About” displays the version and GitHub link and can manually check GitHub Releases. When a newer release contains a trusted DeskCubby APK, Download and Install writes it to private cache, verifies package name, version, and signature, guides the “Install unknown apps” permission when necessary, and invokes the system installer.
- The launcher display name can switch between “Desk Cubby” and “桌洞” through launcher aliases; some launchers need a moment to refresh.
- The About page switches immediately among the classic icon, magic-book icon, and a user-provided DeskCubby icon. Either icon style can be combined with either launcher language.
- App Tutorial opens repository [TUTORIAL.md](TUTORIAL.md), which explains each page, button, and gesture. Page Tutorials can disable the default overlays or clear every confirmation on the current device to replay them.

### Application data

- The concise “Settings → Application data” page immediately calculates total app usage and private-data usage, grouped by package/code, Room database, DataStore/preferences, reading data, reading/game time, legacy statistics migration files, cache, other private files, external app directories, and selected SAF journal/media directories. Private and SAF scans have item and total-time limits; an incomplete result is explicitly labeled a lower-bound estimate. Recalculate is available.
- The same page provides local JSON backup and links to WebDAV/S3 from its Cloud Sync card.
- Cloud status persistently shows Last sync; before the first successful sync it explicitly displays Never.
- Home's separate Sync Now and Force Upload/Download modules reuse the same state and serialized WorkManager queue. They display enablement, progress, last completion, and pending application JSON. Normal sync performs safe merge; the force card separately confirms local-first upload or one-source remote-first download.
- Multiple WebDAV and S3-compatible configurations can be managed independently and can select journals, media, application JSON, multi-device phone usage, reading progress, and upload-only or bidirectional mode. New configurations enable reading progress by default. Existing configurations are not silently changed during upgrade; users must edit them to opt in.
- Manual Sync Now is followed by an equal-height split Force Upload / Force Download button. Either side first shows an impact confirmation. Force Upload makes local content authoritative for same-path conflicts but still binds remote writes to the scanned remote version and may target several enabled endpoints. Force Download accepts one enabled cloud source; multiple sources fail closed with stable code `SYNC_FORCE_DOWNLOAD_SOURCE_COUNT` before any endpoint is read. A valid single-source download makes remote content authoritative only while the local file still matches its scan snapshot; a concurrent local edit is retained as a conflict copy. Neither force mode propagates deletion; one-sided items are filled only in the chosen direction. Downloaded cloud application JSON remains pending for user confirmation. The global switch also registers a network-constrained six-hour periodic job. Journals and media continue to use only SAF-authorized directories.
- Android's system widget picker can add either a Sync Now widget or a split Force Upload / Force Download widget. Actions enter one unique network-constrained WorkManager queue to prevent duplicate concurrent runs. Widgets show only generic states such as Queued, Syncing/Uploading/Downloading, Complete, Failed, or Check sync settings—never endpoint, path, content, or credentials. Because adding the force widget is itself an explicit user action, tapping it queues directly without another in-app dialog. Force Download with multiple sources fails before reading any endpoint.
- Synchronization continues to use SHA-256, a remote manifest, and conflict copies. If ordinary WebDAV GET/PUT lacks a validator, a `PROPFIND Depth: 0` response capped at 64 KiB obtains one strong ETag before `If-Match`. Without exactly one valid strong ETag, the operation fails safely instead of using second-resolution `Last-Modified` or overwriting unconditionally. Deletions are not propagated: a file missing on one side is uploaded, downloaded, or skipped according to mode.
- An S3 endpoint may omit its scheme. SSL/TLS is enabled by default and prepends `https://`; only a trusted LAN may explicitly use HTTP. Path-style (`/Bucket/path`) is enabled by default, with bucket-subdomain addressing also available for services such as CSTCloud. Since 0.12.0, the app no longer sends a deliberately mismatched `If-Match` conditional GET as a semantic probe and no longer blocks on unquoted, weak, multiple, or missing ETags or a service ignoring conditional GET. Reads and writes still best-effort send `If-Match` / `If-None-Match` and treat 409/412 as conflicts. Manifest/payload SHA-256, content-addressed objects, and same-byte read-back when no trustworthy write ETag exists remain. These checks verify bytes but cannot supply atomic concurrent-write semantics when a compatible S3 service ignores conditions.
- Every WebDAV/S3 service accepts a custom User-Agent; an empty value restores the default.
- WebDAV passwords remain Android-Keystore encrypted. By product requirement, S3 Access Key ID, Secret Access Key, and Session Token are plaintext in private DataStore and fully visible while editing. Legacy Keystore S3 values migrate on first edit/save. S3 credentials remain excluded from logs and DeskCubby JSON backups.
- Enabling Application JSON uploads the v29 backup as `json/dc.json`, including plaintext AI API Keys, custom themes, structured records, and URI-free reading progress. Multi-device phone usage uses a separate per-device object; Reading progress uses `reading/v1/progress.json` merged by full-book SHA-256 without restoring the whole application JSON. The reading object contains no title, URI, cover, or text, although a fingerprint may still identify a known file. HTTPS protects transport only; remote objects are not end-to-end encrypted, so use only trusted services.
- Downloaded cloud application JSON is validated and staged in private storage. A background job never directly overwrites local settings or Room; the user must confirm restoration in Sync settings.

### Backups

- Select an automatic-backup directory, save immediately, or manually import/export one JSON file. Automatic backup uses `dc.json`; manual export defaults to `DC-yyyy-MM-dd.json` and still recognizes legacy `DeskCubby*.json`. “Settings → Application data → View complete JSON” displays the current full snapshot.
- The current format is v29, limited to 64 MiB, with safe import support for v1–v28. On top of v28 controlled Custom themes and up to 500 URI-free reading-progress records, v29 adds five appearance fields to each home-screen widget: name visibility, 0%–100% background opacity, icon visibility, text alignment, and 75%–150% text scale. Older imports default to visible name/icon, 100% background, no scale, and left alignment. Reading progress still contains only the full-book SHA-256 fingerprint, TXT/PDF type, position, total pages, and timestamp—never title, URI, cover, or content. Windows 0.6.0 imports v29 produced by Android 0.13.0.
- Vault passwords/plaintext/derived keys, WebDAV passwords, S3 user/keys/session token, AI chat history and frozen context, note/journal content, media files, background-image files, bookshelf/covers/preferences, reading/game time, health history, and system permissions are excluded. After a v29 import, Vault remains locked. For the existing seven games/variants, higher scores win, newer saves win, and detailed metrics merge by maximum. Go saves, record captures, detailed statistics, and Home shortcut are excluded from v29 and stay only on Android. Usage merges by device and date; an imported file never replaces the local device ID.
- v29 still contains ordinary enable fields for both statistics features, but import force-disables Phone Usage and Health collection and leaves cloud sync disabled. Music visualizer settings restore, but recording permission must be granted again locally. Missing Custom themes, reading progress, or new widget appearance fields use safe defaults while preserving local progress; all earlier per-version compatibility rules still apply.
- Importing v11 or earlier retains an existing local AI Key only when both configuration ID and API endpoint match.

### Local database

- Room is currently version 12 with every explicit migration from 1→2 through 11→12 and no destructive migration. 10→11 added phone usage, external-device cache, daily health, and legacy-file migration markers; 11→12 added game-specific statistics.
- v6 introduced AI session/message tables; deleting a session cascades to its messages. AI history is local and excluded from JSON backup.
- v7 added the Important flag to Quick Thoughts, the `vault_items` ciphertext table, and `game_states`. Explicit JSON backup began including the latter two ciphertext/save structures in v20; v21 added poetry ordering, row height, and User-Agent. The database itself added the poetry-order column only in 9→10.
- v8 added persistent ordering to Vault items while preserving their existing display order during upgrade.

## Android build environment

- Android SDK 36
- JDK 17 or newer (the JDK bundled with Android Studio is supported)

Open the repository's `android/` directory in Android Studio. Unless `GRADLE_USER_HOME` is already set, the project wrapper stores Gradle distributions and dependency caches in `android/.gradle-user-home` to avoid consuming the system drive.

Run the commands below from the repository root.

### Debug APK

```powershell
.\android\gradlew.bat --project-dir .\android :app:assembleDebug
```

Output: `android/app/build/outputs/apk/debug/DeskCubby.apk`

### Release signing

Before the first release build, run:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\android\scripts\generate-release-keystore.ps1
```

The script creates:

- `android/release/DeskCubby-release.jks` — the app's long-lived release signing key.
- `android/keystore.properties` — local signing parameters and strong random passwords.

Both files are ignored by Git. Back them up together in a reliable encrypted location. Every later Android release must use the same key; losing or replacing it prevents installed versions from upgrading normally.

For compatibility with repositories moved from older layouts, builds still accept a legacy root-level `keystore.properties` and its relative key path. New configurations belong under `android/`. If the signing script detects an existing legacy key at the root, it refuses to generate a replacement. Do not copy, replace, or regenerate a long-lived key merely to reorganize directories.

Build the signed release APK:

```powershell
.\android\gradlew.bat --project-dir .\android :app:assembleRelease
```

Output: `android/app/build/outputs/apk/release/DeskCubby.apk`

For manual setup, copy `android/keystore.properties.example` to `android/keystore.properties`. CI may instead use these environment variables so passwords are not written to disk:

- `DESKCUBBY_RELEASE_STORE_FILE`
- `DESKCUBBY_RELEASE_STORE_PASSWORD`
- `DESKCUBBY_RELEASE_KEY_ALIAS`
- `DESKCUBBY_RELEASE_KEY_PASSWORD`

Release tasks fail immediately when signing configuration is missing or incomplete; they never silently produce an unsigned APK.

### Verification

```powershell
.\android\gradlew.bat --project-dir .\android :plugin-api:core:testDebugUnitTest --offline
.\android\gradlew.bat --project-dir .\android :app:compileDebugKotlin --offline
.\android\gradlew.bat --project-dir .\android :app:testDebugUnitTest --offline
.\android\gradlew.bat --project-dir .\android :app:compileDebugAndroidTestKotlin --offline
.\android\gradlew.bat --project-dir .\android :app:assembleDebug :app:lintDebug --offline
```

0.13.0 (2026-08-10) replaced AndroidX PDF with PDFium 1.0.35 for the enhanced PDF view while retaining the system `PdfRenderer` compatibility fallback. Saving a widget template now updates every still-bound widget instance immediately while preserving per-instance independence and the final snapshot after template deletion. App shortcuts center the target launcher Activity/alias icon at 48 dp. The widget designer can choose all 21 Home modules; large widgets reproduce the full calendar, per-item record links, actual Daily Record input, random journal, game shortcuts, and complete cloud-sync state/actions, while Poetry, six meal-photo actions, and Quick Input retain direct actions. Small widgets consistently degrade to navigation. Home cloud sync is split into Sync Now and Force Upload/Download cards with legacy single-card migration. The app moved to versionCode 27, backup stayed at v29, Room stayed at v12, and Windows stayed at 0.5.0. At the user's request, this release did not start an Android emulator; release verification used compilation, automated tests, Lint, a signed APK, and static package checks.

The PDFium Android wrapper uses Apache License 2.0 and the PDFium engine uses a BSD-style license. Copyright, license, and disclaimer notices distributed in the APK are in [`android/app/src/main/assets/pdfium_NOTICES.txt`](android/app/src/main/assets/pdfium_NOTICES.txt) and are also available at “Settings → About → Third-party licenses.” DeskCubby's own code remains MIT-licensed.

0.12.0 (2026-08-10) fixed Android Go reusing one mutable state reference after a valid move, which prevented Compose from redrawing stones and turn state. Every move/pass now publishes an independent snapshot, and board input consistently snaps to the nearest intersection to eliminate dead zones. The two-column bookshelf gained a Show title below cover switch; a TXT without a custom image now draws its title on the default cover. Reader private state moved to schema v6 while retaining v1–v5 support.

The same release fixed nonfatal AndroidX `RequestFailureEvent` signals before first-page display being misclassified as enhanced-PDF failure; document-open/view-bind checks and the 30-second first-page timeout still fail safely to the compatibility view. S3 removed the conditional-GET semantic probe and its compatibility block, allowing services that ignore conditions or return nonstandard ETags. Best-effort `If-Match` / `If-None-Match`, 409/412 conflict handling, manifest/payload SHA-256, content addressing, and same-byte read-back without a trustworthy write ETag remain.

Cloud sync became real Home modules with Sync Now, confirmed Force Upload/Download, progress, last completion, and pending application JSON instead of relying only on system widgets. Home-screen widgets store complete per-App-Widget-ID snapshots so multiple instances can show separate designs and gained name/icon visibility, 0%–100% background opacity, left/center/right alignment, and 75%–150% text size. Backup moved to v29 while retaining v1–v28 import. Windows 0.5.0 could not yet import v29. Android moved to 0.12.0 (versionCode 26); Room stayed at v12 and Windows source/version at 0.5.0.

Windows 0.6.0 (2026-08-11) fixed blank desktop PDFs caused by WebView2 not including a PDF viewer. The former `<iframe>` viewer was replaced by pdf.js canvas rendering. PDF bytes still arrive on demand through restricted `http://reader.localhost/{bookId}.pdf` access, with Rust HTTP Range and CORS support; the frontend never gains file-system access. Rendering fits container width, honors the saved 50%–300% baseline zoom, supports page navigation, clamps restored bookmarks beyond the true page count, and provides retry after load/render failure. CSP now allows `connect-src http://reader.localhost` and `worker-src 'self' blob:`.

The same release caught Windows data compatibility up to Android 0.13.0: import moved from Android v1–v28 to v1–v29 and export always writes v29. Older input is safely upgraded in memory by filling `showName`, `backgroundOpacityPercent`, `showIcon`, `textAlignment`, and `textScalePercent` defaults on every `desktopWidgetConfigs` item and rewriting legacy `cloud_sync` Home modules to `cloud_sync_now`. Valid `homeModuleId` values now match Android's 21 entries, adding `notes`, `game_shortcuts`, `record_overview`, `cloud_sync_now`, and `cloud_sync_force`, with ranges/enums validated for the five v29 appearance fields. Import/export, restore points, the DPAPI compatibility shadow, private-field cleaning, and the `configs_managed` ownership gate remain unchanged. Windows became 0.6.0, Android stayed 0.13.0, and the exchange format stayed v29.

Windows 0.5.0 (2026-08-10) brought across capabilities shared with Android 0.11.0. Windows cloud sync gained explicitly confirmed Force Upload / Force Download. Upload can process several enabled endpoints; download accepts exactly one source. Neither propagates deletion. Remote writes remain tied to scanned versions, local writes to scan snapshots, concurrent changes are never silently overwritten, and remote application JSON remains staged for preview/confirmation.

The release also added local two-player Go on Windows with 9×9/13×13/19×19 boards, capture, suicide prevention, simple ko, two-pass ending, record captures, and move/capture/pass/completed-game statistics, without territory adjudication. SQLite v7 private game tables keep Go saves, statistics, and play time on that computer and structurally exclude them from v28, pre-import restore points, automatic backups, and application-JSON cloud sync. Existing v28 behavior for the other seven games/variants stayed unchanged.

It fixed collapsed sidebar groups leaving page buttons in the layout; collapsed content is now absent from both visual layout and keyboard order. It also fixed journal Markdown previews that remained on “Reading image” for Chinese/space-containing filenames or empty Rust resolution results, while the restricted media protocol continues to accept only safe files under the media root. Windows became 0.5.0; Android version, v28, Reader private schema, and Room v12 were unchanged.

0.11.0 (2026-08-10) improved Android first-use and high-risk entry points. When journals are unconfigured, one SAF picker confirmation at Documents creates and binds `Documents/deskcubby/diary` and `Documents/deskcubby/media`. Settings are written only after system confirmation, directory creation, and read/write validation; manual selection remains.

Two-column bookshelf covers now limit decoding, pixels, and cache using measured card width and no longer open every arbitrary PDF for in-process rendering when entering the shelf. Verified cache/provider thumbnails are preferred, a placeholder appears on failure, and manual covers remain. The enhanced PDF view binds only after `PdfView` attaches, delays the hardware color layer before first paint, and listens for request failure. Document/first-page timeouts are 30 seconds, safely switch to a continuous compatibility view, and allow retry. Availability still depends on OS version, installed package service, system extensions, and the document.

The release added local Android two-player Go on 9×9/13×13/19×19 boards with captures, suicide prevention, simple ko, and a two-pass ending but no territory adjudication. Go saves, record captures, detailed statistics, and Home shortcut are Android-local and excluded from v28. Cloud sync added an equal-height split Force Upload / Force Download control without relaxing deletion, conditional remote write, local snapshot, or pending-JSON boundaries. Sync Now and force sync can both be added through the system widget picker. All three providers gained generic Android discovery metadata, and the S3 conditional-semantics probe used the same bounded conditional GET as real reads. Android moved to 0.11.0 (versionCode 25); backup stayed v28, Reader private schema v5, Room v12, and Windows source/version 0.4.0.

0.10.0 (2026-08-08) overhauled Android reading. Enhanced PDF prewarms in an isolated service and verifies the first page through content/bitmap channels, then falls back after 15 seconds. It added distraction-free fullscreen, custom TXT/PDF foreground/background, PDF white-on-black mapping, list/two-column covers, PDF first-page covers, manual covers, progress percentages, and shared horizontal offset in compatibility mode. Reader private state moved to schema v5. v28 backup and optional `reading/v1/progress.json` merge progress across devices by full-file SHA-256 without title, URI, cover, or content.

The same release added controlled Custom Compose themes with light/dark palettes, base rendering, radius, border, shadow, opacity, spacing, and motion but no CSS/scripts. Five-digit 2048 tiles remain on one line, total operations include ineffective directional input, and loss counts are no longer shown. S3 tolerates nonstandard ETags after conditional/content validation while retaining conflict protection. Home-screen widgets gained first-render fixes, WorkManager fallback updates, and generic widget-picker guidance. Android became 0.10.0 (versionCode 24), backup v28, Reader schema v5, Room v12, with Markdown and `dc-media.json` v2 unchanged; Windows source/version did not change.

This release also introduced the side-channel Android Kotlin Plugin API architecture. `TestPlugin` exists only in the core test source set; the production Hilt plugin set is empty, the UI-contribution registry is not connected to current navigation, and existing business call paths were not migrated. The Plugin API is a future Diary/Vault/Media/Sync/AI/UI/Storage extension point and itself changes no UI, interaction, data logic, Room, or Markdown.

0.9.3 (2026-08-06) fixed some Android devices remaining forever on PDF loading. AndroidX PDF document-open and first-page-content phases each gained an eight-second limit, then automatically switch to the continuous `PdfRenderer` view with a bilingual notice. Coroutine cancellation still propagates. Text search, selection, and automatic contents are enabled only by real Android 11+ / S SDK Extension 13 capability and begin after first-page success. versionCode became 23; backup v27 and Room v12 stayed, with no Windows source/version change.

0.9.2 (2026-08-05) optimized first and repeat Meal Calendar entry. Journal and media directories use one SAF metadata snapshot; media index and four sidecar candidates share a single enumeration. Returning from filter/estimation progress reuses data, while in-app journal revisions invalidate precisely. Dates still queue sequentially, but up to three photos per day recognize in parallel before one combined text-model calculation and one atomic save. Failure produces no partial commit. The built-in prompt migrated to the multi-image `photoIndex` contract. versionCode became 22; backup v27 and Room v12 stayed, with no Windows source/version change.

0.9.1 (2026-08-05) made PDF reading continuously vertical. Android 9+ enhanced mode supports pinch and 50%–300% baseline zoom. Devices with the required system PDF extension also support selection/copy, full-text highlighted search, and text-layer contents; Android 8 retains continuous compatibility rendering. TXT search covers the whole book and body text can be selected/copied. Chapter recognition handles spaces and full-width/zero-width characters and replaces front-matter contents entries with later body locations. Snake, Tetris, Minesweeper, and Spider inherit theme foreground colors; Home game shortcuts select among seven entries. Backup became v27 with v1–v26 imports; Reader state schema v4, versionCode 21, and Room v12, with no Windows source/version change.

0.9.0 (2026-08-05) added Obsidian-compatible Notes with in-place SAF browsing in natural folder order, folder/Markdown create/rename/delete, autosave, and external-conflict handling. Every media upload selects a destination inside the note library and supports both standard images and `![[Wiki embeds]]`. Journals and Notes share a fuller CommonMark preview with independent H1–H6 sizes.

TXT gained broader Chinese/English chapter rules, custom whole-line regex, combined smart/custom mode, and a title-length limit. Calorie progress became inspectable with live duration, collapsible reasoning, and streamed response. Daily details can rerun one image and show “Estimation failed” when empty. Home added Notes, game shortcuts, and Record Overview; Quick Thoughts initially shows the newest content; game typography and default 2048 colors follow theme mode. Backup became v26 with v1–v25 imports, versionCode 20, and Room v12, with no Windows source/version change.

0.8.0 (2026-08-04) added default-on per-page Android tutorial overlays for main pages, nested routes, Settings subpages, individual games, and reading states. Each is confirmed once and can be disabled/reset under “Settings → About → Page tutorials.” Appearance and Language gained an SAF global background with 0%–100% visibility and 0–40 dp blur. Confirmations remain device-local; backup v25 includes background parameters and the tutorial master switch.

TXT gained automatic chapter recognition and a contents drawer, stable logical pages around 1,800 characters, page/progress navigation, and migration from old paragraph positions. Reading backgrounds accept any color, and PDF can jump by page or progress. Daily-event templates and input became multiline; restarting Spider now warns that it cannot be undone. versionCode became 19 and Room stayed v12, with no Windows source/version change.

0.7.0 (2026-08-04) turned bulk Meal Calendar estimation into an observable per-day queue. Long-press Estimate All opens progress with image recognition, text calculation, and save stages. Work continues after leaving; other dates queue; each date saves once only after every image finishes. Bottom navigation removed its selected pill, and spectrum visualization gained adaptive and manual 20–20,000 Hz ranges.

Games gained Room-persisted detailed statistics and a unified Statistics center. Minesweeper can double-tap a number to open surrounding unflagged cells; Spider and other games track appropriate moves, results, clears, and other metrics. Room moved to 12, backup v24 safely merged statistics and music frequency settings, and versionCode became 18. Windows source/version did not change.

0.6.6 (2026-08-04) fixed the 0.6.0 visualizer participating in `Scaffold` measurement and making bottom navigation fill the page, with a fixed-container regression test. Reading-state writes and orientation/exit recovery, foreground game timing and rotation recovery, and Spider undo saves gained stronger crash/race handling.

Meal Calendar added tappable daily calorie details, editable totals, per-food portions/kJ, and notes visible only in details. Recalculation sends notes to the text model, the default prompt was upgraded, and `dc-media.json` v2 retained old data while adding bounded, read-back-verified updates. Phone usage, other-device caches, and daily health statistics safely migrated to Room v11 as the sole runtime authority. Manual JSON default names became `DC-yyyy-MM-dd.json`. WebDAV can use bounded `PROPFIND Depth: 0` to obtain a strong ETag when ordinary responses omit validators and still fails closed without one. versionCode became 17, backup stayed v23, with no Windows source/version change.

0.6.0 (2026-08-03) added TXT/PDF Reading with SAF import, portrait/landscape lock, TXT background/font/line/paragraph controls, default scrolling, and private per-book JSON reading time. Bottom navigation gained bar, waveform, and smooth-curve music visualization after local recording permission. Games added configurable Minesweeper and landscape single-suit Spider; 2048 gained three speeds; every game separately accumulates foreground time in private JSON.

Application Data added grouped package, database, preferences, reading, engagement, statistics, cache, external-directory, and SAF journal/media size. Vault minimum rows shrank to 48 dp. This version briefly added a WebDAV `Last-Modified` fallback; 0.6.6 tightened it to strong ETags through `PROPFIND` to avoid second-resolution overwrite races. versionCode became 16, backup v23, and Room stayed v10; Windows source/version did not change.

0.5.0 (2026-08-03) added a scalable Android home-screen widget designer with custom 1–6 cell sizes, presets, background/text colors, SAF backgrounds, 16 Home modules, other-app buttons, and in-app widget placement. Widget designs entered v22 backup; actual instance bindings remained device-local.

Meal Calendar added a persistent parse cache keyed by SAF document version. Manual refresh can rebuild it, and bulk estimation/Home uploads no longer repeatedly rescan to locate the same photo. Daily Poem expanded from one API to Jinrishici, Hitokoto Poetry, and Gushi Ci rotation with 182 bundled fallbacks. Health moved to Health Connect only and placed status at the bottom. Sync status persistently shows last sync, and the system splash background became black. versionCode became 15, backup v22, Room v10.

0.4.2 (2026-08-01) restored four-direction 2048 swipes inside scrolling pages and replaced the bottom-right undo icon. It fixed sorting the first Poetry Book item or moving an item first. Category deletion can now retain poems under Uncategorized or delete both category and poems.

Step Records became Health, with Health Connect steps, distance, and active calories while the system step sensor remained a steps-only fallback at that time. Home Daily Poem deduplicates by local date across startup and manual refresh. Journal source/preview added confirmed media deletion that removes every current-journal reference and the media file. versionCode became 14; backup v21 and Room v10 stayed.

0.4.1 (2026-07-31) fixed Home Daily Poem cycling after several refreshes through bounded refresh and recent fingerprints. Poetry Book gained top-right sorting, draggable/accessible four-dot handles, title-first one-line previews, and seven-character wrapping only for detected verse. WebDAV/S3 configurations gained User-Agent. Home food-photo changed to tap-camera/long-press-picker without instructions. Enabled Phone Usage attempts collection on the first open each day.

2048 now displays only current score, removes 2048.org copy, puts unlimited undo in a bottom-right chart button, accepts swipes anywhere, enlarges/centers the board, offers day/night and spawn/move/merge switches, and adapts number size to cells. Vault gained configurable minimum row height. Android backup became v21 with v1–v20 imports; Room v10 explicitly migrated poetry ordering; versionCode became 13.

0.4.0 (2026-07-30) moved Android backup to v20, preserving Vault ciphertext/salt/verifier metadata, five game saves and high scores, and phone usage grouped by a stable random device ID. Phone Usage gained device switching, name editing, and All Devices. WebDAV/S3 gained per-device usage objects with safe date merge. The legacy manual canonical-v4 Export for Windows UI was removed. Android retained v1–v19 import, while Windows 0.2.0 did not yet support v20. versionCode became 12 and Room stayed v9.

0.3.8 (2026-07-30) rebuilt all three 2048 variants after 2048.org with new UI/motion and unlimited undo; fixed Android S3 scheme, SSL/TLS, path-style/virtual-host addressing, CSTCloud compatibility, credential display, and error codes; added Poetry categories and 182 bundled school-level works in 11 groups; and reordered JSON/backup explanations on Application Data. versionCode became 11, backup v19, Room v9.

0.3.7 (2026-07-28) rebuilt Phone Usage from foreground/background events split at local midnight and corrected repeated daily highs within system-retained event history after upgrade. All three 2048 variants gained a persisted one-step undo, including after game over. versionCode became 10; backup v18 and Room v8 stayed.

0.3.6 (2026-07-28) added seven-character poetry wrapping, step-sensor fallback, one-tap Settings reset, and a honeycomb palette; rebuilt Phone Usage overview/loading/chart hints; split 2048 into 4×4/5×5/6×6 with stronger colors/motion; shortened Settings entry to Application Data; and changed the AI Chat icon. Backup became v18 and versionCode 9.

0.3.5 (2026-07-28) added the DeskCubby launcher icon, Poetry typography and long-press actions; fixed usage app label/icon resolution and made all three charts selectable by date; made Vault notes compact with long-press copy/edit/delete and drag ordering; and standardized future JSON names on the short `dc` prefix while retaining legacy names. Backup became v17 and Room v8.

Current release artifact:

- Release APK: `android/app/build/outputs/apk/release/DeskCubby.apk`

To verify a signed release APK:

```powershell
apksigner verify --verbose android\app\build\outputs\apk\release\DeskCubby.apk
```

## Windows build environment

The Windows client requires Windows 10/11 x64, Node.js 20+, pnpm, Rust, Visual Studio 2022 Build Tools with the “Desktop development with C++” workload, and WebView2 Runtime. `windows/rust-toolchain.toml` pins project-local `stable-x86_64-pc-windows-msvc` and does not rely on the machine's default GNU target.

If Visual Studio has cached Windows SDK 10.0.26100, run the following from **Administrator PowerShell** to install desktop C++/signing components at the fixed `E:\Windows Kits\10` location and then perform a read-only verification:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\windows\scripts\install-windows-sdk-26100.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\windows\scripts\install-windows-sdk-26100.ps1 -VerifyOnly
```

The script verifies Microsoft installer SHA-256/Authenticode signatures in the Visual Studio cache and checks `rc.exe`, `signtool.exe`, `kernel32.lib`, and the `KitsRoot10` registry value. A cache containing only a bootstrapper may download Microsoft SDK payloads. Treat the SDK as usable for this project's MSVC build only after `-VerifyOnly` succeeds.

Run the following from the repository root:

```powershell
cd .\windows
pnpm install --frozen-lockfile

# Vite interface in a browser
pnpm dev

# Desktop development with the Rust backend
pnpm tauri dev
```

Before committing Windows changes:

```powershell
cd .\windows
pnpm lint
pnpm typecheck
pnpm test
cargo fmt --manifest-path .\src-tauri\Cargo.toml --check
cargo clippy --manifest-path .\src-tauri\Cargo.toml --all-targets -- -D warnings
cargo test --manifest-path .\src-tauri\Cargo.toml
```

A normal local package build produces NSIS and copies a portable executable from the same Release build with a SHA-256 file:

```powershell
cd .\windows
pnpm package:windows
pnpm package:portable
```

Typical outputs:

- Raw Release executable: `windows/src-tauri/target/release/deskcubby-windows.exe` (or `target/x86_64-pc-windows-msvc/release/` with an explicit target)
- NSIS installer: `windows/src-tauri/target/release/bundle/nsis/`
- Portable test executable and checksum: `windows/artifacts/DeskCubby-0.6.0-windows-x64-portable.exe` and its `.sha256`

To create an explicitly unsigned build for local testing only—never for release:

```powershell
cd .\windows
.\scripts\build-release.ps1 -Mode AllowUnsignedTestBuild
```

A production release requires the long-lived Tauri-updater private key from a protected environment. Windows Authenticode identity through a PFX or hardware/cloud signing command is optional:

```powershell
cd .\windows
.\scripts\build-release.ps1 -Mode SignedRelease -ReleaseTag windows-v0.6.0
```

A successful `SignedRelease` produces:

- `windows/artifacts/DeskCubby-0.6.0-windows-x64-setup.exe`
- `windows/artifacts/DeskCubby-0.6.0-windows-x64-portable.exe`
- `windows/artifacts/DeskCubby-0.6.0-windows-x64-setup.exe.sig`
- `windows/artifacts/SHA256SUMS.txt`
- `windows/artifacts/latest.json`

The Tauri updater's minisign private-key signature is a mandatory production boundary for validating in-app downloads; a missing or invalid installer `.sig`, or one that does not match the embedded public key, fails the build. Authenticode signing and a trusted timestamp establish Windows publisher identity/SmartScreen only. Without Authenticode configuration, the script explicitly verifies that the executable is `NotSigned` before continuing. When a PFX or custom signing command is configured, it signs and verifies the certificate chain, trusted timestamp, and optional signer subject. The two Authenticode mechanisms cannot be enabled simultaneously.

`.github/workflows/windows-release.yml` runs `SignedRelease` only on an exact `windows-vX.Y.Z` tag, validates five assets, and creates a **draft** GitHub Release containing the portable EXE, setup EXE, setup `.sig`, `SHA256SUMS.txt`, and `latest.json`. Before and after upload, it verifies the exact asset set, sizes, and GitHub SHA-256 digests. It never overwrites a published, incomplete, or unknown-asset Release. After human review and publication, the verified `latest.json` can be promoted to the separate `windows-stable` channel, avoiding competition with Android for the repository-wide `latest` release.

The checked-in base configuration intentionally contains no updater public key or endpoint, so normal local 0.6.0 builds are updater-unconfigured test artifacts and may display “Unknown publisher.” `SignedRelease` and CI can produce a formal release without Windows code-signing certificate secrets, but they still require the updater public key, HTTPS endpoint, Tauri updater private key, and nonempty private-key password. A missing item or failed `.sig` validation fails closed. Windows 0.1.0 did not include the updater plugin; existing users must manually install one updater-enabled formal release before in-app updates can work.

## Usage boundaries

- Windows 0.6.0 imports Android v1–v29 and always exports v29, but it never opens or shares the Android Room database directly. Real journal, media, and note files interoperate only through ordinary directories selected by the user.
- A Windows re-export merges Android-only modules and unknown fields from the DPAPI compatibility shadow. That does not mean Windows displays or executes the built-in browser, home-screen widgets, or unknown future features.
- Windows Phone Usage and Health display only phone data explicitly imported, linked, or downloaded from an enabled dedicated usage cloud object. Windows never calls activity/health collection APIs or uploads usage. Details, source paths, and read-only caches are excluded from v29, restore points, automatic backups, and application-JSON cloud sync.
- Editors use source editing plus reading preview and do not reserialize the CommonMark AST, preserving unknown Obsidian syntax. Preview renders only basic CommonMark.
- Media drag ordering recognizes only Markdown images on standalone lines.
- Markdown image links contain only the media filename. When using Obsidian, configure DeskCubby's media directory as the attachment folder.
- The Weather widget currently displays an offline cached placeholder. Daily Poem rotates among three restricted HTTPS sources and falls back to bundled poetry. Third-party availability remains under those providers' control.
- RSS targets public HTTPS feeds. Article lists currently do not persist across app restarts.
- AI providers must support non-streaming OpenAI `chat/completions` messages. Chat images and image recognition use `image_url` data URLs with an 8 MiB limit per image. The reasoning panel displays only reasoning actually returned by the service.
- After AI context is selected, frozen content leaves the device only when the user sends a message. That snapshot remains in the local conversation and is included in later requests for the same conversation. Start a new conversation to stop reusing it.
- AI calorie output is an estimate, not a medical or nutritional measurement.
- Phone Usage reconstructs foreground time from Android UsageEvents including screen-off/lock and splits at local midnight in the current time zone. Results may differ from a manufacturer's “screen time.” The app backfills natural days only while the system still retains a complete event stream. Android/manufacturer-deleted data and the oldest truncated day cannot be recovered. If event data is unavailable for the current day, only that day may use a one-day aggregate fallback; one manufacturer total is never copied across historical days.
- Health reads existing Health Connect data only and no longer uses local `TYPE_STEP_COUNTER`. On Android 13 or earlier, installing/updating Health Connect opens Google Play first and falls back to an HTTPS page if the store is unavailable. Without Health Connect or authorization, DeskCubby does not switch to another source or fabricate zeroes.
- A Meal Calendar tall PNG is constrained by Android bitmap memory, image-height, and total-pixel safety limits. Shorten the date range and export in batches when necessary.
- A WebDAV remote directory must already exist and provide exactly one valid strong ETag in an ordinary response or `PROPFIND Depth: 0` property. Android 0.12.0's S3 path no longer runs a conditional-GET probe or requires the service to enforce `If-Match` as an enablement gate. Conditional headers are best effort; SHA-256, content addressing, and necessary post-write same-byte read-back still validate content but cannot replace missing atomic concurrency semantics on the server. Windows 0.6.0 uses its own restricted S3 implementation and is unaffected by that Android change.
- Cloud sync defaults to limits of 64 MiB per object, 512 MiB per run, 10,000 objects, and ten minutes total. It is not an unrestricted whole-drive mirror.
- Downloading cloud application JSON creates only a pending staged copy. Restoring JSON does not replace journal content, media files, or AI chat history.
- Windows Vault belongs only to the current Windows database and is excluded from v29, cloud sync, and restore points; its password cannot be recovered. Importing Android v29 also removes Android Vault `active`/`pending`/`items` and never replaces Windows Vault. Phone Usage can only download from a user-selected source or dedicated usage cloud object into a private DPAPI cache. Windows never uploads usage, and local source paths and usage/health details/caches are not exported.
- Automatic updates work only in production packages containing a trusted HTTPS update source and updater public key. Automatic checks only notify; download and installation always require confirmation. A local unconfigured build must show “Updater not configured.”
- File version history and a more complete Save a Copy conflict workflow are not yet implemented.
- Some cloud document providers reject renaming. In that case, soft delete fails and retains the original file instead of permanently deleting it.

## License

DeskCubby source code is available under the [MIT License](LICENSE). Sources and separate licenses for bundled Android poetry presets are listed in [`poetry_presets_NOTICES.txt`](android/app/src/main/assets/poetry_presets_NOTICES.txt). Preset compilation derived from middle-school data is licensed under CC BY-SA 4.0 and is not covered by the repository code's MIT License. Copyright, license, and disclaimer notices for the PdfiumAndroid wrapper (Apache-2.0) and the PDFium engine (BSD-style) used by the enhanced Android PDF view are in [`pdfium_NOTICES.txt`](android/app/src/main/assets/pdfium_NOTICES.txt).
