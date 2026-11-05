import { Calendar, Check, Clock, Message, Sparkles } from "@/components/icons";
import { Text } from "@/components/i18n/language-provider";

export function OrbitVisual() {
  return (
    <div className="orbit" role="group" aria-labelledby="homepage-platform-preview-caption">
      <span className="sr-only" id="homepage-platform-preview-caption"><Text domain="public">Illustrative platform preview — not live school data</Text></span>
      <div className="orbit__glow" />
      <div className="orbit__ring orbit__ring--one" />
      <div className="orbit__ring orbit__ring--two" />
      <div className="orbit__ring orbit__ring--three" />

      <div className="orbit__core">
        <span className="orbit__eyebrow"><Text domain="public">Platform preview</Text></span>
        <strong><Text domain="public">Everything</Text><br /><Text domain="public">in rhythm.</Text></strong>
        <div className="orbit__status">
          <span><span /> <Text domain="public">Example</Text></span>
          <small><Text domain="public">Not live school data</Text></small>
        </div>
      </div>

      <div className="orbit-card orbit-card--attendance">
        <span className="orbit-card__icon orbit-card__icon--mint"><Check /></span>
        <span><small><Text domain="public">Attendance</Text></small><strong><Text domain="public">Class registers</Text></strong></span>
        <em><Text domain="public">Scoped</Text></em>
      </div>

      <div className="orbit-card orbit-card--class">
        <span className="orbit-card__icon orbit-card__icon--yellow"><Clock /></span>
        <span><small><Text domain="public">Learning</Text></small><strong><Text domain="public">Class timetable</Text></strong></span>
        <em><Text domain="public">Plan</Text></em>
      </div>

      <div className="orbit-card orbit-card--event">
        <span className="orbit-card__icon orbit-card__icon--lavender"><Calendar /></span>
        <span><small><Text domain="public">Calendar</Text></small><strong><Text domain="public">School updates</Text></strong></span>
        <em><Text domain="public">Notice</Text></em>
      </div>

      <div className="orbit-card orbit-card--message">
        <span className="orbit-card__icon orbit-card__icon--blue"><Message /></span>
        <span><small><Text domain="public">From the class</Text></small><strong><Text domain="public">A new learning story</Text></strong></span>
      </div>

      <span className="orbit__spark orbit__spark--one"><Sparkles /></span>
      <span className="orbit__spark orbit__spark--two"><Sparkles /></span>
      <span className="orbit__dot orbit__dot--one" />
      <span className="orbit__dot orbit__dot--two" />
    </div>
  );
}
