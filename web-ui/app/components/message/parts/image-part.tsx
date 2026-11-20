import * as React from "react";
import { ImageOff } from "lucide-react";
import { useTranslation } from "react-i18next";

import { resolveFileUrl } from "~/lib/files";

interface ImagePartProps {
  url: string;
}

export function ImagePart({ url }: ImagePartProps) {
  const { t } = useTranslation("message");
  const [error, setError] = React.useState(false);
  const [loaded, setLoaded] = React.useState(false);
  const imageUrl = resolveFileUrl(url);

  if (!url) return null;

  if (error) {
    return (
      <div className="flex items-center gap-2 rounded-md border border-destructive/50 bg-destructive/10 px-3 py-2 text-sm text-destructive">
        <ImageOff className="h-4 w-4" />
        <span>{t("media_part.image_load_failed", { url: imageUrl })}</span>
      </div>
    );
  }

  return (
    <div className="relative my-2 max-w-md">
      {!loaded && (
        <div className="flex h-48 items-center justify-center rounded-md border border-muted bg-muted/30">
          <div className="text-sm text-muted-foreground">{t("media_part.image_loading")}</div>
        </div>
      )}
      <img
        src={imageUrl}
        alt={t("media_part.image_alt")}
        className={`rounded-md border border-muted object-contain ${loaded ? "block" : "hidden"}`}
        onLoad={() => setLoaded(true)}
        onError={() => setError(true)}
        style={{ maxHeight: "500px", width: "auto" }}
      />
    </div>
  );
}
