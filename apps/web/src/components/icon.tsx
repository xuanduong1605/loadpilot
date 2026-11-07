import type { SVGProps } from 'react';

const paths = {
  activity: 'M3 12h4l3-8 4 16 3-8h4',
  download: 'M12 3v12m-5-5 5 5 5-5M4 16v5h16v-5',
  stop: 'M5 5h14v14H5Z',
  clock: 'M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18Zm0 4v5l3 2',
  arrow: 'M5 12h14m-6-6 6 6-6 6',
  chevron: 'm9 5 7 7-7 7',
  panel: 'M3 3h18v18H3Zm6 0v18',
  plus: 'M12 5v14M5 12h14',
  search: 'M21 21l-5-5M10 3a7 7 0 1 0 0 14 7 7 0 0 0 0-14',
  server: 'M3 3h18v7H3Zm0 11h18v7H3ZM7 6.5h.01M7 17.5h.01',
  chart: 'M4 19h16M7 15v-4m5 4V5m5 10V8',
  check: 'm5 12 4 4L19 6',
  compare: 'M8 4v16m8-16v16M4 8h8m0 8h8',
  experiments: 'M9 3h6m-5 0v7l-5 8a2 2 0 0 0 2 3h10a2 2 0 0 0 2-3l-5-8V3M8 15h8',
  eye: 'M2 12s4-7 10-7 10 7 10 7-4 7-10 7S2 12 2 12Zm10-3a3 3 0 1 0 0 6 3 3 0 0 0 0-6',
  grid: 'M3 3h7v7H3Zm11 0h7v7h-7ZM3 14h7v7H3Zm11 0h7v7h-7Z',
  lock: 'M7 10V7a5 5 0 0 1 10 0v3M5 10h14v11H5Zm7 5v2',
  logout: 'M9 4H4v16h5m5-12 4 4-4 4m-6-4h10',
  menu: 'M4 6h16M4 12h16M4 18h16',
  memory: 'M7 3v3m5-3v3m5-3v3M7 18v3m5-3v3m5-3v3M3 7h3m-3 5h3m-3 5h3m12-10h3m-3 5h3m-3 5h3M6 6h12v12H6Zm4 4h4v4h-4Z',
  refresh: 'M20 7v5h-5M4 17v-5h5m-4-4a8 8 0 0 1 13-3l2 3M4 16l2 3a8 8 0 0 0 13-3',
  scenarios: 'm8 7-5 5 5 5m8-10 5 5-5 5m-3-14-2 18',
  settings: 'M4 7h16M4 17h16M8 4v6m8 4v6',
  preferences: 'M4 6h16M4 12h16M4 18h16M8 3v6m8 0v6m-6 0v6',
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
