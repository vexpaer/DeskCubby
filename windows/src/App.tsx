import { lazy, Suspense } from "react";
import { Link, Route, Routes } from "react-router-dom";
import {
  AppShell,
  ErrorBoundary,
  ErrorState,
  LoadingState,
  PageFrame,
  ThemeRuntime,
} from "./components";
import { translate } from "./i18n";
import { readableError } from "./lib/ipc";
import { useAppStore } from "./store/appStore";

const HomePage = lazy(() => import("./pages/HomePage"));
const DiaryPage = lazy(() => import("./pages/DiaryPage"));
const MealPage = lazy(() => import("./pages/MealPage"));
const DailyRecordPage = lazy(() => import("./pages/DailyRecordPage"));
const ThoughtsPage = lazy(() => import("./pages/ThoughtsPage"));
const DateRecordsPage = lazy(() => import("./pages/DateRecordsPage"));
const PoetryPage = lazy(() => import("./pages/PoetryPage"));
const BackupPage = lazy(() => import("./pages/BackupPage"));
const SettingsPage = lazy(() => import("./pages/SettingsPage"));
const VaultPage = lazy(() => import("./pages/VaultPage"));
const UsagePage = lazy(() => import("./pages/UsagePage"));
const AiPage = lazy(() => import("./pages/AiPage"));
const AiSettingsPage = lazy(() => import("./pages/settings/AiSettingsPage"));
const CloudSyncPage = lazy(() => import("./pages/CloudSyncPage"));
const AboutPage = lazy(() => import("./pages/AboutPage"));
const NotesPage = lazy(() => import("./pages/NotesPage"));
const ReaderPage = lazy(() => import("./pages/ReaderPage"));
const RssPage = lazy(() => import("./pages/RssPage"));
const GamesPage = lazy(() => import("./pages/GamesPage"));
const StatsPage = lazy(() => import("./pages/StatsPage"));
const HealthPage = lazy(() => import("./pages/HealthPage"));
const MorePage = lazy(() => import("./pages/MorePage"));

function NotFoundPage() {
  const language = useAppStore((state) => state.appearance.language);
  return (
    <PageFrame title={translate(language, "notFound.title")}>
      <div className="panel empty-state">
        <p>{translate(language, "notFound.description")}</p>
        <Link className="button button-primary" to="/">
          {translate(language, "action.backHome")}
        </Link>
      </div>
    </PageFrame>
  );
}

function AppRoutes() {
  const language = useAppStore((state) => state.appearance.language);
  return (
    <ErrorBoundary
      fallback={(reset) => (
        <PageFrame title={translate(language, "status.error")}>
          <ErrorState retry={reset} />
        </PageFrame>
      )}
    >
      <Suspense fallback={<LoadingState />}>
        <Routes>
          <Route path="/" element={<HomePage />} />
          <Route path="/diary" element={<DiaryPage />} />
          <Route path="/meals" element={<MealPage />} />
          <Route path="/daily" element={<DailyRecordPage />} />
          <Route path="/thoughts" element={<ThoughtsPage />} />
          <Route path="/dates" element={<DateRecordsPage />} />
          <Route path="/poetry" element={<PoetryPage />} />
          <Route path="/vault" element={<VaultPage />} />
          <Route path="/usage" element={<UsagePage />} />
          <Route path="/ai" element={<AiPage />} />
          <Route path="/health" element={<HealthPage />} />
          <Route path="/notes" element={<NotesPage />} />
          <Route path="/reader" element={<ReaderPage />} />
          <Route path="/rss" element={<RssPage />} />
          <Route path="/games" element={<GamesPage />} />
          <Route path="/statistics" element={<StatsPage />} />
          <Route path="/more" element={<MorePage />} />
          <Route path="/backup" element={<BackupPage />} />
          <Route path="/settings/data/sync" element={<CloudSyncPage />} />
          <Route path="/settings/cloud" element={<CloudSyncPage />} />
          <Route path="/settings/about" element={<AboutPage />} />
          <Route path="/settings/updates" element={<AboutPage />} />
          <Route path="/settings/ai" element={<AiSettingsPage />} />
          <Route path="/settings/*" element={<SettingsPage />} />
          <Route path="*" element={<NotFoundPage />} />
        </Routes>
      </Suspense>
    </ErrorBoundary>
  );
}

export default function App() {
  const language = useAppStore((state) => state.appearance.language);
  return (
    <ErrorBoundary
      fallback={(reset, error) => (
        <div className="startup-screen">
          <PageFrame title={translate(language, "status.error")}>
            <ErrorState
              description={readableError(error, language)}
              retry={reset}
            />
          </PageFrame>
        </div>
      )}
    >
      <ThemeRuntime>
        <AppShell>
          <AppRoutes />
        </AppShell>
      </ThemeRuntime>
    </ErrorBoundary>
  );
}
