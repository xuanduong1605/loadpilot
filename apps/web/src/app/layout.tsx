import type { Metadata } from 'next';
import './styles.css';

export const metadata: Metadata = {
  title: 'Loadpilot | Performance laboratory',
  description: 'Plan load tests, understand capacity and compare resource configurations with Loadpilot.',
  icons: { icon: '/icon.svg' },
};
export default function RootLayout({children}: Readonly<{children: React.ReactNode}>) {
  return <html lang="en"><body>{children}</body></html>;
}
