import Markdown from "~/components/markdown/markdown";
import { IntelligentUiPart } from "./intelligent-ui-part";

interface TextPartProps {
  intelligentUi?: boolean;
  text: string;
  isAnimating?: boolean;
  onClickCitation?: (id: string) => void;
}

export function TextPart({ text, intelligentUi, isAnimating, onClickCitation }: TextPartProps) {
  if (!text) return null;
  if (intelligentUi) {
    const content = text.trimStart();
    const marker = /^RIKKA_UI_V1\r?\n/.exec(content);
    if (marker) return <IntelligentUiPart source={content.slice(marker[0].length)} done={!isAnimating} />;
    if (isAnimating && "RIKKA_UI_V1\r".startsWith(content)) return null;
  }
  return (
    <div data-part="text">
      <Markdown content={text} isAnimating={isAnimating} onClickCitation={onClickCitation} />
    </div>
  );
}
