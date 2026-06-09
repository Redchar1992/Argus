/** Small ⓘ icon with a hover/focus tooltip — for explaining compliance jargon inline. */
export function Hint({ text }: { text: string }) {
  return (
    <span className="hint" tabIndex={0} role="note" aria-label={text}>
      <span className="hint-icon">i</span>
      <span className="hint-text">{text}</span>
    </span>
  );
}
