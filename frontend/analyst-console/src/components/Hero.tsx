import { useI18n } from '../i18n';

/** Ambient, on-brand hero art: an all-seeing eye with a scanning sweep across the
 * iris and "watcher" dots orbiting the lid — the Argus motif (the hundred-eyed
 * watchman). Pure SVG + SMIL, low-key, disabled under prefers-reduced-motion,
 * hidden on mobile via CSS. */
function HeroArt() {
  return (
    <svg viewBox="0 0 300 180" className="hero-art-svg" role="img" aria-hidden="true">
      <defs>
        <linearGradient id="argusLg" x1="0" y1="0" x2="1" y2="1">
          <stop offset="0" stopColor="#6aa0ff" />
          <stop offset="1" stopColor="#bcd4ff" />
        </linearGradient>
        <radialGradient id="argusGlow" cx="50%" cy="50%" r="50%">
          <stop offset="0" stopColor="#6aa0ff" stopOpacity="0.24" />
          <stop offset="1" stopColor="#6aa0ff" stopOpacity="0" />
        </radialGradient>
        <radialGradient id="argusIris" cx="50%" cy="45%" r="55%">
          <stop offset="0" stopColor="#bcd4ff" />
          <stop offset="0.55" stopColor="#6aa0ff" />
          <stop offset="1" stopColor="#2f4f8c" />
        </radialGradient>
        {/* clip so the scan sweep stays inside the iris */}
        <clipPath id="irisClip">
          <circle cx="150" cy="90" r="34" />
        </clipPath>
      </defs>

      <ellipse cx="150" cy="90" rx="135" ry="78" fill="url(#argusGlow)" />

      {/* eye almond outline */}
      <path
        d="M70 90 Q150 36 230 90 Q150 144 70 90 Z"
        fill="none"
        stroke="url(#argusLg)"
        strokeWidth="2.6"
      />

      {/* iris */}
      <g className="eye-iris">
        <circle cx="150" cy="90" r="34" fill="url(#argusIris)" opacity="0.9" />
        <circle cx="150" cy="90" r="34" fill="none" stroke="#bcd4ff" strokeWidth="1.4" opacity="0.5" />
        <circle cx="150" cy="90" r="13" fill="#0b0e14" />
        <circle cx="144" cy="84" r="4" fill="#e8f0ff" opacity="0.85" />
        {/* scanning sweep line across the iris */}
        <g clipPath="url(#irisClip)">
          <rect className="scan-sweep" x="148" y="54" width="3" height="72" fill="#e8f0ff" opacity="0.85" />
        </g>
      </g>

      {/* "watcher" eyes / data points feeding the loop: plan → act → observe */}
      <g fill="#9bc0ff">
        <circle className="flowdot" cx="70" cy="90" r="3.2">
          <animate attributeName="opacity" values="0.2;1;0.2" dur="2.8s" repeatCount="indefinite" />
        </circle>
        <circle className="flowdot" cx="150" cy="34" r="3.2">
          <animate attributeName="opacity" values="0.2;1;0.2" dur="2.8s" begin="0.9s" repeatCount="indefinite" />
        </circle>
        <circle className="flowdot" cx="230" cy="90" r="3.2">
          <animate attributeName="opacity" values="0.2;1;0.2" dur="2.8s" begin="1.8s" repeatCount="indefinite" />
        </circle>
      </g>
    </svg>
  );
}

export function Hero() {
  const { t } = useI18n();
  return (
    <div className="hero">
      <div className="hero-glow" aria-hidden />
      <div className="hero-text">
        <h1>{t('hero.headline')}</h1>
        <p>{t('hero.sub')}</p>
        <div className="namecard">
          <span className="term">{t('hero.term')}</span>
          <span className="def">{t('hero.def')}</span>
        </div>
        <br />
        <a className="cta" href="#investigate">
          {t('hero.cta')} →
        </a>
      </div>
      <div className="hero-art">
        <HeroArt />
      </div>
    </div>
  );
}

export function HowItWorks() {
  const { t } = useI18n();
  const steps = [
    { title: t('how.s1t'), desc: t('how.s1d') },
    { title: t('how.s2t'), desc: t('how.s2d') },
    { title: t('how.s3t'), desc: t('how.s3d') },
    { title: t('how.s4t'), desc: t('how.s4d') },
  ];
  return (
    <div className="how">
      <div className="section-title">{t('how.title')}</div>
      <div className="steps">
        {steps.map((s, i) => (
          <a className="step" key={i} href="#investigate">
            <div className="step-num">{i + 1}</div>
            <div className="step-title">{s.title}</div>
            <div className="step-desc">{s.desc}</div>
          </a>
        ))}
      </div>
    </div>
  );
}
