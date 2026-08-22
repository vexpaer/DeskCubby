/** Login gate shown when access-password auth is enabled. */
import React, { useState } from "react";
import { useNavigate } from "react-router-dom";
import { apiGet, apiSend } from "../../api/client";
import { tr } from "../../i18n/tr";
import { ErrorText } from "../../components/ui";

interface AuthStatus {
  enabled: boolean;
  authenticated: boolean;
  deployment?: { suggestHttps?: boolean; behindProxy?: boolean; scheme?: string };
}

export default function LoginPage() {
  const [password, setPassword] = useState("");
  const [error, setError] = useState<unknown>(null);
  const [status, setStatus] = useState<AuthStatus | null>(null);
  const navigate = useNavigate();

  React.useEffect(() => {
    apiGet<AuthStatus>("/api/auth/status").then(setStatus).catch(() => undefined);
  }, []);

  const login = async () => {
    setError(null);
    try {
      await apiSend("/api/auth/login", "POST", { password });
      navigate("/", { replace: true });
      location.reload();
    } catch (e) {
      setError(e);
    }
  };

  return (
    <div className="dc-center" style={{ minHeight: "100vh", padding: 16 }}>
      <div className="dc-surface" style={{ padding: 28, width: "min(420px, 94vw)" }}>
        <div className="dc-title" style={{ marginBottom: 4 }}>DeskCubby</div>
        <div className="dc-muted" style={{ marginBottom: 18 }}>
          {tr("此实例已开启访问密码，请登录后继续。", "This instance is protected by an access password.")}
        </div>
        <input
          className="dc-input"
          type="password"
          autoFocus
          placeholder={tr("访问密码", "Access password")}
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          onKeyDown={(e) => e.key === "Enter" && void login()}
        />
        <ErrorText error={error} />
        <button className="dc-btn dc-btn-filled" style={{ marginTop: 14, width: "100%", justifyContent: "center" }} onClick={() => void login()}>
          {tr("解锁", "Unlock")}
        </button>
        {status?.deployment?.suggestHttps && (
          <div className="dc-muted" style={{ fontSize: "0.82em", marginTop: 14 }}>
            {tr(
              "检测到当前未使用 HTTPS。公网部署建议同时开启访问密码并通过 Caddy/Nginx 反向代理启用 HTTPS。",
              "HTTPS is not active. For public deployments enable the access password and serve via a Caddy/Nginx reverse proxy with HTTPS."
            )}
          </div>
        )}
      </div>
    </div>
  );
}
