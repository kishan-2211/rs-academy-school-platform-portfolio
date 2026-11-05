import type { MetadataRoute } from "next";
import { site } from "@/content/site";

export default function manifest(): MetadataRoute.Manifest {
  return {
    name: `${site.schoolName} School Platform`,
    short_name: site.schoolName,
    description: "Secure school updates, calendars, attendance, learning and family services.",
    start_url: "/portal",
    scope: "/",
    display: "standalone",
    orientation: "any",
    background_color: "#f7f3ea",
    theme_color: "#f7f3ea",
    categories: ["education", "productivity"],
    icons: [
      {
        src: site.logo,
        sizes: "768x699",
        type: "image/jpeg",
        purpose: "any",
      },
    ],
  };
}
