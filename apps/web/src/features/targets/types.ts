export type TargetInput = {
  name: string; baseUrl: string; environment: 'DEVELOPMENT' | 'STAGING' | 'PRODUCTION';
  namespace: string; deployment: string; container: string; credentialRef: string; monitoringRef: string;
};
export type Target = Omit<TargetInput, 'credentialRef' | 'monitoringRef'> & {
  credentialRef: string | null; monitoringRef: string | null;
  id: string; revision: number; createdAt: string; updatedAt: string; archivedAt: string | null;
};
export type TargetPage = { items: Target[]; total: number; offset: number; limit: number };
