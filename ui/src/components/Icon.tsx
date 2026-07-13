// Material Design Icons rendered inline as SVG path data from @mdi/js — bundled, no external fetch,
// so the strict CSP is satisfied.
export function Icon({ path, size = 18, className, title }:
  { path: string; size?: number; className?: string; title?: string }) {
  return (
    <svg viewBox="0 0 24 24" width={size} height={size} className={className}
      fill="currentColor" aria-hidden={title ? undefined : true} role={title ? 'img' : undefined}>
      {title && <title>{title}</title>}
      <path d={path} />
    </svg>
  );
}
