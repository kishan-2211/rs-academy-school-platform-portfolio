export const site = {
  platformName: "EduCore",
  schoolName: "R.S. Academy",
  legalName: "R.S. Academy",
  shortName: "RSA",
  motto: "Knowledge is Strength",
  description:
    "R.S. Academy is a CBSE school serving Nursery through Class X in Suaari, Danganj, Varanasi, with learning in Hindi and English.",
  email: "thersacademysuwari@gmail.com",
  phone: "+91 98380 02012",
  phoneHref: "+919838002012",
  principal: "Brijesh Kumar Singh",
  address: "Suaari, Danganj, Varanasi, Uttar Pradesh, India",
  locality: "Suaari · Danganj · Varanasi",
  board: "CBSE",
  gradeRange: "Nursery–Class X",
  languages: ["Hindi", "English"],
  logo: "/brand/rs-academy-logo.jpeg",
  admissionsLabel: "Admissions enquiries 2026–27",
  nav: [
    { href: "/about", label: "Our school" },
    { href: "/academics", label: "Learning" },
    { href: "/campus", label: "Campus life" },
    { href: "/admissions", label: "Admissions" },
    { href: "/fees", label: "Fees" },
    { href: "/news", label: "Stories" },
  ],
} as const;

export type SiteConfig = typeof site;
