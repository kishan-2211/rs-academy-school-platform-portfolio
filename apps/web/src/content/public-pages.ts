export type PublicPage = {
  eyebrow: string;
  title: string;
  accent: string;
  introduction: string;
  theme: "mint" | "sky" | "lavender" | "yellow" | "coral";
  sections: Array<{
    label: string;
    title: string;
    body: string;
  }>;
};

export const publicPages: Record<string, PublicPage> = {
  about: {
    eyebrow: "Our school",
    title: "A place to become",
    accent: "fully yourself.",
    introduction:
      "R.S. Academy serves families from Suaari, Danganj, and Varanasi through a single-campus CBSE pathway from Nursery to Class X, with Hindi and English woven through school life.",
    theme: "mint",
    sections: [
      {
        label: "Our purpose",
        title: "Curiosity with character",
        body: "Our motto, Knowledge is Strength, expresses a practical promise: build secure academic foundations, encourage disciplined effort, and help learners use knowledge with confidence and responsibility.",
      },
      {
        label: "Our culture",
        title: "Belonging comes first",
        body: "A community school works best when learners are known, routines are consistent, and expectations are clear. The new platform will help teachers and school leaders follow each learner without losing the human relationship.",
      },
      {
        label: "Our partnership",
        title: "Families are part of the story",
        body: "Clear notices, attendance, fees, learning updates, and direct contact will help families stay informed without depending on scattered paper records or forwarded messages.",
      },
    ],
  },
  academics: {
    eyebrow: "Learning",
    title: "Knowledge that opens",
    accent: "new doors.",
    introduction:
      "A CBSE learning journey from Nursery to Class X, building progressively through English, Hindi, mathematics, environmental studies, science, social science, computing, general knowledge, art, and Sanskrit where applicable.",
    theme: "sky",
    sections: [
      {
        label: "Foundational years",
        title: "Nursery to Class II",
        body: "Early language, number, environmental awareness, expression, movement, general knowledge, art, and age-appropriate computing form a confident start in Hindi and English.",
      },
      {
        label: "Middle years",
        title: "Classes III to VIII",
        body: "English, Hindi, mathematics, science or environmental studies, social science, computing, general knowledge, art, and Sanskrit grow in depth as learners become more independent.",
      },
      {
        label: "Senior years",
        title: "Classes IX and X",
        body: "Focused secondary study in the CBSE pathway prepares learners for board expectations and the transition to senior secondary education. Final subject and assessment rules remain governed by approved school and CBSE policy.",
      },
    ],
  },
  campus: {
    eyebrow: "Campus life",
    title: "Space to move, make,",
    accent: "and belong.",
    introduction:
      "R.S. Academy operates one campus in Suaari, Danganj. This section will grow with verified photographs, facilities, safety information, transport details, and visitor guidance approved by the school.",
    theme: "yellow",
    sections: [
      {
        label: "Learning spaces",
        title: "Built around the school day",
        body: "Classrooms and shared learning areas support the Nursery-to-Class-X timetable. Facility names and photographs will be published only after the school verifies them.",
      },
      {
        label: "Daily rhythm",
        title: "A clear, familiar routine",
        body: "The current reference schedule begins with prayer at 07:45, teaching at 08:00, a short interval at 10:30, and departure after the seventh period at 12:30.",
      },
      {
        label: "Community",
        title: "A campus shaped by people",
        body: "The campus serves a real local community of approximately 700–800 learners and around 30 teaching and operational staff. Published activities will be added as the school confirms them.",
      },
    ],
  },
  admissions: {
    eyebrow: "Admissions",
    title: "A clear path to your",
    accent: "next chapter.",
    introduction:
      "Families considering Nursery through Class X can contact R.S. Academy directly or use the enquiry journey. Admission availability, age rules, documents, and fees are confirmed by the school office before any commitment.",
    theme: "coral",
    sections: [
      {
        label: "01 · Discover",
        title: "Understand the school",
        body: "Explore the CBSE pathway, school location, class range, verified notices, and contact information before sharing personal details.",
      },
      {
        label: "02 · Meet",
        title: "Visit and talk with us",
        body: "Request a suitable visit or call Principal Brijesh Kumar Singh on +91 98380 02012 to confirm office availability.",
      },
      {
        label: "03 · Apply",
        title: "Complete one guided application",
        body: "The full application workflow will save progress, request only approved information, protect uploaded documents, and show each next step clearly. Until launch, an online submission is an enquiry—not an admission offer.",
      },
    ],
  },
  news: {
    eyebrow: "Stories & notices",
    title: "The school in",
    accent: "living color.",
    introduction:
      "Learning stories, community moments, important notices, and upcoming events—published with enough context to be genuinely useful.",
    theme: "lavender",
    sections: [
      {
        label: "Learning story",
        title: "When questions become prototypes",
        body: "A future story space for classroom inquiry, student reflection, photographs with approved consent, and the next questions a project inspired.",
      },
      {
        label: "Community",
        title: "A stage for many kinds of courage",
        body: "A future home for performances, exhibitions, sporting moments, service, clubs, celebrations, and voices from across the community.",
      },
      {
        label: "Notice",
        title: "Important information, clearly dated",
        body: "Notices will show audience, publish and expiry dates, attachments, translations, and a visible correction history when information changes.",
      },
    ],
  },
  careers: {
    eyebrow: "Careers",
    title: "Do work that helps people",
    accent: "grow.",
    introduction:
      "We are building a thoughtful, inclusive workplace for educators and school professionals who combine high expectations with humanity.",
    theme: "mint",
    sections: [
      {
        label: "Teach",
        title: "Craft matters here",
        body: "Educators deserve time, useful tools, strong professional learning, and colleagues who care about both subject expertise and the whole learner.",
      },
      {
        label: "Support",
        title: "Every role shapes the day",
        body: "Admissions, operations, finance, wellbeing, transport, technology, and campus teams make safe, joyful learning possible.",
      },
      {
        label: "Join",
        title: "A fair, transparent process",
        body: "Approved vacancies will include responsibilities, requirements, location, safeguarding checks, timeline, and a secure application route.",
      },
    ],
  },
  downloads: {
    eyebrow: "Downloads",
    title: "The right document,",
    accent: "without the hunt.",
    introduction:
      "An accessible, searchable library for approved public policies, calendars, forms, prospectuses, and mandatory disclosures.",
    theme: "sky",
    sections: [
      {
        label: "Families",
        title: "Calendars, guides, and forms",
        body: "Published files will include a plain-language description, audience, version, language, file type, size, and last updated date.",
      },
      {
        label: "Admissions",
        title: "Prospectus and required information",
        body: "Families can find current admission guidance and fee disclosure without depending on an outdated file forwarded through a messaging group.",
      },
      {
        label: "Transparency",
        title: "Policies and disclosures",
        body: "Mandatory public records can be organized by school year and category with superseded versions retained where policy requires it.",
      },
    ],
  },
};
