//! Mini-game persistence and statistics for the Windows client.
//!
//! Game rules live in the React/TypeScript layer so keyboard input and animation can stay
//! responsive.  This module is the authority for durable state: a save snapshot, high score and
//! all metric deltas from one accepted action are committed in one SQLite transaction.  A
//! process-wide mutex preserves the Android coordinator's ordered-write semantics even when UI
//! timers and keyboard actions reach Tauri concurrently.

use std::{
    collections::BTreeMap,
    sync::Mutex,
    time::{SystemTime, UNIX_EPOCH},
};

use rusqlite::{Connection, OptionalExtension, Transaction, params};
use serde::{Deserialize, Serialize};
use tauri::State;

use crate::{
    AppState,
    db::{DataError, Database},
    security::{CommandResult, SecurityErrorDto},
};

pub(crate) const GAME_DTO_VERSION: u32 = 1;
pub(crate) const MAX_GAME_SAVE_BYTES: usize = 16 * 1024 * 1024;
const MAX_SCORE: i64 = i32::MAX as i64;
const MAX_PLAY_TIME_DELTA_MILLIS: i64 = 24 * 60 * 60 * 1_000;
/// The Android v28-compatible game whitelist. Never add Windows-private games here without a
/// coordinated backup format change on both platforms.
const BACKUP_GAME_IDS: [&str; 7] = [
    "2048",
    "2048_5",
    "2048_6",
    "snake",
    "tetris",
    "minesweeper",
    "spider",
];
const PRIVATE_GAME_IDS: [&str; 1] = ["go"];
const RUNTIME_GAME_IDS: [&str; 8] = [
    "2048",
    "2048_5",
    "2048_6",
    "snake",
    "tetris",
    "minesweeper",
    "spider",
    "go",
];

static GAME_WRITE_MUTEX: Mutex<()> = Mutex::new(());

/// Android v28-compatible game-state row used only at the backup boundary.
/// Windows-private engagement time is intentionally absent.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct GameBackupState {
    pub game_id: String,
    pub high_score: i64,
    pub save_json: Option<String>,
    pub updated_at: i64,
}

/// Android v28-compatible lifetime game statistic used only at the backup boundary.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct GameBackupStatistic {
    pub game_id: String,
    pub metric_key: String,
    pub value: i64,
    pub updated_at: i64,
}

/// Called by the repository-wide SQLite version migration.  Keeping the SQL here makes the
/// feature migration reviewable while allowing `db.rs` to combine all v0.9.3 catch-up tables in a
/// single outer transaction.
pub(crate) fn migrate(transaction: &Transaction<'_>) -> rusqlite::Result<()> {
    transaction.execute_batch(
        r#"
        CREATE TABLE game_states (
            game_id TEXT PRIMARY KEY CHECK (
                game_id IN ('2048', '2048_5', '2048_6', 'snake', 'tetris', 'minesweeper', 'spider')
            ),
            high_score INTEGER NOT NULL DEFAULT 0
                CHECK (high_score BETWEEN 0 AND 2147483647),
            save_json TEXT CHECK (
                save_json IS NULL OR (
                    length(save_json) BETWEEN 2 AND 16777216
                    AND json_valid(save_json)
                    AND json_type(save_json) = 'object'
                )
            ),
            updated_at INTEGER NOT NULL CHECK (updated_at >= 0)
        );

        CREATE TABLE game_statistics (
            game_id TEXT NOT NULL CHECK (
                game_id IN ('2048', '2048_5', '2048_6', 'snake', 'tetris', 'minesweeper', 'spider')
            ),
            metric_key TEXT NOT NULL CHECK (length(metric_key) BETWEEN 1 AND 64),
            value INTEGER NOT NULL DEFAULT 0 CHECK (value >= 0),
            updated_at INTEGER NOT NULL CHECK (updated_at >= 0),
            PRIMARY KEY (game_id, metric_key)
        ) WITHOUT ROWID;

        -- Deliberately Windows-private. It must never be copied into v28, recovery points,
        -- automatic application JSON or cloud application JSON.
        CREATE TABLE game_engagement_times (
            game_id TEXT PRIMARY KEY CHECK (
                game_id IN ('2048', '2048_5', '2048_6', 'snake', 'tetris', 'minesweeper', 'spider')
            ),
            total_millis INTEGER NOT NULL DEFAULT 0 CHECK (total_millis >= 0),
            updated_at INTEGER NOT NULL CHECK (updated_at >= 0)
        );
        "#,
    )
}

