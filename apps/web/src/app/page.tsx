import Link from "next/link";
import {
  ArrowRight,
  ArrowUpRight,
  Calendar,
  Check,
  Compass,
  Heart,
  Play,
  Shield,
  Sparkles,
} from "@/components/icons";
import { OrbitVisual } from "@/components/orbit-visual";
import { Reveal } from "@/components/reveal";
import { Text } from "@/components/i18n/language-provider";

const pillars = [
  {
    number: "01",
    icon: <Compass />,
    title: "Foundations that last",
    body: "From Nursery to Class X, clear teaching and steady practice build language, mathematics, science, and confident thinking.",
    color: "mint",
  },
  {
    number: "02",
    icon: <Heart />,
    title: "Rooted in community",
    body: "A local school in Suaari where teachers and families can stay close to each learner's progress, attendance, and wellbeing.",
    color: "coral",
  },
  {
    number: "03",
    icon: <Sparkles />,
    title: "Ready for tomorrow",
    body: "Hindi and English learning, digital confidence, creativity, discipline, and character grow alongside academic achievement.",
    color: "lavender",
  },
] as const;

const journey = [
  ["07:45", "Prayer & readiness", "The community gathers before lessons begin."],
  ["08:00", "Strong first lessons", "Focused learning in language, mathematics, and core subjects."],
  ["10:30", "Pause & reconnect", "A short break brings movement, conversation, and renewed focus."],
  ["11:55", "Finish with purpose", "The final period carries learning through to the 12:30 close."],
] as const;

