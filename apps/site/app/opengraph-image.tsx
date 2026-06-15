import { ImageResponse } from "next/og";

export const alt = "microhone — your phone is your PC's mic";
export const size = { width: 1200, height: 630 };
export const contentType = "image/png";

export default function OpengraphImage() {
  return new ImageResponse(
    (
      <div
        style={{
          width: "100%",
          height: "100%",
          display: "flex",
          flexDirection: "column",
          alignItems: "center",
          justifyContent: "center",
          backgroundColor: "#ffffff",
          backgroundImage:
            "radial-gradient(circle at 50% 0%, #dbeafe, #ffffff 62%)",
          color: "#0b1220",
          fontFamily: "sans-serif",
        }}
      >
        <div
          style={{
            display: "flex",
            alignItems: "center",
            gap: 22,
            marginBottom: 44,
          }}
        >
          <div
            style={{
              width: 92,
              height: 92,
              borderRadius: 24,
              backgroundImage: "linear-gradient(135deg, #3B82F6, #2563EB)",
              display: "flex",
              alignItems: "center",
              justifyContent: "center",
            }}
          >
            <div
              style={{ width: 28, height: 46, borderRadius: 14, backgroundColor: "#fff" }}
            />
          </div>
          <div style={{ fontSize: 58, fontWeight: 700, letterSpacing: -1 }}>
            microhone
          </div>
        </div>

        <div
          style={{
            display: "flex",
            fontSize: 76,
            fontWeight: 800,
            letterSpacing: -2,
          }}
        >
          {"Your phone is your PC's "}
          <span style={{ color: "#2563EB", marginLeft: 18 }}>mic.</span>
        </div>

        <div style={{ fontSize: 32, color: "#64748b", marginTop: 30 }}>
          {"Free · WiFi or USB · works with any app"}
        </div>
      </div>
    ),
    { ...size },
  );
}