/// Adds storage for games that are deliberately private to this Windows installation. Keeping
/// these rows in separate tables makes exclusion from v28 exports, recovery points, automatic
/// backups and application-JSON cloud sync structural rather than dependent on call-site filters.
pub(crate) fn migrate_private(transaction: &Transaction<'_>) -> rusqlite::Result<()> {
    transaction.execute_batch(
        r#"
        CREATE TABLE private_game_states (
            game_id TEXT PRIMARY KEY CHECK (game_id IN ('go')),
            high_score INTEGER NOT NULL DEFAULT 0
                CHECK (high_score BETWEEN 0 AND 2147483647),
            save_json TEXT CHECK (
                save_json IS NULL OR (
                    length(save_json) BETWEEN 2 AND 16777216
                    AND json_valid(save_json)
                    AND json_type(save_json) = 'object'
                )
            ),
            updated_at INTEGER NOT NULL CHECK (updated_at >= 0)
        );

        CREATE TABLE private_game_statistics (
            game_id TEXT NOT NULL CHECK (game_id IN ('go')),
            metric_key TEXT NOT NULL CHECK (length(metric_key) BETWEEN 1 AND 64),
            value INTEGER NOT NULL DEFAULT 0 CHECK (value >= 0),
            updated_at INTEGER NOT NULL CHECK (updated_at >= 0),
            PRIMARY KEY (game_id, metric_key)
        ) WITHOUT ROWID;

        CREATE TABLE private_game_engagement_times (
            game_id TEXT PRIMARY KEY CHECK (game_id IN ('go')),
            total_millis INTEGER NOT NULL DEFAULT 0 CHECK (total_millis >= 0),
            updated_at INTEGER NOT NULL CHECK (updated_at >= 0)
        );
        "#,
    )
}

/// Reads the two Android-compatible collections for v28 export or a recovery point.
/// This query never reads `game_engagement_times`.
pub(crate) fn list_backup_rows(
    connection: &Connection,
) -> Result<(Vec<GameBackupState>, Vec<GameBackupStatistic>), DataError> {
    let mut state_statement = connection.prepare(
        "SELECT game_id, high_score, save_json, updated_at
         FROM game_states ORDER BY game_id",
    )?;
    let states = state_statement
        .query_map([], |row| {
            Ok(GameBackupState {
                game_id: row.get(0)?,
                high_score: row.get(1)?,
                save_json: row.get(2)?,
                updated_at: row.get(3)?,
            })
        })?
        .collect::<rusqlite::Result<Vec<_>>>()?;

    let mut statistic_statement = connection.prepare(
        "SELECT game_id, metric_key, value, updated_at
         FROM game_statistics ORDER BY game_id, metric_key",
    )?;
    let statistics = statistic_statement
        .query_map([], |row| {
            Ok(GameBackupStatistic {
                game_id: row.get(0)?,
                metric_key: row.get(1)?,
                value: row.get(2)?,
                updated_at: row.get(3)?,
            })
        })?
        .collect::<rusqlite::Result<Vec<_>>>()?;
    Ok((states, statistics))
}

/// Merges a validated Android backup into the live rows using Android's conflict semantics.
/// A disabled collection is preserved verbatim for pre-field backup compatibility.
pub(crate) fn merge_backup_rows(
    transaction: &Transaction<'_>,
    imported_states: &[GameBackupState],
    imported_statistics: &[GameBackupStatistic],
    merge_states: bool,
    merge_statistics: bool,
) -> Result<(), DataError> {
    let (local_states, local_statistics) = list_backup_rows(transaction)?;
    if merge_states {
        validate_backup_states(imported_states)?;
        let mut merged = local_states
            .into_iter()
            .map(|state| (state.game_id.clone(), state))
            .collect::<BTreeMap<_, _>>();
        for remote in imported_states {
            let next = if let Some(local) = merged.get(&remote.game_id) {
                let mut newest = if remote.updated_at >= local.updated_at {
                    remote.clone()
                } else {
                    local.clone()
                };
                newest.high_score = local.high_score.max(remote.high_score);
                newest.updated_at = local.updated_at.max(remote.updated_at);
                newest
            } else {
                remote.clone()
            };
            merged.insert(remote.game_id.clone(), next);
        }
        replace_state_rows(transaction, merged.values())?;
    }
    if merge_statistics {
        validate_backup_statistics(imported_statistics)?;
        let mut merged = local_statistics
            .into_iter()
            .map(|statistic| {
                (
                    (statistic.game_id.clone(), statistic.metric_key.clone()),
                    statistic,
                )
            })
            .collect::<BTreeMap<_, _>>();
        for remote in imported_statistics {
            let key = (remote.game_id.clone(), remote.metric_key.clone());
            let next = if let Some(local) = merged.get(&key) {
                GameBackupStatistic {
                    game_id: remote.game_id.clone(),
                    metric_key: remote.metric_key.clone(),
                    value: local.value.max(remote.value),
                    updated_at: local.updated_at.max(remote.updated_at),
                }
            } else {
                remote.clone()
            };
            merged.insert(key, next);
        }
        replace_statistic_rows(transaction, merged.values())?;
    }
    Ok(())
}

