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
  const [height, setHeight] = useState(40);
  const { i18n } = useTranslation();
  const lang = i18n.resolvedLanguage ?? i18n.language;
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
      if (done) ackTimer.current = window.setTimeout(() => setFailed(true), 10000);
      port.current?.postMessage({
        revision: ++revision.current,
        source: source.slice(0, 65537),
        done,
        lang,
        dark: document.documentElement.classList.contains("dark"),
      });
    };
    send();
    const observer = new MutationObserver(send);
    observer.observe(document.documentElement, { attributes: true, attributeFilter: ["class"] });
    return () => { observer.disconnect(); window.clearTimeout(ackTimer.current); };
  }, [connected, failed, source, done, lang]);
  function connect() {
    if (port.current) {
      setFailed(true);
      return;
    } // Never resend an answer after navigation.
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
          if (Number.isFinite(data.height)) setHeight(Math.max(24, Math.min(2400, data.height)));
          break;
        case "failed":
          setFailed(true);
          break;
      }
    };
    // Opaque sandbox origin: only transfer a port, never a message body, via '*'.
    frame.current?.contentWindow?.postMessage("rikka-ui:connect", "*", [channel.port2]);
  }
  if (failed) return <pre className="whitespace-pre-wrap">{source}</pre>;
  return (
    <>
      <iframe
        ref={frame}
        title="Intelligent UI"
        src="/intelligent-ui/index.html"
        sandbox="allow-scripts"
        className="w-full border-0"
        style={{ height }}
        onLoad={connect}
      />
      {partial && (
        <details>
          <summary>{lang?.startsWith("zh") ? "查看完整原文" : "View original text"}</summary>
          <pre className="whitespace-pre-wrap">{source}</pre>
        </details>
      )}
    </>
  );
}
