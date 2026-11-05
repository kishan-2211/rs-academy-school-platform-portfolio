const en = {
  familyAccess: "R.S. Academy family access",
  title: "A private link to",
  titleEmphasis: "their school day.",
  intro: "Every invitation activates one school-verified learner relationship. It never grants general access to student records.",
  oneRelationship: "One invitation, one learner relationship",
  passwordProtected: "Password-protected account access",
  schoolAudited: "Revocable and fully audited by the school",
  verifying: "Verifying your secure invitation…",
  successAnimation: "Successful invitation acceptance",
  successTitle: "Access verified",
  successBody: "Your relationship is active. We are taking you to the secure sign-in page.",
  invitationBadge: "Verified school invitation",
  formLabel: "Accept school invitation",
  invitedEmail: "Invited email",
  expires: "Expires",
  existingInstruction: "Enter your current school-account password to add this learner relationship.",
  newInstruction: "Create a password for your new family portal account.",
  currentPassword: "Current password",
  createPassword: "Create password",
  passwordHelp: "Use at least 12 characters and avoid a password used elsewhere.",
  confirmPassword: "Confirm password",
  accepting: "Verifying…",
  acceptContinue: "Accept and continue",
  privacy: "Acceptance does not sign you in automatically. You will enter through the normal secure portal afterward.",
  unavailableTitle: "Invitation unavailable",
  contactSchool: "Contact the school",
  missing: "This invitation link is incomplete. Ask the school to create a new invitation.",
  unavailable: "This invitation is invalid, expired, or no longer available. Ask the school to review the invitation.",
  acceptUnavailable: "The invitation could not be accepted. Review its details or contact the school.",
  mismatch: "The passwords do not match.",
  contextChanged: "The guardian relationship or invitation changed. Ask the school to review it before trying again.",
  accountConflict: "The account could not be linked to this guardian. Ask the school to review the account relationship.",
  membershipUnavailable: "The school-account access needs review. Contact the school before accepting this invitation.",
} as const;

type MessageKey = keyof typeof en;
const hi: Record<MessageKey, string> = {
  familyAccess: "R.S. Academy परिवार की पहुँच",
  title: "बच्चे के स्कूल से जुड़ने का",
  titleEmphasis: "एक निजी लिंक।",
  intro: "हर आमंत्रण स्कूल द्वारा सत्यापित एक विद्यार्थी से आपका संबंध सक्रिय करता है। इससे दूसरे विद्यार्थियों के रिकॉर्ड की सामान्य पहुँच नहीं मिलती।",
  oneRelationship: "एक आमंत्रण, एक विद्यार्थी से संबंध",
  passwordProtected: "पासवर्ड से सुरक्षित खाते की पहुँच",
  schoolAudited: "स्कूल इसे रद्द कर सकता है और हर कार्रवाई का रिकॉर्ड रखता है",
  verifying: "आपके सुरक्षित आमंत्रण की जाँच हो रही है…",
  successAnimation: "आमंत्रण सफलतापूर्वक स्वीकार किया गया",
  successTitle: "पहुँच सत्यापित हुई",
  successBody: "विद्यार्थी से आपका संबंध सक्रिय है। अब आपको सुरक्षित लॉगिन पेज पर ले जा रहे हैं।",
  invitationBadge: "स्कूल का सत्यापित आमंत्रण",
  formLabel: "स्कूल का आमंत्रण स्वीकार करें",
  invitedEmail: "आमंत्रित ईमेल",
  expires: "वैधता समाप्त होने का समय",
  existingInstruction: "इस विद्यार्थी से संबंध जोड़ने के लिए अपने मौजूदा स्कूल खाते का पासवर्ड दर्ज करें।",
  newInstruction: "अपने नए परिवार पोर्टल खाते के लिए पासवर्ड बनाएँ।",
  currentPassword: "मौजूदा पासवर्ड",
  createPassword: "पासवर्ड बनाएँ",
  passwordHelp: "कम से कम 12 अक्षर या अंक रखें और किसी दूसरे खाते का पासवर्ड न दोहराएँ।",
  confirmPassword: "पासवर्ड की पुष्टि करें",
  accepting: "जाँच हो रही है…",
  acceptContinue: "स्वीकार करें और आगे बढ़ें",
  privacy: "स्वीकृति से आप अपने आप लॉगिन नहीं होंगे। इसके बाद सामान्य सुरक्षित पोर्टल से लॉगिन करना होगा।",
  unavailableTitle: "आमंत्रण उपलब्ध नहीं है",
  contactSchool: "स्कूल से संपर्क करें",
  missing: "यह आमंत्रण लिंक अधूरा है। स्कूल से नया आमंत्रण बनाने के लिए कहें।",
  unavailable: "यह आमंत्रण अमान्य है, इसकी वैधता समाप्त हो गई है या यह अब उपलब्ध नहीं है। स्कूल से आमंत्रण की जाँच करवाएँ।",
  acceptUnavailable: "आमंत्रण स्वीकार नहीं हो सका। जानकारी जाँचें या स्कूल से संपर्क करें।",
  mismatch: "दोनों पासवर्ड मेल नहीं खाते।",
  contextChanged: "अभिभावक का संबंध या आमंत्रण बदल गया है। फिर प्रयास करने से पहले स्कूल से इसकी जाँच करवाएँ।",
  accountConflict: "खाते को इस अभिभावक से नहीं जोड़ा जा सका। स्कूल से खाते और संबंध की जाँच करवाएँ।",
  membershipUnavailable: "स्कूल खाते की पहुँच की समीक्षा चाहिए। आमंत्रण स्वीकार करने से पहले स्कूल से संपर्क करें।",
};

export type GuardianInvitationMessageKey = MessageKey;
export type GuardianInvitationErrorKey = "missing" | "unavailable" | "acceptUnavailable" | "mismatch" | "contextChanged" | "accountConflict" | "membershipUnavailable";

export function guardianInvitationMessage(locale: string, key: MessageKey): string {
  return locale === "hi" ? hi[key] : en[key];
}

// Display only known public problem codes. Server details can contain private or
// untranslated values and are not a user-interface catalogue.
export function guardianInvitationProblemKey(code: unknown, fallback: GuardianInvitationErrorKey): GuardianInvitationErrorKey {
  switch (code) {
    case "GUARDIAN_INVITATION_UNAVAILABLE":
    case "GUARDIAN_INVITATION_ACCEPTANCE_DENIED": return "unavailable";
    case "GUARDIAN_INVITATION_CONTEXT_CHANGED":
    case "STALE_VERSION": return "contextChanged";
    case "GUARDIAN_ACCOUNT_LINK_CONFLICT": return "accountConflict";
    case "GUARDIAN_MEMBERSHIP_UNAVAILABLE": return "membershipUnavailable";
    default: return fallback;
  }
}
