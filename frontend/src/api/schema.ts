import type { components } from './generated/schema';

/**
 * Request and response bodies, generated from the backend's OpenAPI spec (D171). A field the
 * backend doesn't mark required is optional and may be null.
 */
export type Schemas = components['schemas'];
