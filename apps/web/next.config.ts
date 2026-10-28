import type { NextConfig } from 'next';

const development = process.env.NODE_ENV === 'development';
const config: NextConfig = {
  // Workspace instructions are maintained at the monorepo root.
  agentRules: false,
  output: development ? undefined : 'export',
  trailingSlash: true,
  poweredByHeader: false,
  ...(development ? {
    async rewrites() {
      return [{source: '/api/:path*', destination: 'http://127.0.0.1:8080/api/:path*'}];
    },
  } : {}),
};
export default config;
