"use client";

import type { Route } from "next";
import Link from "next/link";
import { useEffect, useMemo, useState } from "react";
import { ArrowUpRight, Check } from "@/components/icons";
import {
  AcademicIcon,
  AttendanceIcon,
  MessageIcon,
  MoneyIcon,
  PeopleIcon,
  ReportIcon,
  SettingsIcon,
} from "@/components/workspace/workspace-icons";

type Language = "en" | "hi";
type ContextStatus = "loading" | "ready" | "unavailable";
type Copy = { en: string; hi: string };
type Guide = {
  code: string;
  title: Copy;
  description: Copy;
  steps: Copy[];
  href: string;
  requires: string[];
  icon: typeof SettingsIcon;
};

const apiUrl = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";
const copy = (en: string, hi: string): Copy => ({ en, hi });

const guides: Guide[] = [
  {
    code: "security",
    title: copy("Protect your account", "अपने खाते को सुरक्षित रखें"),
    description: copy(
      "Review MFA, recovery codes, and signed-in devices before handling school records.",
      "विद्यालय रिकॉर्ड संभालने से पहले MFA, रिकवरी कोड और साइन-इन डिवाइस की जाँच करें।",
    ),
    steps: [
      copy("Open Account security from the workspace.", "वर्कस्पेस से खाता सुरक्षा खोलें।"),
      copy(
        "Add an authenticator and store recovery codes privately.",
        "ऑथेंटिकेटर जोड़ें और रिकवरी कोड सुरक्षित रखें।",
      ),
      copy(
        "Sign out devices you do not recognize.",
        "जिन डिवाइस को आप नहीं पहचानते, उन्हें साइन आउट करें।",
      ),
    ],
    href: "/workspace/security",
    requires: [],
    icon: SettingsIcon,
  },
  {
    code: "admissions",
    title: copy("Move an applicant to enrolment", "आवेदक को नामांकन तक ले जाएँ"),
    description: copy(
      "Use the enquiry and application pipeline without entering the same family twice.",
      "एक ही परिवार की जानकारी दोबारा दर्ज किए बिना पूछताछ और आवेदन प्रक्रिया पूरी करें।",
    ),
    steps: [
      copy(
        "Check for an existing enquiry or application first.",
        "पहले मौजूदा पूछताछ या आवेदन खोजें।",
      ),
      copy(
        "Complete review and record the authorized decision.",
        "समीक्षा पूरी करके अधिकृत निर्णय दर्ज करें।",
      ),
      copy(
        "Create learner and guardian records only through enrolment handoff.",
        "विद्यार्थी और अभिभावक रिकॉर्ड केवल नामांकन हैंडऑफ से बनाएँ।",
      ),
    ],
    href: "/workspace/admissions",
    requires: ["admissions.*"],
    icon: PeopleIcon,
  },
  {
    code: "students",
    title: copy("Maintain learner and guardian records", "विद्यार्थी और अभिभावक रिकॉर्ड संभालें"),
    description: copy(
      "Keep enrolment, relationships, access, and section placement inside the correct school scope.",
      "नामांकन, संबंध, पहुँच और सेक्शन प्लेसमेंट सही विद्यालय सीमा में रखें।",
    ),
    steps: [
      copy(
        "Confirm the learner before editing identity details.",
        "पहचान विवरण बदलने से पहले विद्यार्थी की पुष्टि करें।",
      ),
      copy(
        "Record one explicit primary guardian where appropriate.",
        "जहाँ उचित हो, एक मुख्य अभिभावक स्पष्ट रूप से दर्ज करें।",
      ),
      copy(
        "Use effective-dated placement instead of rewriting history.",
        "इतिहास बदलने के बजाय प्रभावी तिथि वाला प्लेसमेंट उपयोग करें।",
      ),
    ],
    href: "/workspace/students",
    requires: ["student.*", "guardian.*"],
    icon: PeopleIcon,
  },
  {
    code: "teaching",
    title: copy("Open your teaching view", "अपना शिक्षण दृश्य खोलें"),
    description: copy(
      "Teachers see only their effective allocations and entries from published timetables.",
      "शिक्षक केवल अपनी प्रभावी जिम्मेदारियाँ और प्रकाशित समय-सारणी की प्रविष्टियाँ देखते हैं।",
    ),
    steps: [
      copy(
        "Open My teaching to confirm assigned classes and subjects.",
        "कक्षाएँ और विषय देखने के लिए मेरा शिक्षण खोलें।",
      ),
      copy(
        "Report a missing allocation to authorized academic leadership.",
        "गायब जिम्मेदारी की सूचना अधिकृत शैक्षणिक नेतृत्व को दें।",
      ),
      copy(
        "Never use another teacher's account or membership.",
        "किसी अन्य शिक्षक का खाता या सदस्यता कभी उपयोग न करें।",
      ),
    ],
    href: "/workspace/my-academics",
    requires: ["academics.read"],
    icon: AcademicIcon,
  },
  {
    code: "academics",
    title: copy("Configure academic structure", "शैक्षणिक संरचना कॉन्फ़िगर करें"),
    description: copy(
      "Manage sessions, sections, subjects, books, syllabus, allocations, and published timetables as configuration.",
      "सत्र, सेक्शन, विषय, पुस्तकें, पाठ्यक्रम, जिम्मेदारियाँ और प्रकाशित समय-सारणी कॉन्फ़िगरेशन के रूप में संभालें।",
    ),
    steps: [
      copy(
        "Configure the session, campus, section, and subject first.",
        "पहले सत्र, परिसर, सेक्शन और विषय कॉन्फ़िगर करें।",
      ),
      copy(
        "Assign only employment-eligible teachers.",
        "केवल रोजगार-पात्र शिक्षक को जिम्मेदारी दें।",
      ),
      copy(
        "Validate a timetable draft before publishing it.",
        "समय-सारणी ड्राफ्ट प्रकाशित करने से पहले जाँचें।",
      ),
    ],
    href: "/workspace/academics",
    requires: ["academics.configure"],
    icon: AcademicIcon,
  },
  {
    code: "attendance",
    title: copy("Complete an attendance register", "उपस्थिति रजिस्टर पूरा करें"),
    description: copy(
      "Use the school policy, submit complete evidence, and correct locked work through review.",
      "विद्यालय नीति का उपयोग करें, पूरा प्रमाण जमा करें और लॉक रिकॉर्ड में समीक्षा से सुधार करें।",
    ),
    steps: [
      copy(
        "Choose the correct date, section, and register mode.",
        "सही तिथि, सेक्शन और रजिस्टर प्रकार चुनें।",
      ),
      copy(
        "Record every expected learner before submission.",
        "जमा करने से पहले प्रत्येक अपेक्षित विद्यार्थी की उपस्थिति दर्ज करें।",
      ),
      copy(
        "Use a correction request after lock; do not rewrite evidence.",
        "लॉक के बाद सुधार अनुरोध करें; प्रमाण न बदलें।",
      ),
    ],
    href: "/workspace/attendance",
    requires: ["attendance.*"],
    icon: AttendanceIcon,
  },
  {
    code: "learning",
    title: copy("Publish and review assignments", "असाइनमेंट प्रकाशित और जाँचें"),
    description: copy(
      "Publish to a frozen class audience and keep revisions, responses, and feedback traceable.",
      "निश्चित कक्षा समूह के लिए प्रकाशित करें और संशोधन, उत्तर तथा प्रतिक्रिया का इतिहास रखें।",
    ),
    steps: [
      copy(
        "Choose an assigned class and school-managed category.",
        "अपनी कक्षा और विद्यालय-प्रबंधित श्रेणी चुनें।",
      ),
      copy(
        "Review due date, language, links, and private attachments.",
        "अंतिम तिथि, भाषा, लिंक और निजी संलग्नक जाँचें।",
      ),
      copy(
        "Revise published work through a new version.",
        "प्रकाशित कार्य में नया संस्करण बनाकर संशोधन करें।",
      ),
    ],
    href: "/workspace/learning",
    requires: ["learning.*"],
    icon: AcademicIcon,
  },
  {
    code: "assessment",
    title: copy(
      "Record and publish results safely",
      "परिणाम सुरक्षित रूप से दर्ज और प्रकाशित करें",
    ),
    description: copy(
      "Keep marks entry, validation, publication, and correction as separate accountable stages.",
      "अंक प्रविष्टि, सत्यापन, प्रकाशन और सुधार को अलग जवाबदेह चरणों में रखें।",
    ),
    steps: [
      copy(
        "Use the configured scheme and assigned assessment only.",
        "केवल कॉन्फ़िगर योजना और निर्धारित मूल्यांकन उपयोग करें।",
      ),
      copy(
        "Complete the register before independent validation.",
        "स्वतंत्र सत्यापन से पहले रजिस्टर पूरा करें।",
      ),
      copy(
        "Correct issued evidence through the controlled correction workflow.",
        "जारी प्रमाण में नियंत्रित सुधार प्रक्रिया से बदलाव करें।",
      ),
    ],
    href: "/workspace/assessment",
    requires: ["assessment.*"],
    icon: ReportIcon,
  },
  {
    code: "fees",
    title: copy(
      "Handle and close fees without rewriting money",
      "वित्तीय रिकॉर्ड बदले बिना फीस संभालें और बंद करें",
    ),
    description: copy(
      "Use plans, charges, receipts, ageing, reversals, and independently approved day closes as accountable evidence.",
      "योजना, शुल्क, रसीद, बकाया अवधि, रिवर्सल और स्वतंत्र रूप से स्वीकृत दैनिक बंद को जवाबदेह प्रमाण की तरह उपयोग करें।",
    ),
    steps: [
      copy(
        "Confirm the learner fee account and currency.",
        "विद्यार्थी फीस खाता और मुद्रा की पुष्टि करें।",
      ),
      copy(
        "Use a unique receipt and payment reference.",
        "अद्वितीय रसीद और भुगतान संदर्भ उपयोग करें।",
      ),
      copy(
        "Reverse an incorrect entry; never delete financial history.",
        "गलत प्रविष्टि को रिवर्स करें; वित्तीय इतिहास कभी न मिटाएँ।",
      ),
      copy(
        "Declare each payment-method total, explain any variance, and send the daily close to a different authority for approval.",
        "हर भुगतान माध्यम का कुल घोषित करें, किसी अंतर का कारण लिखें और दैनिक बंद को स्वीकृति के लिए अलग अधिकारी को भेजें।",
      ),
    ],
    href: "/workspace/fees",
    requires: ["fees.*"],
    icon: MoneyIcon,
  },
  {
    code: "communication",
    title: copy("Send a bilingual school notice", "द्विभाषी विद्यालय सूचना भेजें"),
    description: copy(
      "Choose a safe audience, verify both languages, and use approval for accountable delivery.",
      "सुरक्षित समूह चुनें, दोनों भाषाएँ जाँचें और जवाबदेह वितरण के लिए अनुमोदन उपयोग करें।",
    ),
    steps: [
      copy("Choose the smallest correct audience.", "सबसे छोटा सही प्राप्तकर्ता समूह चुनें।"),
      copy(
        "Verify Hindi and English meaning before submission.",
        "जमा करने से पहले हिंदी और अंग्रेज़ी का अर्थ जाँचें।",
      ),
      copy(
        "Review failed delivery evidence without exposing contact data.",
        "संपर्क डेटा दिखाए बिना विफल वितरण प्रमाण की समीक्षा करें।",
      ),
    ],
    href: "/workspace/communication",
    requires: ["communication.*"],
    icon: MessageIcon,
  },
  {
    code: "transport",
    title: copy("Run a safe transport day", "सुरक्षित परिवहन दिवस चलाएँ"),
    description: copy(
      "Use published routes, eligible staff, ready vehicles, frozen rosters, and append-only trip events.",
      "प्रकाशित मार्ग, पात्र कर्मचारी, तैयार वाहन, निश्चित सूची और अपरिवर्तनीय यात्रा घटनाएँ उपयोग करें।",
    ),
    steps: [
      copy(
        "Confirm route revision, duty, and vehicle readiness.",
        "मार्ग संस्करण, ड्यूटी और वाहन तैयारी की पुष्टि करें।",
      ),
      copy(
        "Freeze the service-day roster before operation.",
        "संचालन से पहले उस दिन की यात्री सूची निश्चित करें।",
      ),
      copy(
        "Record incidents in the restricted workflow immediately.",
        "घटना को तुरंत प्रतिबंधित प्रक्रिया में दर्ज करें।",
      ),
    ],
    href: "/workspace/transport",
    requires: ["transport.*"],
    icon: AttendanceIcon,
  },
  {
    code: "staff",
    title: copy("Maintain employment-backed staff access", "रोजगार-आधारित स्टाफ पहुँच संभालें"),
    description: copy(
      "Keep employment, assignment, portal account, evidence, and public profile as separate records.",
      "रोजगार, जिम्मेदारी, पोर्टल खाता, प्रमाण और सार्वजनिक प्रोफ़ाइल को अलग रिकॉर्ड रखें।",
    ),
    steps: [
      copy(
        "Create the employment profile before assignments.",
        "जिम्मेदारी से पहले रोजगार प्रोफ़ाइल बनाएँ।",
      ),
      copy(
        "Link a portal account only when access is required.",
        "पोर्टल खाता केवल आवश्यकता होने पर जोड़ें।",
      ),
      copy(
        "Offboard safely so assignment and access history remains.",
        "सुरक्षित ऑफबोर्डिंग करें ताकि जिम्मेदारी और पहुँच इतिहास रहे।",
      ),
    ],
    href: "/workspace/staff",
    requires: ["people.*"],
    icon: PeopleIcon,
  },
  {
    code: "documents",
    title: copy("Issue a verifiable school document", "सत्यापन योग्य विद्यालय दस्तावेज़ जारी करें"),
    description: copy(
      "Build from current report-card or payment evidence, use a published bilingual template, and preserve every correction.",
      "वर्तमान रिपोर्ट कार्ड या भुगतान प्रमाण से बनाएँ, प्रकाशित द्विभाषी टेम्पलेट उपयोग करें और हर सुधार सुरक्षित रखें।",
    ),
    steps: [
      copy(
        "Choose the authoritative source and matching template.",
        "अधिकृत स्रोत और उससे मेल खाता टेम्पलेट चुनें।",
      ),
      copy(
        "Use a different authorized person for protected approval.",
        "संरक्षित अनुमोदन के लिए किसी दूसरे अधिकृत व्यक्ति का उपयोग करें।",
      ),
      copy(
        "Cancel or supersede mistakes—never delete the evidence.",
        "गलती को रद्द या प्रतिस्थापित करें—प्रमाण कभी न हटाएँ।",
      ),
    ],
    href: "/workspace/documents",
    requires: ["documents.*"],
    icon: ReportIcon,
  },
  {
    code: "reports",
    title: copy("Read operational reports correctly", "संचालन रिपोर्ट सही तरह पढ़ें"),
    description: copy(
      "Use live aggregates and their definitions; open quality exceptions in the source workflow.",
      "लाइव कुल और उनकी परिभाषाएँ देखें; गुणवत्ता अपवाद मूल प्रक्रिया में खोलें।",
    ),
    steps: [
      copy("Check the school and generated timestamp.", "विद्यालय और रिपोर्ट बनने का समय जाँचें।"),
      copy("Open Definition before interpreting a number.", "संख्या समझने से पहले परिभाषा खोलें।"),
      copy(
        "Treat zero exceptions as a limited check, not certification.",
        "शून्य अपवाद को सीमित जाँच मानें, प्रमाणन नहीं।",
      ),
    ],
    href: "/workspace/reports",
    requires: ["reports.read"],
    icon: ReportIcon,
  },
  {
    code: "setup",
    title: copy(
      "Verify school configuration and launch",
      "विद्यालय कॉन्फ़िगरेशन और लॉन्च सत्यापित करें",
    ),
    description: copy(
      "Keep school facts and campuses editable, then complete safe launch evidence through a separate authority review.",
      "विद्यालय तथ्य और परिसर संपादन योग्य रखें, फिर अलग अधिकृत समीक्षा से सुरक्षित लॉन्च प्रमाण पूरा करें।",
    ),
    steps: [
      copy(
        "Confirm each public fact with school leadership.",
        "हर सार्वजनिक तथ्य की विद्यालय नेतृत्व से पुष्टि करें।",
      ),
      copy(
        "Record a reason and accuracy confirmation for each save.",
        "हर सेव के लिए कारण और सटीकता पुष्टि दर्ज करें।",
      ),
      copy("Keep at least one campus active.", "कम से कम एक परिसर सक्रिय रखें।"),
      copy(
        "Mark a launch control Ready only with a safe reference—never paste a password or private record.",
        "लॉन्च नियंत्रण को केवल सुरक्षित संदर्भ के साथ Ready करें—पासवर्ड या निजी रिकॉर्ड कभी न चिपकाएँ।",
      ),
      copy(
        "Use a different authorized person to decide the frozen launch review.",
        "स्थिर लॉन्च समीक्षा का निर्णय किसी दूसरे अधिकृत व्यक्ति से कराएँ।",
      ),
    ],
    href: "/workspace/setup",
    requires: ["school.configure", "platform.configure"],
    icon: SettingsIcon,
  },
];

