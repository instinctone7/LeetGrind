export function Icon({ name, className = '' }: { name: string; className?: string }) {
  return <span className={`orbital-icon ${className}`} aria-hidden="true">{name}</span>
}

export function Avatar({ name, className = '' }: { name: string; className?: string }) {
  return <span className={`orbital-avatar ${className}`} aria-hidden="true">{Array.from(name.trim())[0]?.toUpperCase() ?? '?'}</span>
}

export function Wordmark() {
  return <span className="orbital-wordmark">
    <strong>LeetGrind</strong><small>Observatory</small>
  </span>
}
