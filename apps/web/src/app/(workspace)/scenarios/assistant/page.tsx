import Link from 'next/link';
import { PageHeading } from '../../../../components/console-ui';

export const metadata = { title: 'Script assistance | Loadpilot' };

export default function Page() {
  return <><PageHeading title="Script assistance" back={{ href: '/scenarios/', label: 'Back to scripts' }} />
    <p><Link href="/assistant/?task=SCRIPT">Open the assistant to discuss script changes</Link></p></>;
}