function grants(capabilities: string[], requirements: string[]) {
  if (!requirements.length) return true;
  return requirements.some((requirement) =>
    requirement.endsWith(".*")
      ? capabilities.some((capability) => capability.startsWith(requirement.slice(0, -1)))
      : capabilities.includes(requirement),
  );
}

export function HelpWorkspace() {
  const [language, setLanguage] = useState<Language>("en");
  const [query, setQuery] = useState("");
  const [capabilities, setCapabilities] = useState<string[]>([]);
  const [contextStatus, setContextStatus] = useState<ContextStatus>("loading");

  useEffect(() => {
    const load = async () => {
      try {
        const response = await fetch(`${apiUrl}/api/v1/context`, { credentials: "include" });
        if (!response.ok) {
          setContextStatus("unavailable");
          return;
        }
        const context = (await response.json()) as { capabilities: string[] };
        if (!Array.isArray(context.capabilities)) {
          setContextStatus("unavailable");
          return;
        }
        setCapabilities(context.capabilities);
        setContextStatus("ready");
      } catch {
        setContextStatus("unavailable");
      }
    };
    void Promise.resolve().then(load);
  }, []);

  const visibleGuides = useMemo(() => {
    const normalizedQuery = query.trim().toLocaleLowerCase(language === "hi" ? "hi-IN" : "en-IN");
    return guides.filter((guide) => {
      if (!grants(capabilities, guide.requires)) return false;
      if (!normalizedQuery) return true;
      const searchable = [
        guide.title[language],
        guide.description[language],
        ...guide.steps.map((step) => step[language]),
      ]
        .join(" ")
        .toLocaleLowerCase(language === "hi" ? "hi-IN" : "en-IN");
      return searchable.includes(normalizedQuery);
    });
  }, [capabilities, language, query]);

  const t = (value: Copy) => value[language];

  return (
    <section className="help-workspace">
      <header className="help-hero">
        <div>
          <span>{language === "en" ? "Help inside the work" : "काम के भीतर सहायता"}</span>
          <h1>
            {language === "en" ? (
              <>
                Guidance for the <em>next safe action.</em>
              </>
            ) : (
              <>
                अगले <em>सुरक्षित कदम</em> के लिए मार्गदर्शन।
              </>
            )}
          </h1>
          <p>
            {language === "en"
              ? "Search short, role-aware guides for the workflows this signed-in account can use. Guidance explains scope, history, and safety boundaries without exposing school records."
              : "इस साइन-इन खाते के उपलब्ध कार्यों के लिए छोटे, भूमिका-आधारित मार्गदर्शक खोजें। मार्गदर्शन विद्यालय रिकॉर्ड दिखाए बिना सीमा, इतिहास और सुरक्षा समझाता है।"}
          </p>
        </div>
        <aside>
          <MessageIcon />
          <span>{language === "en" ? "Language" : "भाषा"}</span>
          <div role="group" aria-label="Help language">
            <button
              type="button"
              aria-pressed={language === "en"}
              onClick={() => setLanguage("en")}
            >
              English
            </button>
            <button
              type="button"
              aria-pressed={language === "hi"}
              onClick={() => setLanguage("hi")}
            >
              हिंदी
            </button>
          </div>
          <small>
            {language === "en"
              ? "The application data language remains unchanged."
              : "एप्लिकेशन डेटा की भाषा नहीं बदलती।"}
          </small>
        </aside>
      </header>

      <section className="help-search">
        <div>
          <label htmlFor="help-query">
            {language === "en" ? "What do you need to do?" : "आपको क्या करना है?"}
          </label>
          <input
            id="help-query"
            type="search"
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            placeholder={
              language === "en"
                ? "Search attendance, fees, security…"
                : "उपस्थिति, फीस, सुरक्षा खोजें…"
            }
          />
        </div>
        <span aria-live="polite">
          {contextStatus === "loading" ? (
            language === "en" ? (
              "Checking access…"
            ) : (
              "पहुँच जाँची जा रही है…"
            )
          ) : contextStatus === "unavailable" ? (
            language === "en" ? (
              "Role guides unavailable"
            ) : (
              "भूमिका मार्गदर्शक उपलब्ध नहीं"
            )
          ) : (
            <>
              <strong>{visibleGuides.length}</strong>
              {language === "en" ? " guides available" : " मार्गदर्शक उपलब्ध"}
            </>
          )}
        </span>
      </section>

      {visibleGuides.length ? (
        <div className="help-grid">
          {visibleGuides.map((guide) => {
            const Icon = guide.icon;
            return (
              <article key={guide.code}>
                <header>
                  <span>
                    <Icon />
                  </span>
                  <div>
                    <small>{language === "en" ? "Authorized workflow" : "अधिकृत कार्य"}</small>
                    <h2>{t(guide.title)}</h2>
                  </div>
                </header>
                <p>{t(guide.description)}</p>
                <ol>
                  {guide.steps.map((step, index) => (
                    <li key={step.en}>
                      <span>{index + 1}</span>
                      <p>{t(step)}</p>
                    </li>
                  ))}
                </ol>
                <Link href={guide.href as Route}>
                  {language === "en" ? "Open workflow" : "कार्य खोलें"}
                  <ArrowUpRight />
                </Link>
              </article>
            );
          })}
        </div>
      ) : (
        <div className="help-empty">
          <MessageIcon />
          <h2>
            {language === "en"
              ? "No guide matches that search"
              : "इस खोज से कोई मार्गदर्शक नहीं मिला"}
          </h2>
          <p>
            {language === "en"
              ? "Try a workflow name such as attendance, fees, security, or reports."
              : "उपस्थिति, फीस, सुरक्षा या रिपोर्ट जैसे कार्य नाम खोजें।"}
          </p>
          <button type="button" onClick={() => setQuery("")}>
            {language === "en" ? "Clear search" : "खोज साफ़ करें"}
          </button>
        </div>
      )}

      <footer className="help-boundary">
        <Check />
        <div>
          <strong>{language === "en" ? "Privacy-safe help" : "गोपनीयता-सुरक्षित सहायता"}</strong>
          <p>
            {language === "en"
              ? "This page uses capability names only to choose relevant guides. It does not load learner, guardian, staff, payment, health, safeguarding, or credential data."
              : "यह पृष्ठ केवल उपयुक्त मार्गदर्शक चुनने के लिए क्षमता नाम उपयोग करता है। यह विद्यार्थी, अभिभावक, कर्मचारी, भुगतान, स्वास्थ्य, सुरक्षा या क्रेडेंशियल डेटा लोड नहीं करता।"}
          </p>
        </div>
      </footer>
    </section>
  );
}
