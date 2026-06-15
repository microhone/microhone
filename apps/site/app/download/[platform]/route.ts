// Streams the latest release asset from the (public) releases repo through our
// own domain, so downloads start in-place on microhone.com without sending the
// user off to github.com.

export const runtime = "edge";

const RELEASES =
  "https://github.com/microhone/microhone/releases/latest/download";

const ASSETS: Record<string, { file: string; type: string }> = {
  windows: {
    file: "microhone-windows-setup.exe",
    type: "application/octet-stream",
  },
  android: {
    file: "microhone-android.apk",
    type: "application/vnd.android.package-archive",
  },
};

export async function GET(
  _request: Request,
  { params }: { params: Promise<{ platform: string }> },
) {
  const { platform } = await params;
  const asset = ASSETS[platform];
  if (!asset) {
    return new Response("Unknown platform", { status: 404 });
  }

  const upstream = await fetch(`${RELEASES}/${asset.file}`, {
    redirect: "follow",
  });
  if (!upstream.ok || !upstream.body) {
    return new Response("Download unavailable", { status: 502 });
  }

  return new Response(upstream.body, {
    headers: {
      "Content-Type": asset.type,
      "Content-Disposition": `attachment; filename="${asset.file}"`,
      "Cache-Control": "public, max-age=300",
    },
  });
}
