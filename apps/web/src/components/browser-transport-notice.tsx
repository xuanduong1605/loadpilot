'use client';

import { useEffect, useState } from 'react';

export function BrowserTransportNotice({ className }: { className?: string }) {
  const [insecure, setInsecure] = useState(false);
  useEffect(() => { setInsecure(window.location.protocol === 'http:' && !window.isSecureContext); }, []);
  return insecure ? <p className={className} role="status">This connection uses HTTP. Use the HTTPS address for credentials and sensitive data.</p> : null;
}
