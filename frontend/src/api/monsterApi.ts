import api from './axiosConfig';
import type { Monster, MonsterSearchParams } from '../types/monster';
import type { PageResponse } from '../types/reference';

export const searchMonsters = async (params: MonsterSearchParams): Promise<PageResponse<Monster>> => {
  const response = await api.get('/monsters', { params });
  return response.data;
};

export const getMonster = async (id: string): Promise<Monster> => {
  const response = await api.get(`/monsters/${id}`);
  return response.data;
};

export const getMonsterSources = async (): Promise<string[]> => {
  const response = await api.get('/monsters/filters/sources');
  return response.data;
};

export const getMonsterTypes = async (): Promise<string[]> => {
  const response = await api.get('/monsters/filters/types');
  return response.data;
};

export const getMonsterChallengeRatings = async (): Promise<string[]> => {
  const response = await api.get('/monsters/filters/challenge-ratings');
  return response.data;
};