/// Replaces only the two Android-compatible collections, for exact recovery rollback.
/// The Windows-private engagement table is deliberately left untouched.
pub(crate) fn replace_backup_rows(
    transaction: &Transaction<'_>,
    states: &[GameBackupState],
    statistics: &[GameBackupStatistic],
) -> Result<(), DataError> {
    validate_backup_states(states)?;
    validate_backup_statistics(statistics)?;
    replace_state_rows(transaction, states.iter())?;
    replace_statistic_rows(transaction, statistics.iter())?;
    Ok(())
}

fn validate_backup_states(states: &[GameBackupState]) -> Result<(), DataError> {
    let mut seen = BTreeMap::new();
    for state in states {
        if !is_backup_game_id(&state.game_id)
            || !(0..=MAX_SCORE).contains(&state.high_score)
            || state.updated_at < 0
            || seen.insert(state.game_id.as_str(), ()).is_some()
        {
            return Err(DataError::Validation(
                "Game backup state is invalid".to_owned(),
            ));
        }
        if let Some(save_json) = &state.save_json {
            validate_save_json(save_json)
                .map_err(|_| DataError::Validation("Game backup save is invalid".to_owned()))?;
        }
    }
    Ok(())
}

fn validate_backup_statistics(statistics: &[GameBackupStatistic]) -> Result<(), DataError> {
    let mut seen = BTreeMap::new();
    for statistic in statistics {
        let key = (statistic.game_id.as_str(), statistic.metric_key.as_str());
        if !is_backup_game_id(&statistic.game_id)
            || !supports_metric(&statistic.game_id, &statistic.metric_key)
            || statistic.value < 0
            || statistic.updated_at < 0
            || seen.insert(key, ()).is_some()
        {
            return Err(DataError::Validation(
                "Game backup statistic is invalid".to_owned(),
            ));
        }
    }
    Ok(())
}

fn replace_state_rows<'a>(
    transaction: &Transaction<'_>,
    states: impl IntoIterator<Item = &'a GameBackupState>,
) -> Result<(), DataError> {
    transaction.execute("DELETE FROM game_states", [])?;
    let mut statement = transaction.prepare(
        "INSERT INTO game_states(game_id, high_score, save_json, updated_at)
         VALUES(?1, ?2, ?3, ?4)",
    )?;
    for state in states {
        statement.execute(params![
            state.game_id,
            state.high_score,
            state.save_json,
            state.updated_at
        ])?;
    }
    Ok(())
}

fn replace_statistic_rows<'a>(
    transaction: &Transaction<'_>,
    statistics: impl IntoIterator<Item = &'a GameBackupStatistic>,
) -> Result<(), DataError> {
    transaction.execute("DELETE FROM game_statistics", [])?;
    let mut statement = transaction.prepare(
        "INSERT INTO game_statistics(game_id, metric_key, value, updated_at)
         VALUES(?1, ?2, ?3, ?4)",
    )?;
    for statistic in statistics {
        statement.execute(params![
            statistic.game_id,
            statistic.metric_key,
            statistic.value,
            statistic.updated_at
        ])?;
    }
    Ok(())
}

