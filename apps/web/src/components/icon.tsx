import type { SVGProps } from 'react';

const paths = {
  arrow: 'M5 12h14m-6-6 6 6-6 6',
  chart: 'M4 19h16M7 15v-4m5 4V5m5 10V8',
  check: 'm5 12 4 4L19 6',
  compare: 'M8 4v16m8-16v16M4 8h8m0 8h8',
  experiments: 'M9 3h6m-5 0v7l-5 8a2 2 0 0 0 2 3h10a2 2 0 0 0 2-3l-5-8V3M8 15h8',
  eye: 'M2 12s4-7 10-7 10 7 10 7-4 7-10 7S2 12 2 12Zm10-3a3 3 0 1 0 0 6 3 3 0 0 0 0-6',
  grid: 'M3 3h7v7H3Zm11 0h7v7h-7ZM3 14h7v7H3Zm11 0h7v7h-7Z',
  lock: 'M7 10V7a5 5 0 0 1 10 0v3M5 10h14v11H5Zm7 5v2',
  menu: 'M4 6h16M4 12h16M4 18h16',
  scenarios: 'm8 7-5 5 5 5m8-10 5 5-5 5m-3-14-2 18',
  settings: 'M4 7h16M4 17h16M8 4v6m8 4v6',
  target: 'M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20Zm0 5a5 5 0 1 0 0 10 5 5 0 0 0 0-10Zm0 5h.01',
} as const;

export type IconName = keyof typeof paths;

export function Icon({ name, ...props }: SVGProps<SVGSVGElement> & { name: IconName }) {
  return (
    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" {...props}>
      <path d={paths[name]} />
    </svg>
  );
}
