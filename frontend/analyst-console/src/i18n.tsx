import { createContext, useContext, useState, type ReactNode } from 'react';

export type Lang = 'en' | 'zh';

/** Flat dictionary: key → { en, zh(繁中) }. Proper nouns / verdict codes left untranslated. */
export const DICT = {
  tagline: { en: 'agentic crypto-compliance screening', zh: '代理式加密合規篩查' },

  // identity and session
  'auth.title': { en: 'Sign in to Argus', zh: '登入 Argus' },
  'auth.intro': {
    en: 'Use your analyst account to open the protected investigation console.',
    zh: '使用分析師帳戶開啟受保護的調查控制台。',
  },
  'auth.username': { en: 'Username', zh: '使用者名稱' },
  'auth.password': { en: 'Password', zh: '密碼' },
  'auth.signIn': { en: 'Sign in', zh: '登入' },
  'auth.signingIn': { en: 'Signing in…', zh: '登入中…' },
  'auth.signOut': { en: 'Sign out', zh: '登出' },
  'auth.signingOut': { en: 'Signing out…', zh: '登出中…' },
  'auth.demo': { en: 'Local demo account', zh: '本機示範帳戶' },
  'auth.demoOnly': { en: 'Seeded by auth-service; never use in production.', zh: '由 auth-service 預置;請勿用於正式環境。' },
  'auth.retry': { en: 'Retry session check', zh: '重試工作階段檢查' },
  'auth.security': {
    en: 'The browser receives an HttpOnly session cookie. The upstream JWT stays inside the Node BFF.',
    zh: '瀏覽器只接收 HttpOnly 工作階段 Cookie;上游 JWT 保留在 Node BFF 內。',
  },
  'auth.currentSession': { en: 'Current session', zh: '目前工作階段' },
  'auth.mfaTitle': { en: 'Verify your identity', zh: '驗證您的身分' },
  'auth.mfaIntro': { en: 'Enter a second factor for', zh: '請輸入第二驗證因素:' },
  'auth.mfaMethod': { en: 'Verification method', zh: '驗證方式' },
  'auth.totp': { en: 'Authenticator app', zh: '驗證器應用程式' },
  'auth.totpCode': { en: '6-digit authenticator code', zh: '6 位數驗證碼' },
  'auth.recoveryCode': { en: 'Recovery code', zh: '復原碼' },
  'auth.verify': { en: 'Verify', zh: '驗證' },
  'auth.verifying': { en: 'Verifying…', zh: '驗證中…' },
  'auth.useAnotherAccount': { en: 'Use another account', zh: '使用其他帳戶' },
  'auth.recoverAccount': { en: 'Recover account', zh: '復原帳戶' },
  'auth.recoveryTitle': { en: 'Recover your account', zh: '復原您的帳戶' },
  'auth.recoveryIntro': {
    en: 'Use one offline recovery code to set a new password.',
    zh: '使用一組離線復原碼設定新密碼。',
  },
  'auth.newPassword': { en: 'New password', zh: '新密碼' },
  'auth.resetPassword': { en: 'Reset password', zh: '重設密碼' },
  'auth.recovering': { en: 'Resetting…', zh: '重設中…' },
  'auth.backToSignIn': { en: 'Back to sign in', zh: '返回登入' },
  'auth.recoveryFailed': { en: 'Account recovery failed.', zh: '帳戶復原失敗。' },

  // hero
  'hero.headline': {
    en: 'An AI agent that investigates every wallet — and shows its work.',
    zh: '一個會調查每個錢包的 AI 代理 —— 並完整展示其推理過程。',
  },
  'hero.sub': {
    en: 'Argus autonomously screens a crypto wallet the way a human analyst would: it plans an investigation, calls compliance tools, reasons over what it finds, and returns a CLEAR / REVIEW / BLOCK verdict — with a fully auditable trail of every thought and tool call.',
    zh: 'Argus 像人類分析師一樣自主篩查加密錢包:它規劃調查、呼叫合規工具、對結果進行推理,最後給出 CLEAR / REVIEW / BLOCK 裁決 —— 並留下每一步思考與工具呼叫的可稽核軌跡。',
  },
  'hero.term': { en: 'Argus', zh: 'Argus(阿耳戈斯)' },
  'hero.def': {
    en: 'in Greek myth, the hundred-eyed giant set to watch over everything, never closing all his eyes at once. A fitting name for an agent that watches every transaction.',
    zh: '希臘神話中擁有一百隻眼睛的巨人,被指派看守一切,從不同時閉上所有眼睛。正適合一個監看每筆交易的代理。',
  },
  'hero.cta': { en: 'Investigate a wallet', zh: '開始調查錢包' },

  // how it works
  'how.title': { en: 'How Argus works', zh: 'Argus 如何運作' },
  'how.s1t': { en: 'Plan', zh: '規劃' },
  'how.s1d': {
    en: 'Given a wallet, the agent decides which checks to run and in what order — sanctions first, then profile, trace and rules.',
    zh: '針對一個錢包,代理決定要執行哪些檢查及其順序 —— 先制裁篩查,再做畫像、追蹤與規則。',
  },
  'how.s2t': { en: 'Act', zh: '行動' },
  'how.s2d': {
    en: 'It calls real compliance tools — sanctions screen, address profile, transaction trace, risk rules — with concrete arguments.',
    zh: '它以具體參數呼叫真實的合規工具 —— 制裁篩查、地址畫像、交易追蹤、風險規則。',
  },
  'how.s3t': { en: 'Observe', zh: '觀察' },
  'how.s3d': {
    en: 'Each tool result feeds back into the agent’s reasoning, which may trigger a deeper follow-up step.',
    zh: '每個工具結果回饋到代理的推理中,可能觸發更深入的後續步驟。',
  },
  'how.s4t': { en: 'Decide', zh: '裁決' },
  'how.s4d': {
    en: 'It returns a verdict — CLEAR, REVIEW or BLOCK — with a risk score and the exact factors behind it.',
    zh: '它給出裁決 —— CLEAR、REVIEW 或 BLOCK —— 附帶風險分數與其背後的具體因素。',
  },
  'how.go': { en: 'Open →', zh: '前往 →' },

  // search panel
  'search.title': { en: 'Run an investigation', zh: '執行調查' },
  'search.placeholder': { en: 'Enter a wallet address (or pick a demo wallet below)', zh: '輸入錢包地址(或在下方選擇示範錢包)' },
  'search.button': { en: 'Investigate', zh: '調查' },
  'search.demoHint': { en: 'Try a pre-seeded demo wallet:', zh: '試試預置的示範錢包:' },
  'search.empty': { en: 'Enter a wallet address to investigate.', zh: '請輸入要調查的錢包地址。' },

  // demo wallet labels
  'demo.block': { en: 'directly sanctioned', zh: '直接受制裁' },
  'demo.mixer': { en: '1-hop mixer exposure', zh: '一跳混幣器關聯' },
  'demo.structuring': { en: 'structuring pattern', zh: '化整為零模式' },
  'demo.clean': { en: 'clean wallet', zh: '乾淨錢包' },

  // timeline
  'tl.title': { en: 'Agent reasoning & tool-call timeline', zh: '代理推理與工具呼叫時間軸' },
  'tl.subject': { en: 'Subject', zh: '調查對象' },
  'tl.waiting': { en: 'Waiting for the agent to take its first step…', zh: '等待代理邁出第一步…' },
  'tl.io': { en: 'tool call + observation', zh: '工具呼叫 + 觀察結果' },
  'tl.args': { en: 'Arguments', zh: '參數' },
  'tl.obs': { en: 'Observation', zh: '觀察結果' },

  // decision panel
  'dec.title': { en: 'Compliance decision', zh: '合規裁決' },
  'dec.score': { en: 'Risk score', zh: '風險分數' },
  'dec.band': { en: 'Risk band', zh: '風險等級' },
  'dec.decidedBy': { en: 'Decided by', zh: '裁決者' },
  'dec.factors': { en: 'Risk factors', zh: '風險因素' },
  'dec.clearSub': { en: 'No disqualifying exposure found. Safe to proceed.', zh: '未發現足以否決的風險關聯。可安全放行。' },
  'dec.reviewSub': { en: 'Elevated risk — route to a human analyst before proceeding.', zh: '風險升高 —— 放行前需轉交人工分析師審查。' },
  'dec.blockSub': { en: 'Disqualifying exposure. Do not transact.', zh: '存在否決性風險關聯。請勿交易。' },
  'dec.pending': { en: 'The agent is still working — the verdict appears here when it finishes.', zh: '代理仍在運作中 —— 完成後此處會顯示裁決。' },

  // status / misc
  'status.running': { en: 'in progress', zh: '進行中' },
  'inprogress.title': { en: 'Investigation in progress', zh: '調查進行中' },
  'inprogress.desc': {
    en: 'The agent is planning, calling tools and observing results. This view updates live.',
    zh: '代理正在規劃、呼叫工具並觀察結果。此畫面即時更新。',
  },

  // footer
  'footer.note': {
    en: 'Argus pairs an LLM planner with a sandboxed set of compliance tools. Every decision is reproducible from its trail; nothing is a black box.',
    zh: 'Argus 將 LLM 規劃器與一組沙箱化的合規工具配對。每個決策都可從其軌跡重現;沒有黑箱。',
  },
  'footer.source': { en: 'source', zh: '原始碼' },

  // hints (jargon)
  'hint.sanctions': {
    en: 'Sanctions screening checks the wallet against government watch-lists (e.g. OFAC SDN). A direct hit is an automatic BLOCK.',
    zh: '制裁篩查將錢包比對政府觀察名單(如 OFAC SDN)。直接命中即自動 BLOCK。',
  },
  'hint.exposure': {
    en: 'Exposure measures how close (in hops) the wallet sits to a flagged source — e.g. a mixer or a sanctioned address — by tracing its transaction graph.',
    zh: '關聯度衡量錢包透過交易圖譜距離被標記來源(如混幣器或受制裁地址)有多近(以「跳」計)。',
  },
  'hint.score': {
    en: 'A 0–100 risk score the agent assigns from all evidence. Admin-set thresholds map the score into a CLEAR / REVIEW / BLOCK band.',
    zh: '代理依據所有證據給出的 0–100 風險分數。管理員設定的門檻將分數對應到 CLEAR / REVIEW / BLOCK 等級。',
  },
  'hint.verdict': {
    en: 'CLEAR = safe to proceed. REVIEW = elevated risk, send to a human analyst. BLOCK = disqualifying exposure, do not transact.',
    zh: 'CLEAR = 可放行。REVIEW = 風險升高,轉人工審查。BLOCK = 否決性風險,禁止交易。',
  },
  'hint.loop': {
    en: 'The agent loop: plan → call a tool → observe the result → reason → repeat, until it has enough evidence to decide. Each pass is one timeline step.',
    zh: '代理迴圈:規劃 → 呼叫工具 → 觀察結果 → 推理 → 重複,直到證據足以裁決。每一輪即時間軸上的一步。',
  },
  'hint.provider': {
    en: 'The LLM that did the planning and reasoning for this investigation.',
    zh: '為本次調查進行規劃與推理的 LLM。',
  },
} as const;

export type I18nKey = keyof typeof DICT;

interface I18nValue {
  lang: Lang;
  setLang: (l: Lang) => void;
  t: (key: I18nKey) => string;
}

const Ctx = createContext<I18nValue | null>(null);

export function LangProvider({ children }: { children: ReactNode }) {
  const [lang, setLangState] = useState<Lang>(() => {
    try {
      return (localStorage.getItem('argus.lang') as Lang) || 'en';
    } catch {
      return 'en';
    }
  });
  const setLang = (l: Lang) => {
    setLangState(l);
    try {
      localStorage.setItem('argus.lang', l);
    } catch {
      /* ignore */
    }
  };
  const t = (key: I18nKey) => DICT[key][lang];
  return <Ctx.Provider value={{ lang, setLang, t }}>{children}</Ctx.Provider>;
}

export function useI18n(): I18nValue {
  const c = useContext(Ctx);
  if (!c) throw new Error('useI18n must be used within LangProvider');
  return c;
}
