import { useTranslation } from "react-i18next";

import { cn } from "~/lib/utils";

function TypingIndicator({ className, ...props }: React.ComponentProps<"div">) {
  const { t } = useTranslation();

  return (
    <div
      role="status"
      aria-label={t("a11y.typing")}
      className={cn("flex items-center gap-1", className)}
      {...props}
    >
      <span className="size-1.5 animate-bounce rounded-full bg-muted-foreground/60 [animation-delay:0ms]" />
      <span className="size-1.5 animate-bounce rounded-full bg-muted-foreground/60 [animation-delay:150ms]" />
      <span className="size-1.5 animate-bounce rounded-full bg-muted-foreground/60 [animation-delay:300ms]" />
    </div>
  );
}

export { TypingIndicator };
