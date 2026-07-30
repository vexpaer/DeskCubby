#![recursion_limit = "512"]

mod backup;
mod cloud_sync;
mod commands;
mod db;
mod diary;
mod media;
mod media_protocol;
mod models;
mod poetry;
mod security;
pub mod updater;
mod usage;
mod vault;
mod vault_persistence;
mod watcher;

use std::path::PathBuf;
use std::sync::Arc;
use std::time::Duration;
use tauri::{Emitter, Manager};

const AUTO_BACKUP_INITIAL_DELAY: Duration = Duration::from_secs(30);
const AUTO_BACKUP_INTERVAL: Duration = Duration::from_secs(5 * 60);

#[derive(Clone)]
pub struct AppState {
    pub database: db::Database,
    pub private_dir: PathBuf,
    pub diary_watcher: watcher::DiaryWatcher,
    pub usage_statistics: Arc<usage::UsageStatisticsService>,
    pub vault: Arc<vault::VaultService>,
    pub(crate) cloud_sync: Arc<cloud_sync::commands::CloudSyncService>,
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .register_uri_scheme_protocol("media", media_protocol::handle)
        .plugin(tauri_plugin_dialog::init())
        .plugin(updater::plugin())
        .setup(|app| {
            let private_dir = app
                .path()
                .app_local_data_dir()
                .map_err(|error| format!("APP_DATA_UNAVAILABLE: {error}"))?;
            std::fs::create_dir_all(&private_dir)?;
            let database = db::Database::open(private_dir.join("deskcubby.db"))?;
            let usage_statistics = Arc::new(usage::UsageStatisticsService::new(
                private_dir.join("phone-usage"),
            )?);
            let vault = Arc::new(vault::VaultService::new(
                vault_persistence::DatabaseVaultStore::new(database.clone()),
            ));
            let diary_watcher = watcher::DiaryWatcher::default();
            let diary_path = database
                .get_local_paths()
                .ok()
                .and_then(|paths| paths.diary_path);
            diary_watcher.set_directory(app.handle().clone(), diary_path.as_deref());
            let cloud_event_app = app.handle().clone();
            let cloud_sync = Arc::new(cloud_sync::commands::CloudSyncService::new(
                database.clone(),
                private_dir.clone(),
                Arc::new(move || {
                    let _ = cloud_event_app.emit("diary-index-changed", ());
                }),
            )?);
            cloud_sync.start_scheduler();
            let automatic_update_store = database.clone();
            app.manage(AppState {
                database,
                private_dir,
                diary_watcher,
                usage_statistics,
                vault,
                cloud_sync,
            });
            updater::spawn_automatic_update_scheduler(app.handle().clone(), automatic_update_store);
            let backup_app = app.handle().clone();
            tauri::async_runtime::spawn(async move {
                tokio::time::sleep(AUTO_BACKUP_INITIAL_DELAY).await;
                loop {
                    let handle = backup_app.clone();
                    let _ = tokio::task::spawn_blocking(move || {
                        let state = handle.state::<AppState>();
                        // Automatic backups are best-effort. A missing optional directory or
                        // transient storage failure is surfaced when the user runs a backup
                        // explicitly, without leaking local paths into logs.
                        let _ = commands::run_automatic_backup_for_state(&state);
                    })
                    .await;
                    tokio::time::sleep(AUTO_BACKUP_INTERVAL).await;
                }
            });
            Ok(())
        })
        .invoke_handler(commands::handler())
        .run(tauri::generate_context!())
        .expect("failed to run DeskCubby");
}
