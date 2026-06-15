import type { Metadata } from "next";
import { Inter } from "next/font/google";
import { Analytics } from "@vercel/analytics/next";
import "./globals.css";

const inter = Inter({
  variable: "--font-inter",
  subsets: ["latin"],
});

const title = "microhone — your phone is your PC's mic";
const description =
  "Speak into your phone and it comes out on your computer — in Discord, Zoom, OBS and any app. Over WiFi or USB. Free, no account.";

export const metadata: Metadata = {
  metadataBase: new URL("https://microhone.com"),
  title,
  description,
  keywords: [
    "microhone",
    "phone as microphone",
    "use phone as mic on PC",
    "virtual microphone",
    "Discord microphone",
    "OBS microphone",
  ],
  openGraph: {
    type: "website",
    url: "https://microhone.com",
    siteName: "microhone",
    title,
    description,
  },
  twitter: {
    card: "summary_large_image",
    title,
    description,
  },
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="en" className={`${inter.variable} h-full`}>
      <body className="flex min-h-full flex-col bg-white text-slate-900">
        {children}
        <Analytics />
      </body>
    </html>
  );
}
