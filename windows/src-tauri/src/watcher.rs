use notify::{Config, Event, EventKind, RecommendedWatcher, RecursiveMode, Watcher};
use std::path::Path;
use std::sync::{
    Arc, Mutex,
    atomic::{AtomicU64, Ordering},
};
use std::time::{SystemTime, UNIX_EPOCH};
use tauri::{AppHandle, Emitter, Runtime};

const EVENT_NAME: &str = "diary-index-changed";
const EMIT_DEBOUNCE_MILLIS: u64 = 120;

#[derive(Clone, Default)]
pub struct DiaryWatcher {
    active: Arc<Mutex<Option<RecommendedWatcher>>>,
}

impl DiaryWatcher {
    /// Replaces the active non-recursive watcher. Failure is deliberately
    /// non-fatal: saves still use SHA-256 conflict checks and the UI exposes a
    /// manual rescan, so a transient watcher failure must not make a persisted
    /// settings update look unsuccessful.
    pub fn set_directory<R: Runtime>(&self, app: AppHandle<R>, root: Option<&str>) {
        let Ok(mut active) = self.active.lock() else {
            return;
        };
        *active = None;

        let Some(root) = root else {
            return;
        };
        let Ok(root) = crate::diary::validate_directory(Path::new(root)) else {
            return;
        };

        let last_emit = Arc::new(AtomicU64::new(0));
        let callback_emit = Arc::clone(&last_emit);
        let Ok(mut watcher) = RecommendedWatcher::new(
            move |result: notify::Result<Event>| {
                let Ok(event) = result else {
                    return;
                };
                if !is_diary_event(&event) {
                    return;
                }
                let now = SystemTime::now()
                    .duration_since(UNIX_EPOCH)
                    .map(|duration| duration.as_millis() as u64)
                    .unwrap_or(0);
                let previous = callback_emit.swap(now, Ordering::Relaxed);
                if now.saturating_sub(previous) >= EMIT_DEBOUNCE_MILLIS {
                    let _ = app.emit(EVENT_NAME, ());
                }
            },
            Config::default(),
        ) else {
            return;
        };
        if watcher.watch(&root, RecursiveMode::NonRecursive).is_ok() {
            *active = Some(watcher);
        }
    }
}

fn is_diary_event(event: &Event) -> bool {
    if !matches!(
        event.kind,
        EventKind::Create(_) | EventKind::Modify(_) | EventKind::Remove(_)
    ) {
        return false;
    }
    event.paths.iter().any(|path| {
        path.extension()
            .and_then(|extension| extension.to_str())
            .is_some_and(|extension| extension.eq_ignore_ascii_case("md"))
            || is_trash_directory(path)
    })
}

fn is_trash_directory(path: &Path) -> bool {
    path.file_name()
        .and_then(|name| name.to_str())
        .is_some_and(|name| name.eq_ignore_ascii_case(".DeskCubby Trash"))
}

#[cfg(test)]
mod tests {
    use super::*;
    use notify::event::{CreateKind, ModifyKind};
    use std::path::PathBuf;

    #[test]
    fn filters_events_to_markdown_and_trash() {
        let markdown = Event {
            kind: EventKind::Modify(ModifyKind::Any),
            paths: vec![PathBuf::from("2026-07-29 note.MD")],
            attrs: Default::default(),
        };
        let image = Event {
            kind: EventKind::Create(CreateKind::Any),
            paths: vec![PathBuf::from("photo.png")],
            attrs: Default::default(),
        };
        assert!(is_diary_event(&markdown));
        assert!(!is_diary_event(&image));
    }
}
