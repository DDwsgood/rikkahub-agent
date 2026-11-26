import Markdown from "~/components/markdown/markdown";
import { RichContentPart } from "./rich-content-part";

interface TextPartProps {
  intelligentUi?: boolean;
  text: string;
  isAnimating?: boolean;
  onClickCitation?: (id: string) => void;
}

export function TextPart({ text, intelligentUi, isAnimating, onClickCitation }: TextPartProps) {
  if (!text) return null;
  if (intelligentUi) {
    return <RichContentPart text={text} isAnimating={isAnimating} onClickCitation={onClickCitation} />;
  }
  return (
    <div data-part="text">
      <Markdown content={text} isAnimating={isAnimating} onClickCitation={onClickCitation} />
    </div>
  );
}
