import Link from "next/link";
import { ArrowUpRight } from "@/components/icons";
import { Brand } from "@/components/brand";
import { site } from "@/content/site";
import { Text } from "@/components/i18n/language-provider";

const footerGroups = [
  {
    label: "Discover",
    items: [
      ["Our school", "/about"],
      ["Learning", "/academics"],
      ["Campus life", "/campus"],
      ["Stories", "/news"],
    ],
  },
  {
    label: "Join us",
    items: [
      ["Admissions", "/admissions"],
      ["Apply online", "/admissions/apply"],
      ["Book a visit", "/admissions/visit"],
      ["Careers", "/careers"],
    ],
  },
  {
    label: "Connect",
    items: [
      ["Parent & student portal", "/portal"],
      ["Contact", "/contact"],
      ["Notices", "/news?type=notice"],
      ["Downloads", "/downloads"],
    ],
  },
] as const;

export function SiteFooter() {
  return (
    <footer className="site-footer">
      <div className="shell site-footer__top">
        <div className="site-footer__intro">
          <Brand />
          <p><Text domain="public">{site.description}</Text></p>
          <Link className="footer-contact" href="/contact">
            <Text domain="navigation">Start a conversation</Text> <ArrowUpRight />
          </Link>
        </div>
        <div className="site-footer__links">
          {footerGroups.map((group) => (
            <div key={group.label}>
              <h2><Text domain="navigation">{group.label}</Text></h2>
              {group.items.map(([label, href]) => (
                <Link key={href} href={href}><Text domain="navigation">{label}</Text></Link>
              ))}
            </div>
          ))}
        </div>
      </div>
      <div className="shell site-footer__bottom">
        <span>© {new Date().getFullYear()} {site.schoolName}</span>
        <div>
          <Link href="/privacy"><Text domain="navigation">Privacy</Text></Link>
          <Link href="/accessibility"><Text domain="navigation">Accessibility</Text></Link>
          <Link href="/credits"><Text domain="navigation">Creative credits</Text></Link>
          <Link href="/terms"><Text domain="navigation">Terms</Text></Link>
        </div>
        <span><Text domain="navigation">Powered by</Text> {site.platformName}</span>
      </div>
    </footer>
  );
}