export default function HomePage() {
  return (
    <main id="main-content">
      <section className="hero section-dark">
        <div className="hero__grid" aria-hidden="true" />
        <div className="hero__aurora hero__aurora--one" aria-hidden="true" />
        <div className="hero__aurora hero__aurora--two" aria-hidden="true" />
        <div className="shell hero__inner">
          <div className="hero__copy">
            <p className="eyebrow eyebrow--light">
              <span /> <Text domain="public">CBSE · Nursery to Class X · Varanasi</Text>
            </p>
            <h1>
              <Text domain="public">Knowledge becomes</Text><br />
              <em><Text domain="public">strength.</Text></em>
            </h1>
            <p className="hero__lede">
              <Text domain="public">R.S. Academy brings strong foundations, caring guidance, and future-ready learning to families in Suaari, Danganj, and the wider Varanasi community.</Text>
            </p>
            <div className="hero__actions">
              <Link className="button button--coral" href="/admissions">
                <Text domain="public">Discover admissions</Text> <ArrowUpRight />
              </Link>
              <Link className="text-link text-link--light" href="/about">
                <span className="play"><Play /></span>
                <Text domain="public">See our school story</Text>
              </Link>
            </div>
            <div className="hero__proof">
              <span className="sr-only"><Text domain="public">School experience commitments</Text></span>
              <span><Check /> <Text domain="public">CBSE learning pathway</Text></span>
              <span><Check /> <Text domain="public">Hindi &amp; English</Text></span>
              <span><Check /> <Text domain="public">Nursery–Class X</Text></span>
            </div>
          </div>
          <div className="hero__visual">
            <OrbitVisual />
          </div>
        </div>
        <div className="shell hero__foot">
          <span><Text domain="public">Scroll to explore</Text></span>
          <span className="hero__line" />
          <span><Text domain="public">R.S. Academy · Suaari · Danganj</Text></span>
        </div>
      </section>

      <section className="intro section-paper">
        <div className="shell intro__grid">
          <Reveal>
            <p className="eyebrow"><span /> <Text domain="public">R.S. Academy, Suaari</Text></p>
          </Reveal>
          <Reveal delay={80} className="intro__statement">
            <h2>
              <Text domain="public">A strong education begins with</Text>{" "}<em><Text domain="public">trust</Text></em><Text domain="public">—and grows through knowledge, practice, character, and care.</Text>
            </h2>
            <p>
              <Text domain="public">Our digital platform is being built around the real rhythm of this one-campus school: simpler communication, visible progress, safer records, and less administrative friction.</Text>
            </p>
            <Link className="text-link" href="/about">
              <Text domain="public">How we learn together</Text> <ArrowRight />
            </Link>
          </Reveal>
        </div>
      </section>

      <section className="pillars section-paper">
        <div className="shell pillars__grid">
          {pillars.map((pillar, index) => (
            <Reveal key={pillar.title} delay={index * 90} className={`pillar pillar--${pillar.color}`}>
              <div className="pillar__top">
                <span className="pillar__number">{pillar.number}</span>
                <span className="pillar__icon">{pillar.icon}</span>
              </div>
              <h3><Text domain="public">{pillar.title}</Text></h3>
              <p><Text domain="public">{pillar.body}</Text></p>
              <Link href="/academics">
                <span className="sr-only"><Text domain="public">{`Learn more about ${pillar.title}`}</Text></span>
                <ArrowUpRight />
              </Link>
            </Reveal>
          ))}
        </div>
      </section>

      <section className="day section-ink">
        <div className="day__noise" aria-hidden="true" />
        <div className="shell day__heading">
          <Reveal>
            <p className="eyebrow eyebrow--light"><span /> <Text domain="public">Draft 2026–27 rhythm</Text></p>
            <h2><Text domain="public">Every moment has</Text><br /><em><Text domain="public">room to matter.</Text></em></h2>
          </Reveal>
          <Reveal delay={100}>
            <p>
              <Text domain="public">A supplied reference schedule begins with prayer at 07:45, follows seven teaching periods with a short interval, and closes at 12:30. The school office will confirm the final published timetable.</Text>
            </p>
            <Link className="text-link text-link--light" href="/campus">
              <Text domain="public">Explore campus life</Text> <ArrowRight />
            </Link>
          </Reveal>
        </div>
        <div className="shell day__timeline">
          {journey.map(([time, title, body], index) => (
            <Reveal key={time} delay={index * 70} className="day-stop">
              <div className="day-stop__marker"><span>{index + 1}</span></div>
              <time>{time}</time>
              <h3><Text domain="public">{title}</Text></h3>
              <p><Text domain="public">{body}</Text></p>
            </Reveal>
          ))}
        </div>
        <div className="day__marquee" aria-hidden="true">
          <div>
            <span><Text domain="public">Learn</Text></span><i>✦</i><span><Text domain="public">Make</Text></span><i>✦</i><span><Text domain="public">Move</Text></span><i>✦</i>
            <span><Text domain="public">Belong</Text></span><i>✦</i><span><Text domain="public">Imagine</Text></span><i>✦</i><span><Text domain="public">Learn</Text></span><i>✦</i>
          </div>
        </div>
      </section>

      <section className="connection section-paper">
        <div className="shell connection__grid">
          <Reveal className="connection__visual">
            <div className="portal-card">
              <div className="portal-card__head">
                <div>
                  <small><Text domain="public">Good afternoon</Text></small>
                  <strong><Text domain="public">Your family view</Text></strong>
                </div>
                <span className="portal-avatar">AK</span>
              </div>
              <div className="portal-child">
                <span className="portal-child__avatar">A</span>
                <div><strong><Text domain="public">Aarav&apos;s day</Text></strong><small><Text domain="public">Class VI · Demonstration profile</Text></small></div>
                <span className="status-pill"><span /> <Text domain="public">Present</Text></span>
              </div>
              <div className="portal-stats">
                <div><Calendar /><small><Text domain="public">Next event</Text></small><strong><Text domain="public">Science studio</Text></strong><span><Text domain="public">Friday, 10:30</Text></span></div>
                <div><Check /><small><Text domain="public">Today</Text></small><strong><Text domain="public">3 updates</Text></strong><span><Text domain="public">All caught up</Text></span></div>
              </div>
              <div className="portal-update">
                <span><Sparkles /></span>
                <div><small><Text domain="public">Learning story</Text></small><strong><Text domain="public">Building bridges: design, test, improve</Text></strong></div>
                <ArrowUpRight />
              </div>
            </div>
            <span className="connection__halo" aria-hidden="true" />
            <span className="connection__tag connection__tag--one"><Text domain="public">Clear</Text></span>
            <span className="connection__tag connection__tag--two"><Text domain="public">Connected</Text></span>
          </Reveal>
          <Reveal delay={120} className="connection__copy">
            <p className="eyebrow"><span /> <Text domain="public">One connected community</Text></p>
            <h2><Text domain="public">Families see the story,</Text><br /><em><Text domain="public">not just the score.</Text></em></h2>
            <p>
              <Text domain="public">The family portal brings attendance, learning, fees, events, messages, and documents into one calm, secure place—across every child.</Text>
            </p>
            <ul>
              <li><Shield /> <Text domain="public">Relationship-based privacy and access</Text></li>
              <li><Calendar /> <Text domain="public">One view of what matters today and next</Text></li>
              <li><Heart /> <Text domain="public">Feedback and communication with context</Text></li>
            </ul>
            <Link className="button button--dark" href="/portal">
              <Text domain="public">Preview the family experience</Text> <ArrowUpRight />
            </Link>
          </Reveal>
        </div>
      </section>

      <section className="learning section-sky">
        <div className="shell learning__heading">
          <Reveal>
            <p className="eyebrow"><span /> <Text domain="public">Learning pathways</Text></p>
            <h2><Text domain="public">A strong beginning.</Text><br /><em><Text domain="public">A widening world.</Text></em></h2>
          </Reveal>
          <Reveal delay={80}>
            <p>
              <Text domain="public">The Nursery-to-Class-X pathway builds confidence and independence while preserving curiosity, creativity, discipline, and the courage to try again.</Text>
            </p>
          </Reveal>
        </div>
        <div className="shell learning__cards">
          <Reveal className="learning-card learning-card--primary">
            <div className="learning-card__art learning-card__art--primary" aria-hidden="true">
              <span className="shape shape--sun" />
              <span className="shape shape--hill" />
              <span className="shape shape--book" />
              <span className="shape shape--pencil" />
            </div>
            <div className="learning-card__copy">
              <span><Text domain="public">Nursery–Class II</Text></span><h3><Text domain="public">Language, number, play, and confident beginnings.</Text></h3>
              <Link href="/academics#foundational"><span className="sr-only"><Text domain="public">Explore Nursery to Class II learning</Text></span><ArrowUpRight /></Link>
            </div>
          </Reveal>
          <Reveal delay={90} className="learning-card learning-card--middle">
            <div className="learning-card__art learning-card__art--middle" aria-hidden="true">
              <span className="shape shape--planet" />
              <span className="shape shape--orbit" />
              <span className="shape shape--moon" />
              <span className="shape shape--star">✦</span>
            </div>
            <div className="learning-card__copy">
              <span><Text domain="public">Classes III–VIII</Text></span><h3><Text domain="public">Ideas connect. Knowledge and independence grow.</Text></h3>
              <Link href="/academics#middle"><span className="sr-only"><Text domain="public">Explore Classes III to VIII learning</Text></span><ArrowUpRight /></Link>
            </div>
          </Reveal>
          <Reveal delay={180} className="learning-card learning-card--senior">
            <div className="learning-card__art learning-card__art--senior" aria-hidden="true">
              <span className="shape shape--grid" />
              <span className="shape shape--arch" />
              <span className="shape shape--sphere" />
            </div>
            <div className="learning-card__copy">
              <span><Text domain="public">Classes IX–X</Text></span><h3><Text domain="public">Focused preparation. Responsible choices. Strong next steps.</Text></h3>
              <Link href="/academics#senior"><span className="sr-only"><Text domain="public">Explore Classes IX to X learning</Text></span><ArrowUpRight /></Link>
            </div>
          </Reveal>
        </div>
      </section>

      <section className="invitation section-coral">
        <div className="invitation__rings" aria-hidden="true"><span /><span /><span /></div>
        <div className="shell invitation__inner">
          <Reveal>
            <p className="eyebrow"><span /> <Text domain="public">Come and meet us</Text></p>
            <h2><Text domain="public">Your child&apos;s next chapter</Text><br /><em><Text domain="public">can begin here.</Text></em></h2>
            <p>
              <Text domain="public">Visit the campus, meet our educators, and experience the warmth and energy of our learning community.</Text>
            </p>
            <div className="invitation__actions">
              <Link className="button button--cream" href="/admissions/visit"><Text domain="public">Book a school visit</Text> <Calendar /></Link>
              <Link className="text-link" href="/admissions"><Text domain="public">Explore admissions</Text> <ArrowRight /></Link>
            </div>
          </Reveal>
        </div>
      </section>
    </main>
  );
}
