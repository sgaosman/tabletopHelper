import api from './axiosConfig';
import type { Spell, Condition, Item, SpellSearchParams, ItemSearchParams, PageResponse, Race, CharacterClassRef, Subclass, Background, Feat } from '../types/reference';

export const searchSpells = async (params: SpellSearchParams): Promise<PageResponse<Spell>> => {
  const response = await api.get('/reference/spells', { params });
  return response.data;
};

export const getSpell = async (id: string): Promise<Spell> => {
  const response = await api.get(`/reference/spells/${id}`);
  return response.data;
};

export const getSpellSchools = async (): Promise<string[]> => {
  const response = await api.get('/reference/spells/filters/schools');
  return response.data;
};

export const getSpellSources = async (): Promise<string[]> => {
  const response = await api.get('/reference/spells/filters/sources');
  return response.data;
};

export const getSpellClasses = async (): Promise<string[]> => {
  const response = await api.get('/reference/spells/filters/classes');
  return response.data;
};

export const getSpellSubclasses = async (className: string): Promise<string[]> => {
  const response = await api.get('/reference/spells/filters/subclasses', { params: { className } });
  return response.data;
};

export const getAllConditions = async (): Promise<Condition[]> => {
  const response = await api.get('/reference/conditions');
  return response.data;
};

export const searchItems = async (params: ItemSearchParams): Promise<PageResponse<Item>> => {
  const response = await api.get('/reference/items', { params });
  return response.data;
};

export const getItem = async (id: string): Promise<Item> => {
  const response = await api.get(`/reference/items/${id}`);
  return response.data;
};

export const getItemTypes = async (): Promise<string[]> => {
  const response = await api.get('/reference/items/filters/types');
  return response.data;
};

export const getItemRarities = async (): Promise<string[]> => {
  const response = await api.get('/reference/items/filters/rarities');
  return response.data;
};

export const getItemSources = async (): Promise<string[]> => {
  const response = await api.get('/reference/items/filters/sources');
  return response.data;
};

export const getQuickReference = async (): Promise<unknown[]> => {
  const response = await api.get('/reference/quickref');
  return response.data;
};

export const getRaces = async (source?: string): Promise<Race[]> => {
  const response = await api.get('/reference/races', { params: source ? { source } : {} });
  return response.data;
};

export const getRaceSources = async (): Promise<string[]> => {
  const response = await api.get('/reference/races/filters/sources');
  return response.data;
};

export const getClasses = async (): Promise<CharacterClassRef[]> => {
  const response = await api.get('/reference/classes');
  return response.data;
};

export const getSubclasses = async (classId: string): Promise<Subclass[]> => {
  const response = await api.get(`/reference/classes/${classId}/subclasses`);
  return response.data;
};

export const getBackgrounds = async (): Promise<Background[]> => {
  const response = await api.get('/reference/backgrounds');
  return response.data;
};

export const getFeats = async (): Promise<Feat[]> => {
  const response = await api.get('/reference/feats');
  return response.data;
};
