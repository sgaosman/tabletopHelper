import { skipToken, useMutation, useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query';
import { campaignApi } from '../api/campaignApi';
import type { Campaign, CampaignCreateRequest, CampaignJoinRequest, CampaignSettingsRequest } from '../types/campaign';
import { queryKeys } from './keys';
import { invalidateOnConflict } from './queryClient';

/** The campaigns the signed-in user runs or plays in. */
export function useCampaigns() {
  return useQuery({
    queryKey: queryKeys.campaigns.list(),
    queryFn: async () => (await campaignApi.getAll()).data,
  });
}

/** One campaign. With `refetchInterval` it is fetched again on that interval while the tab is visible. */
export function useCampaign(campaignId: string | undefined, { refetchInterval }: { refetchInterval?: number } = {}) {
  return useQuery({
    queryKey: queryKeys.campaigns.detail(campaignId ?? ''),
    queryFn: campaignId ? async () => (await campaignApi.getById(campaignId)).data : skipToken,
    refetchInterval,
  });
}

/** Creates a campaign; it appears in the campaign list once the list has been fetched again. */
export function useCreateCampaign() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (request: CampaignCreateRequest) => (await campaignApi.create(request)).data,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: queryKeys.campaigns.list(), exact: true }),
    onError: error => invalidateOnConflict(queryClient, error, queryKeys.campaigns.list()),
  });
}

/** Joins a campaign with its invite code; it appears in the campaign list once the list has been fetched again. */
export function useJoinCampaign() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (request: CampaignJoinRequest) => (await campaignApi.join(request)).data,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: queryKeys.campaigns.list(), exact: true }),
    onError: error => invalidateOnConflict(queryClient, error, queryKeys.campaigns.list()),
  });
}

/** Saves the DM's campaign settings; the campaign the server returns replaces the cached copies. */
export function useUpdateCampaignSettings() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ campaignId, settings }: { campaignId: string; settings: CampaignSettingsRequest }) =>
      (await campaignApi.updateSettings(campaignId, settings)).data,
    onSuccess: campaign => writeCampaign(queryClient, campaign),
    onError: (error, { campaignId }) =>
      invalidateOnConflict(queryClient, error, queryKeys.campaigns.detail(campaignId)),
  });
}

async function writeCampaign(queryClient: QueryClient, campaign: Campaign) {
  const detail = queryKeys.campaigns.detail(campaign.id);
  // A poll that was already under way would otherwise land afterwards and put the old campaign back
  await queryClient.cancelQueries({ queryKey: detail, exact: true });
  queryClient.setQueryData(detail, campaign);
  queryClient.setQueryData<Campaign[]>(queryKeys.campaigns.list(), campaigns =>
    campaigns?.map(c => (c.id === campaign.id ? campaign : c)));
}
