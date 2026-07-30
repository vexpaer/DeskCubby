import { invokeCommand } from "./ipc";

export const VAULT_DTO_VERSION = 1 as const;

export type DecimalI64 = string;

export interface VaultStatusV1 {
  schemaVersion: typeof VAULT_DTO_VERSION;
  lockState: "NOT_SET" | "LOCKED" | "UNLOCKED";
  corruptedItemCount: number;
}

export interface VaultItemV1 {
  id: DecimalI64;
  content: string;
  note: string | null;
  sortOrder: DecimalI64;
  createdAt: DecimalI64;
  updatedAt: DecimalI64;
  primaryAction: "COPY" | "OPEN_URL";
}

export interface VaultItemDraftV1 {
  content: string;
  note: string | null;
}

function passwordRequest(password: string) {
  return {
    request: {
      schemaVersion: VAULT_DTO_VERSION,
      password,
    },
  };
}

export const vaultApi = {
  status(): Promise<VaultStatusV1> {
    return invokeCommand("get_vault_status");
  },

  setup(password: string): Promise<VaultStatusV1> {
    return invokeCommand("setup_vault", passwordRequest(password));
  },

  unlock(password: string): Promise<VaultStatusV1> {
    return invokeCommand("unlock_vault", passwordRequest(password));
  },

  lock(): Promise<VaultStatusV1> {
    return invokeCommand("lock_vault");
  },

  list(): Promise<VaultItemV1[]> {
    return invokeCommand("list_vault_items");
  },

  create(draft: VaultItemDraftV1): Promise<VaultItemV1> {
    return invokeCommand("create_vault_item", {
      request: { schemaVersion: VAULT_DTO_VERSION, ...draft },
    });
  },

  update(id: DecimalI64, draft: VaultItemDraftV1): Promise<VaultItemV1> {
    return invokeCommand("update_vault_item", {
      request: { schemaVersion: VAULT_DTO_VERSION, id, ...draft },
    });
  },

  remove(id: DecimalI64): Promise<void> {
    return invokeCommand("delete_vault_item", {
      request: { schemaVersion: VAULT_DTO_VERSION, id },
    });
  },

  reorder(ids: DecimalI64[]): Promise<VaultItemV1[]> {
    return invokeCommand("reorder_vault_items", {
      request: { schemaVersion: VAULT_DTO_VERSION, ids },
    });
  },

  changePassword(currentPassword: string, newPassword: string): Promise<void> {
    return invokeCommand("change_vault_password", {
      request: {
        schemaVersion: VAULT_DTO_VERSION,
        currentPassword,
        newPassword,
      },
    });
  },

  copyItem(id: DecimalI64): Promise<void> {
    return invokeCommand("copy_vault_item", {
      request: { schemaVersion: VAULT_DTO_VERSION, id },
    });
  },

  openItem(id: DecimalI64): Promise<void> {
    return invokeCommand("open_vault_item_url", {
      request: { schemaVersion: VAULT_DTO_VERSION, id },
    });
  },
};