#[derive(Debug, Clone, Copy, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
enum SaveMode {
    Save,
    Finish,
    Clear,
    None,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct GameActionRequestV1 {
    dto_version: u32,
    game_id: String,
    save_mode: SaveMode,
    save_json: Option<String>,
    score: i64,
    #[serde(default)]
    increments: BTreeMap<String, String>,
    #[serde(default)]
    maxima: BTreeMap<String, String>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct GamePlayTimeRequestV1 {
    dto_version: u32,
    game_id: String,
    delta_millis: String,
}

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub(crate) struct GameStateDtoV1 {
    game_id: String,
    high_score: i64,
    save_json: Option<String>,
    updated_at: Option<String>,
    total_play_millis: String,
}

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub(crate) struct GameStatisticDtoV1 {
    game_id: String,
    metric_key: String,
    value: String,
    updated_at: String,
}

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub(crate) struct GamesSnapshotDtoV1 {
    dto_version: u32,
    games: Vec<GameStateDtoV1>,
    statistics: Vec<GameStatisticDtoV1>,
}

#[tauri::command]
pub(crate) fn get_games_snapshot(state: State<'_, AppState>) -> CommandResult<GamesSnapshotDtoV1> {
    load_snapshot(&state.database).map_err(map_storage_error)
}

#[tauri::command]
pub(crate) fn apply_game_action(
    state: State<'_, AppState>,
    request: GameActionRequestV1,
) -> CommandResult<GamesSnapshotDtoV1> {
    validate_action(&request)?;
    let _guard = GAME_WRITE_MUTEX
        .lock()
        .map_err(|_| SecurityErrorDto::storage_unavailable())?;
    apply_action(&state.database, request).map_err(map_storage_error)?;
    load_snapshot(&state.database).map_err(map_storage_error)
}

#[tauri::command]
pub(crate) fn add_game_play_time(
    state: State<'_, AppState>,
    request: GamePlayTimeRequestV1,
) -> CommandResult<GamesSnapshotDtoV1> {
    if request.dto_version != GAME_DTO_VERSION || !is_runtime_game_id(&request.game_id) {
        return Err(SecurityErrorDto::invalid_input());
    }
    let delta = parse_positive_decimal(&request.delta_millis)?;
    if delta > MAX_PLAY_TIME_DELTA_MILLIS {
        return Err(SecurityErrorDto::invalid_input());
    }
    let _guard = GAME_WRITE_MUTEX
        .lock()
        .map_err(|_| SecurityErrorDto::storage_unavailable())?;
    add_play_time(&state.database, &request.game_id, delta).map_err(map_storage_error)?;
    load_snapshot(&state.database).map_err(map_storage_error)
}

fn map_storage_error(_: DataError) -> SecurityErrorDto {
    SecurityErrorDto::storage_unavailable()
}

fn validate_action(request: &GameActionRequestV1) -> CommandResult<()> {
    if request.dto_version != GAME_DTO_VERSION
        || !is_runtime_game_id(&request.game_id)
        || !(0..=MAX_SCORE).contains(&request.score)
        || request.increments.keys().any(|metric| {
            !is_active_metric(&request.game_id, metric) || request.maxima.contains_key(metric)
        })
        || request
            .maxima
            .keys()
            .any(|metric| !is_active_metric(&request.game_id, metric))
    {
        return Err(SecurityErrorDto::invalid_input());
    }
    match request.save_mode {
        SaveMode::Save => {
            let save = request
                .save_json
                .as_deref()
                .ok_or_else(SecurityErrorDto::invalid_input)?;
            validate_save_json(save)?;
        }
        SaveMode::Finish | SaveMode::Clear | SaveMode::None => {
            if request.save_json.is_some() {
                return Err(SecurityErrorDto::invalid_input());
            }
        }
    }
    for value in request.increments.values() {
        parse_positive_decimal(value)?;
    }
    for value in request.maxima.values() {
        parse_non_negative_decimal(value)?;
    }
    Ok(())
}

fn validate_save_json(save: &str) -> CommandResult<()> {
    if save.len() < 2 || save.len() > MAX_GAME_SAVE_BYTES || save.trim().is_empty() {
        return Err(SecurityErrorDto::invalid_input());
    }
    let value: serde_json::Value =
        serde_json::from_str(save).map_err(|_| SecurityErrorDto::invalid_input())?;
    if !value.is_object() {
        return Err(SecurityErrorDto::invalid_input());
    }
    Ok(())
}

fn parse_positive_decimal(value: &str) -> CommandResult<i64> {
    let parsed = parse_non_negative_decimal(value)?;
    if parsed == 0 {
        return Err(SecurityErrorDto::invalid_input());
    }
    Ok(parsed)
}

fn parse_non_negative_decimal(value: &str) -> CommandResult<i64> {
    if value.is_empty() || value.len() > 19 || !value.bytes().all(|byte| byte.is_ascii_digit()) {
        return Err(SecurityErrorDto::invalid_input());
    }
    value
        .parse::<i64>()
        .map_err(|_| SecurityErrorDto::invalid_input())
}

fn is_runtime_game_id(game_id: &str) -> bool {
    RUNTIME_GAME_IDS.contains(&game_id)
}

fn is_backup_game_id(game_id: &str) -> bool {
    BACKUP_GAME_IDS.contains(&game_id)
}

fn is_private_game_id(game_id: &str) -> bool {
    PRIVATE_GAME_IDS.contains(&game_id)
}

fn supports_metric(game_id: &str, metric: &str) -> bool {
    let common = ["wins", "losses"];
    match game_id {
        "2048" | "2048_5" | "2048_6" => {
            common.contains(&metric)
                || ["moveAttempts", "effectiveMoves", "merges", "highestTile"].contains(&metric)
        }
        "snake" => ["losses", "foodEaten", "maxLength"].contains(&metric),
        "tetris" => ["losses", "piecesLocked", "linesCleared", "tetrises"].contains(&metric),
        "minesweeper" => {
            common.contains(&metric)
                || ["minesCellsRevealed", "minesSwept", "flagsPlaced"].contains(&metric)
        }
        "spider" => {
            common.contains(&metric)
                || ["spiderCardMoves", "spiderDeals", "spiderUndos"].contains(&metric)
        }
        "go" => [
            "goMovesPlayed",
            "goStonesCaptured",
            "goPasses",
            "goGamesCompleted",
        ]
        .contains(&metric),
        _ => false,
    }
}

fn is_active_metric(game_id: &str, metric: &str) -> bool {
    supports_metric(game_id, metric)
        && !(matches!(game_id, "2048" | "2048_5" | "2048_6") && metric == "losses")
}

fn apply_action(database: &Database, request: GameActionRequestV1) -> Result<(), DataError> {
    let mut connection = database.connect()?;
    let transaction = connection.transaction()?;
    let now = now_millis();

    if request.save_mode != SaveMode::None {
        let save_json = if request.save_mode == SaveMode::Save {
            request.save_json.as_deref()
        } else {
            None
        };
        if is_private_game_id(&request.game_id) {
            transaction.execute(
                r#"
                INSERT INTO private_game_states(game_id, high_score, save_json, updated_at)
                VALUES(?1, ?2, ?3, ?4)
                ON CONFLICT(game_id) DO UPDATE SET
                    high_score = MAX(private_game_states.high_score, excluded.high_score),
                    save_json = excluded.save_json,
                    updated_at = excluded.updated_at
                "#,
                params![request.game_id, request.score, save_json, now],
            )?;
        } else {
            transaction.execute(
                r#"
                INSERT INTO game_states(game_id, high_score, save_json, updated_at)
                VALUES(?1, ?2, ?3, ?4)
                ON CONFLICT(game_id) DO UPDATE SET
                    high_score = MAX(game_states.high_score, excluded.high_score),
                    save_json = excluded.save_json,
                    updated_at = excluded.updated_at
                "#,
                params![request.game_id, request.score, save_json, now],
            )?;
        }
    }

    for (metric, raw_delta) in &request.increments {
        let delta = raw_delta
            .parse::<i64>()
            .expect("validated decimal increment");
        if is_private_game_id(&request.game_id) {
            transaction.execute(
                r#"
                INSERT INTO private_game_statistics(game_id, metric_key, value, updated_at)
                VALUES(?1, ?2, ?3, ?4)
                ON CONFLICT(game_id, metric_key) DO UPDATE SET
                    value = CASE
                        WHEN private_game_statistics.value > 9223372036854775807 - excluded.value
                            THEN 9223372036854775807
                        ELSE private_game_statistics.value + excluded.value
                    END,
                    updated_at = excluded.updated_at
                "#,
                params![request.game_id, metric, delta, now],
            )?;
        } else {
            transaction.execute(
                r#"
                INSERT INTO game_statistics(game_id, metric_key, value, updated_at)
                VALUES(?1, ?2, ?3, ?4)
                ON CONFLICT(game_id, metric_key) DO UPDATE SET
                    value = CASE
                        WHEN game_statistics.value > 9223372036854775807 - excluded.value
                            THEN 9223372036854775807
                        ELSE game_statistics.value + excluded.value
                    END,
                    updated_at = excluded.updated_at
                "#,
                params![request.game_id, metric, delta, now],
            )?;
        }
    }
    for (metric, raw_candidate) in &request.maxima {
        let candidate = raw_candidate
            .parse::<i64>()
            .expect("validated decimal maximum");
        if is_private_game_id(&request.game_id) {
            transaction.execute(
                r#"
                INSERT INTO private_game_statistics(game_id, metric_key, value, updated_at)
                VALUES(?1, ?2, ?3, ?4)
                ON CONFLICT(game_id, metric_key) DO UPDATE SET
                    value = MAX(private_game_statistics.value, excluded.value),
                    updated_at = excluded.updated_at
                "#,
                params![request.game_id, metric, candidate, now],
            )?;
        } else {
            transaction.execute(
                r#"
                INSERT INTO game_statistics(game_id, metric_key, value, updated_at)
                VALUES(?1, ?2, ?3, ?4)
                ON CONFLICT(game_id, metric_key) DO UPDATE SET
                    value = MAX(game_statistics.value, excluded.value),
                    updated_at = excluded.updated_at
                "#,
                params![request.game_id, metric, candidate, now],
            )?;
        }
    }
    transaction.commit()?;
    Ok(())
}

fn add_play_time(database: &Database, game_id: &str, delta: i64) -> Result<(), DataError> {
    let mut connection = database.connect()?;
    let transaction = connection.transaction()?;
    if is_private_game_id(game_id) {
        transaction.execute(
            r#"
            INSERT INTO private_game_engagement_times(game_id, total_millis, updated_at)
            VALUES(?1, ?2, ?3)
            ON CONFLICT(game_id) DO UPDATE SET
                total_millis = CASE
                    WHEN private_game_engagement_times.total_millis > 9223372036854775807 - excluded.total_millis
                        THEN 9223372036854775807
                    ELSE private_game_engagement_times.total_millis + excluded.total_millis
                END,
                updated_at = excluded.updated_at
            "#,
            params![game_id, delta, now_millis()],
        )?;
    } else {
        transaction.execute(
            r#"
            INSERT INTO game_engagement_times(game_id, total_millis, updated_at)
            VALUES(?1, ?2, ?3)
            ON CONFLICT(game_id) DO UPDATE SET
                total_millis = CASE
                    WHEN game_engagement_times.total_millis > 9223372036854775807 - excluded.total_millis
                        THEN 9223372036854775807
                    ELSE game_engagement_times.total_millis + excluded.total_millis
                END,
                updated_at = excluded.updated_at
            "#,
            params![game_id, delta, now_millis()],
        )?;
    }
    transaction.commit()?;
    Ok(())
}

fn load_snapshot(database: &Database) -> Result<GamesSnapshotDtoV1, DataError> {
    let connection = database.connect()?;
    Ok(snapshot_from_connection(&connection)?)
}

fn snapshot_from_connection(connection: &Connection) -> rusqlite::Result<GamesSnapshotDtoV1> {
    let mut games = Vec::with_capacity(RUNTIME_GAME_IDS.len());
    for game_id in RUNTIME_GAME_IDS {
        let persisted = if is_private_game_id(game_id) {
            connection
                .query_row(
                    "SELECT high_score, save_json, updated_at FROM private_game_states WHERE game_id = ?1",
                    [game_id],
                    |row| {
                        Ok((
                            row.get::<_, i64>(0)?,
                            row.get::<_, Option<String>>(1)?,
                            row.get::<_, i64>(2)?,
                        ))
                    },
                )
                .optional()?
        } else {
            connection
                .query_row(
                    "SELECT high_score, save_json, updated_at FROM game_states WHERE game_id = ?1",
                    [game_id],
                    |row| {
                        Ok((
                            row.get::<_, i64>(0)?,
                            row.get::<_, Option<String>>(1)?,
                            row.get::<_, i64>(2)?,
                        ))
                    },
                )
                .optional()?
        };
        let total_play_millis = if is_private_game_id(game_id) {
            connection
                .query_row(
                    "SELECT total_millis FROM private_game_engagement_times WHERE game_id = ?1",
                    [game_id],
                    |row| row.get::<_, i64>(0),
                )
                .optional()?
                .unwrap_or(0)
        } else {
            connection
                .query_row(
                    "SELECT total_millis FROM game_engagement_times WHERE game_id = ?1",
                    [game_id],
                    |row| row.get::<_, i64>(0),
                )
                .optional()?
                .unwrap_or(0)
        };
        let (high_score, save_json, updated_at) = persisted
            .map(|(score, save, updated)| (score, save, Some(updated.to_string())))
            .unwrap_or((0, None, None));
        games.push(GameStateDtoV1 {
            game_id: game_id.to_owned(),
            high_score,
            save_json,
            updated_at,
            total_play_millis: total_play_millis.to_string(),
        });
    }

    let mut statement = connection.prepare(
        "SELECT game_id, metric_key, value, updated_at FROM game_statistics
         UNION ALL
         SELECT game_id, metric_key, value, updated_at FROM private_game_statistics
         ORDER BY game_id, metric_key",
    )?;
    let statistics = statement
        .query_map([], |row| {
            Ok(GameStatisticDtoV1 {
                game_id: row.get(0)?,
                metric_key: row.get(1)?,
                value: row.get::<_, i64>(2)?.to_string(),
                updated_at: row.get::<_, i64>(3)?.to_string(),
            })
        })?
        .collect::<rusqlite::Result<Vec<_>>>()?;
    Ok(GamesSnapshotDtoV1 {
        dto_version: GAME_DTO_VERSION,
        games,
        statistics,
    })
}

fn now_millis() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis()
        .min(i64::MAX as u128) as i64
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::TempDir;

    fn database_with_games() -> (TempDir, Database) {
        let directory = tempfile::tempdir().expect("temporary directory");
        let database = Database::open(directory.path().join("games.db")).expect("database");
        (directory, database)
    }

    fn action(
        game_id: &str,
        save: &str,
        score: i64,
        increments: &[(&str, &str)],
    ) -> GameActionRequestV1 {
        GameActionRequestV1 {
            dto_version: GAME_DTO_VERSION,
            game_id: game_id.to_owned(),
            save_mode: SaveMode::Save,
            save_json: Some(save.to_owned()),
            score,
            increments: increments
                .iter()
                .map(|(key, value)| ((*key).to_owned(), (*value).to_owned()))
                .collect(),
            maxima: BTreeMap::new(),
        }
    }

    #[test]
    fn migration_is_transactional() {
        let mut connection = Connection::open_in_memory().expect("connection");
        {
            let transaction = connection.transaction().expect("transaction");
            migrate(&transaction).expect("schema");
            transaction
                .execute("INSERT INTO missing_table(value) VALUES(1)", [])
                .expect_err("force rollback");
            transaction.rollback().expect("rollback");
        }
        let table_count: i64 = connection
            .query_row(
                "SELECT COUNT(*) FROM sqlite_master WHERE name = 'game_states'",
                [],
                |row| row.get(0),
            )
            .expect("table count");
        assert_eq!(table_count, 0);
    }

    #[test]
    fn private_game_migration_is_transactional() {
        let mut connection = Connection::open_in_memory().expect("connection");
        {
            let transaction = connection.transaction().expect("transaction");
            migrate_private(&transaction).expect("private schema");
            transaction
                .execute("INSERT INTO missing_table(value) VALUES(1)", [])
                .expect_err("force rollback");
            transaction.rollback().expect("rollback");
        }
        let table_count: i64 = connection
            .query_row(
                "SELECT COUNT(*) FROM sqlite_master WHERE name LIKE 'private_game_%'",
                [],
                |row| row.get(0),
            )
            .expect("table count");
        assert_eq!(table_count, 0);
    }

    #[test]
    fn action_commits_save_score_and_metrics_together() {
        let (_directory, database) = database_with_games();
        let mut request = action(
            "2048",
            r#"{"size":4,"cells":[2],"score":4}"#,
            4,
            &[
                ("moveAttempts", "1"),
                ("effectiveMoves", "1"),
                ("merges", "1"),
            ],
        );
        request.maxima.insert("highestTile".into(), "4".into());
        validate_action(&request).expect("valid action");
        apply_action(&database, request).expect("apply");

        let snapshot = load_snapshot(&database).expect("snapshot");
        let game = snapshot
            .games
            .iter()
            .find(|game| game.game_id == "2048")
            .expect("2048 state");
        assert_eq!(game.high_score, 4);
        assert!(game.save_json.is_some());
        assert_eq!(snapshot.statistics.len(), 4);
    }

    #[test]
    fn move_attempts_are_active_but_legacy_2048_losses_are_backup_only() {
        let (_directory, database) = database_with_games();
        let attempt = action(
            "2048",
            r#"{"size":4,"cells":[2],"score":0}"#,
            0,
            &[("moveAttempts", "1")],
        );
        validate_action(&attempt).expect("move attempt is active");
        apply_action(&database, attempt).expect("record attempt");

        let loss = action(
            "2048",
            r#"{"size":4,"cells":[2],"score":0}"#,
            0,
            &[("losses", "1")],
        );
        assert!(validate_action(&loss).is_err());
        assert!(
            validate_backup_statistics(&[GameBackupStatistic {
                game_id: "2048".to_owned(),
                metric_key: "losses".to_owned(),
                value: 3,
                updated_at: 1,
            }])
            .is_ok()
        );
    }

    #[test]
    fn invalid_metric_cannot_partially_replace_a_save() {
        let (_directory, database) = database_with_games();
        let valid = action("snake", r#"{"score":10}"#, 10, &[("foodEaten", "1")]);
        apply_action(&database, valid).expect("initial save");

        let invalid = action("snake", r#"{"score":999}"#, 999, &[("tetrises", "1")]);
        assert!(validate_action(&invalid).is_err());
        let snapshot = load_snapshot(&database).expect("snapshot");
        let snake = snapshot
            .games
            .iter()
            .find(|game| game.game_id == "snake")
            .expect("snake state");
        assert_eq!(snake.high_score, 10);
    }

    #[test]
    fn play_time_is_private_and_saturating() {
        let (_directory, database) = database_with_games();
        add_play_time(&database, "tetris", 12_345).expect("play time");
        let snapshot = load_snapshot(&database).expect("snapshot");
        let tetris = snapshot
            .games
            .iter()
            .find(|game| game.game_id == "tetris")
            .expect("tetris state");
        assert_eq!(tetris.total_play_millis, "12345");
        assert!(snapshot.statistics.is_empty());
    }

    #[test]
    fn go_runtime_rows_are_durable_but_excluded_from_backup_and_recovery() {
        let (_directory, database) = database_with_games();
        let request = action(
            "go",
            r#"{"v":1,"size":9,"board":[0],"current":1}"#,
            3,
            &[("goMovesPlayed", "1"), ("goStonesCaptured", "3")],
        );
        validate_action(&request).expect("valid Go action");
        apply_action(&database, request).expect("persist Go action");
        add_play_time(&database, "go", 4_321).expect("Go play time");

        let snapshot = load_snapshot(&database).expect("runtime snapshot");
        let go = snapshot
            .games
            .iter()
            .find(|game| game.game_id == "go")
            .expect("Go state");
        assert_eq!(go.high_score, 3);
        assert!(go.save_json.is_some());
        assert_eq!(go.total_play_millis, "4321");
        assert_eq!(
            snapshot
                .statistics
                .iter()
                .filter(|statistic| statistic.game_id == "go")
                .count(),
            2
        );

        let connection = database.connect().expect("connection");
        let (states, statistics) = list_backup_rows(&connection).expect("backup projection");
        assert!(states.iter().all(|state| state.game_id != "go"));
        assert!(statistics.iter().all(|statistic| statistic.game_id != "go"));
        assert!(
            validate_backup_states(&[GameBackupState {
                game_id: "go".to_owned(),
                high_score: 3,
                save_json: None,
                updated_at: 1,
            }])
            .is_err()
        );
        assert!(
            validate_backup_statistics(&[GameBackupStatistic {
                game_id: "go".to_owned(),
                metric_key: "goMovesPlayed".to_owned(),
                value: 1,
                updated_at: 1,
            }])
            .is_err()
        );

        let mut invalid = action("go", r#"{"v":1}"#, 0, &[("wins", "1")]);
        assert!(validate_action(&invalid).is_err());
        invalid.increments = [("goPasses".to_owned(), "1".to_owned())]
            .into_iter()
            .collect();
        validate_action(&invalid).expect("Go pass metric");

        let mut connection = database.connect().expect("replacement connection");
        let transaction = connection.transaction().expect("transaction");
        replace_backup_rows(&transaction, &[], &[]).expect("replace compatible rows");
        transaction.commit().expect("commit replacement");
        let snapshot = load_snapshot(&database).expect("snapshot after recovery");
        assert_eq!(
            snapshot
                .games
                .iter()
                .find(|game| game.game_id == "go")
                .expect("Go preserved")
                .high_score,
            3
        );
        assert!(snapshot.statistics.iter().any(|statistic| {
            statistic.game_id == "go" && statistic.metric_key == "goStonesCaptured"
        }));
    }

    #[test]
    fn backup_merge_matches_android_and_recovery_does_not_touch_engagement() {
        let (_directory, database) = database_with_games();
        let mut connection = database.connect().expect("connection");
        {
            let transaction = connection.transaction().expect("transaction");
            replace_backup_rows(
                &transaction,
                &[GameBackupState {
                    game_id: "2048".to_owned(),
                    high_score: 100,
                    save_json: Some(r#"{"local":true}"#.to_owned()),
                    updated_at: 100,
                }],
                &[GameBackupStatistic {
                    game_id: "2048".to_owned(),
                    metric_key: "effectiveMoves".to_owned(),
                    value: 8,
                    updated_at: 100,
                }],
            )
            .expect("seed backup rows");
            transaction
                .execute(
                    "INSERT INTO game_engagement_times(game_id, total_millis, updated_at)
                     VALUES('2048', 12345, 100)",
                    [],
                )
                .expect("engagement");
            transaction.commit().expect("seed commit");
        }

        {
            let transaction = connection.transaction().expect("transaction");
            merge_backup_rows(
                &transaction,
                &[
                    GameBackupState {
                        game_id: "2048".to_owned(),
                        high_score: 90,
                        save_json: Some(r#"{"remote":true}"#.to_owned()),
                        updated_at: 200,
                    },
                    GameBackupState {
                        game_id: "snake".to_owned(),
                        high_score: 20,
                        save_json: None,
                        updated_at: 50,
                    },
                ],
                &[GameBackupStatistic {
                    game_id: "2048".to_owned(),
                    metric_key: "effectiveMoves".to_owned(),
                    value: 5,
                    updated_at: 200,
                }],
                true,
                true,
            )
            .expect("merge");
            transaction.commit().expect("merge commit");
        }

        let (states, statistics) = list_backup_rows(&connection).expect("merged rows");
        let merged = states
            .iter()
            .find(|state| state.game_id == "2048")
            .expect("2048");
        assert_eq!(merged.high_score, 100);
        assert_eq!(merged.save_json.as_deref(), Some(r#"{"remote":true}"#));
        assert_eq!(merged.updated_at, 200);
        assert!(states.iter().any(|state| state.game_id == "snake"));
        assert_eq!(statistics[0].value, 8);
        assert_eq!(statistics[0].updated_at, 200);

        {
            let transaction = connection.transaction().expect("transaction");
            replace_backup_rows(
                &transaction,
                &[GameBackupState {
                    game_id: "tetris".to_owned(),
                    high_score: 7,
                    save_json: None,
                    updated_at: 300,
                }],
                &[],
            )
            .expect("exact recovery replacement");
            transaction.commit().expect("recovery commit");
        }
        let engagement: i64 = connection
            .query_row(
                "SELECT total_millis FROM game_engagement_times WHERE game_id = '2048'",
                [],
                |row| row.get(0),
            )
            .expect("private engagement survives");
        assert_eq!(engagement, 12_345);
        let (states, statistics) = list_backup_rows(&connection).expect("recovery rows");
        assert_eq!(states.len(), 1);
        assert_eq!(states[0].game_id, "tetris");
        assert!(statistics.is_empty());
    }
}
