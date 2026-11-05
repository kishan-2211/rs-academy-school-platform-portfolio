import type { Locale } from "./locale";

// Only controlled product codes are translated. Unknown/custom codes keep the
// caller's existing formatting; class names, IDs and author text do not pass
// through a general translation lookup.
const codes: Record<string, Record<Locale, string>> = {
  PRESENT: { en: "Present", hi: "उपस्थित" }, ABSENT: { en: "Absent", hi: "अनुपस्थित" },
  LATE: { en: "Late", hi: "देर से आए" }, EXCUSED: { en: "Excused", hi: "मंज़ूर अनुपस्थिति" },
  HALF_DAY: { en: "Half Day", hi: "आधा दिन" }, SCHOOL_ACTIVITY: { en: "School Activity", hi: "स्कूल की गतिविधि" },
  LEAVE: { en: "Leave", hi: "छुट्टी" }, OFFICIAL_DUTY: { en: "Official Duty", hi: "आधिकारिक काम" },
  UNRECORDED: { en: "Unrecorded", hi: "दर्ज नहीं" }, DAILY: { en: "Daily", hi: "दैनिक" },
  PERIOD: { en: "Period", hi: "पीरियड" }, BOTH: { en: "Both", hi: "दोनों" },
  DRAFT: { en: "Draft", hi: "मसौदा" }, SUBMITTED: { en: "Submitted", hi: "जमा किया गया" },
  APPROVED: { en: "Approved", hi: "मंज़ूर" }, REJECTED: { en: "Rejected", hi: "नामंज़ूर" },
  PENDING: { en: "Pending", hi: "लंबित" }, REQUESTED: { en: "Requested", hi: "अनुरोध किया गया" },
  WITHDRAWN: { en: "Withdrawn", hi: "वापस लिया गया" }, REVOKED: { en: "Revoked", hi: "मंज़ूरी वापस ली गई" },
  ACCEPTED: { en: "Accepted", hi: "स्वीकृत" }, DECLINED: { en: "Declined", hi: "अस्वीकृत" },
  ACTIVE: { en: "Active", hi: "सक्रिय" }, INACTIVE: { en: "Inactive", hi: "निष्क्रिय" },
  SUSPENDED: { en: "Suspended", hi: "अस्थायी रूप से रोका गया" }, ENDED: { en: "Ended", hi: "समाप्त" },
  EXPIRED: { en: "Expired", hi: "समय-सीमा खत्म" },
  LOCKED: { en: "Locked", hi: "लॉक किया गया" }, PUBLISHED: { en: "Published", hi: "प्रकाशित" },
  SCHEDULED: { en: "Scheduled", hi: "तय समय पर" }, CANCELLED: { en: "Cancelled", hi: "रद्द" },
  INSTRUCTIONAL: { en: "Instructional", hi: "पढ़ाई का दिन" }, EXAM: { en: "Exam", hi: "परीक्षा" },
  EVENT: { en: "Event", hi: "कार्यक्रम" }, HOLIDAY: { en: "Holiday", hi: "छुट्टी" },
  CLOSURE: { en: "Closure", hi: "स्कूल बंद" }, REOPENING: { en: "Reopening", hi: "स्कूल दोबारा खुलना" },
  NOTICE: { en: "Notice", hi: "सूचना" }, GENERAL: { en: "General", hi: "सामान्य" },
  NORMAL: { en: "Normal", hi: "सामान्य" }, HIGH: { en: "High", hi: "ज़रूरी" },
  URGENT: { en: "Urgent", hi: "तुरंत ध्यान दें" }, IN_APP: { en: "In App", hi: "ऐप में" },
  SMS: { en: "Sms", hi: "SMS" }, EMAIL: { en: "Email", hi: "ईमेल" },
  QUEUED: { en: "Queued", hi: "भेजने की कतार में" }, SENT: { en: "Sent", hi: "भेजा गया" },
  DELIVERED: { en: "Delivered", hi: "पहुँच गया" }, FAILED: { en: "Failed", hi: "असफल" },
  READ: { en: "Read", hi: "पढ़ा गया" }, ACKNOWLEDGED: { en: "Acknowledged", hi: "पुष्टि की गई" },
  NOT_APPLICABLE: { en: "Not Applicable", hi: "लागू नहीं" }, DISABLED: { en: "Disabled", hi: "बंद" },
};

export function translateCode<T extends string | null | undefined>(locale: Locale, value: T, fallback: (value: T) => string) {
  return value && Object.hasOwn(codes, value) ? codes[value]![locale] : fallback(value);
}
