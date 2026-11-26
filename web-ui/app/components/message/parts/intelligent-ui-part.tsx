import { useEffect, useRef, useState } from "react";
import { useTranslation } from "react-i18next";

export function IntelligentUiPart({ source, done }: { source: string; done: boolean }) {
  const frame = useRef<HTMLIFrameElement>(null);
  const port = useRef<MessagePort | null>(null);
  const [connected, setConnected] = useState(false);
  const revision = useRef(0);
  const ackTimer = useRef<number | undefined>(undefined);
  const [failed, setFailed] = useState(false);
  const [partial, setPartial] = useState(false);
  const [height, setHeight] = useState(64);
  const { i18n } = useTranslation();
  const lang = i18n.resolvedLanguage ?? i18n.language;
  const zh = String(lang).toLowerCase().startsWith("zh");

  useEffect(() => {
    if (connected || failed) return;
    const timer = window.setTimeout(() => setFailed(true), 10000);
    return () => window.clearTimeout(timer);
  }, [connected, failed]);

  useEffect(
    () => () => {
      port.current?.close();
      port.current = null;
    },
    [failed],
  );

  useEffect(() => {
    if (!connected || failed) return;
    const send = () => {
      window.clearTimeout(ackTimer.current);
      if (done) ackTimer.current = window.setTimeout(() => setPartial(true), 10000);
      port.current?.postMessage({
        revision: ++revision.current,
        source: source.slice(0, 65536),
        done,
        lang,
        dark: document.documentElement.classList.contains("dark"),
      });
    };
    send();
    const observer = new MutationObserver(send);
    observer.observe(document.documentElement, { attributes: true, attributeFilter: ["class"] });
    return () => {
      observer.disconnect();
      window.clearTimeout(ackTimer.current);
    };
  }, [connected, failed, source, done, lang]);

  function connect() {
    if (port.current) {
      setFailed(true);
      return;
    }
    const channel = new MessageChannel();
    port.current = channel.port1;
    channel.port1.onmessage = ({ data }) => {
      switch (data?.type) {
        case "ready":
          setConnected(true);
          break;
        case "final":
          if (data.height === revision.current) window.clearTimeout(ackTimer.current);
          break;
        case "partial":
          setPartial(true);
          break;
        case "height":
          if (Number.isSafeInteger(data.height) && data.height >= 0) {
            setHeight(Math.max(36, data.height));
          }
          break;
        case "failed":
          setFailed(true);
          break;
      }
    };
    frame.current?.contentWindow?.postMessage("rikka-ui:connect", "*", [channel.port2]);
  }

  if (failed) {
    return (
      <pre className="rikka-glass-card whitespace-pre-wrap p-3 text-xs overflow-x-auto">
        {source}
      </pre>
    );
  }

  return (
    <div className="my-2 w-full">
      <iframe
        ref={frame}
        title="Rikka Interactive Card"
        src="/intelligent-ui/index.html"
        sandbox="allow-scripts"
        className="w-full border-0 bg-transparent"
        style={{ height, colorScheme: "normal" }}
        onLoad={connect}
      />
      {partial && (
        <details className="mt-1 text-xs text-muted-foreground">
          <summary className="cursor-pointer select-none">
            {zh ? "查看卡片源码" : "View widget source"}
          </summary>
          <pre className="mt-1 whitespace-pre-wrap rounded-lg border border-border/50 bg-muted/30 p-2 text-xs overflow-x-auto">
            {source}
          </pre>
        </details>
      )}
    </div>
  );
}
