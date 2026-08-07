import { invokeCommand } from "./ipc";

export const RSS_DTO_VERSION = 1 as const;

export interface RssSubscriptionV1 {
  id: string;
  title: string;
  url: string;
  enabled: boolean;
}

export interface RssArticleV1 {
  id: string;
  feedId: string;
  feedTitle: string;
  title: string;
  urlAvailable: boolean;
  summary: string;
  publishedAtMs: string | null;
}

export interface RssFeedErrorV1 {
  feedId: string;
  code: string;
}

export interface RssPageV1 {
  dtoVersion: typeof RSS_DTO_VERSION;
  subscriptions: RssSubscriptionV1[];
  maxItemsPerFeed: number;
  showSummaries: boolean;
  articles: RssArticleV1[];
  errors: RssFeedErrorV1[];
  lastUpdatedAtMs: string | null;
}

export interface RssSubscriptionDraftV1 {
  id?: string | null;
  title: string;
  url: string;
}

export const rssApi = {
  page(): Promise<RssPageV1> {
    return invokeCommand("get_rss_page");
  },

  refresh(): Promise<RssPageV1> {
    return invokeCommand("refresh_rss");
  },

  save(draft: RssSubscriptionDraftV1): Promise<RssPageV1> {
    return invokeCommand("save_rss_subscription", {
      request: {
        dtoVersion: RSS_DTO_VERSION,
        id: draft.id ?? null,
        title: draft.title,
        url: draft.url,
      },
    });
  },

  remove(id: string): Promise<RssPageV1> {
    return invokeCommand("delete_rss_subscription", {
      request: { dtoVersion: RSS_DTO_VERSION, id },
    });
  },

  setEnabled(id: string, enabled: boolean): Promise<RssPageV1> {
    return invokeCommand("set_rss_subscription_enabled", {
      request: { dtoVersion: RSS_DTO_VERSION, id, enabled },
    });
  },

  setPreferences(
    maxItemsPerFeed: number,
    showSummaries: boolean,
  ): Promise<RssPageV1> {
    return invokeCommand("set_rss_preferences", {
      request: {
        dtoVersion: RSS_DTO_VERSION,
        maxItemsPerFeed,
        showSummaries,
      },
    });
  },

  openArticle(articleId: string): Promise<void> {
    return invokeCommand("open_rss_article", {
      request: { dtoVersion: RSS_DTO_VERSION, articleId },
    });
  },
};
