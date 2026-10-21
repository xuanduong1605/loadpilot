import type { Metadata } from 'next';
import './styles.css';

export const metadata: Metadata = {
  title: 'Loadpilot | Performance laboratory',
  description: 'Project foundation for an adaptive performance testing platform.',
};
export default function RootLayout({children}: Readonly<{children: React.ReactNode}>) {
  return <html lang="en"><body>{children}</body></html>;
}
