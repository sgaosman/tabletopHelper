// Saves the running backend's OpenAPI spec to src/api/generated/openapi.json, from which
// `npm run api:types` generates the API types (D171). BACKEND_URL picks the backend.
import { mkdir, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const backendUrl = (process.env.BACKEND_URL || 'http://localhost:8080').replace(/\/+$/, '');
const specUrl = `${backendUrl}/v3/api-docs`;
const target = resolve(dirname(fileURLToPath(import.meta.url)), '../src/api/generated/openapi.json');

// Returns the exit code. Calling process.exit() while fetch still holds a socket can crash Node on Windows
async function main() {
  let spec;
  try {
    const response = await fetch(specUrl);
    if (!response.ok) {
      throw new Error(`the server answered ${response.status}`);
    }
    spec = await response.json();
  } catch (error) {
    console.error(`Could not load ${specUrl}: ${error.message}. Start the dev backend, or set BACKEND_URL.`);
    return 1;
  }

  // The generated types rely on OpenAPI 3.0's nullable; any other version means the backend is
  // running older code
  if (!String(spec.openapi).startsWith('3.0.')) {
    console.error(`${specUrl} serves OpenAPI ${spec.openapi}, not 3.0. Restart the backend with the current code.`);
    return 1;
  }

  // The backend sorts the keys, so the same API always gives the same file
  await mkdir(dirname(target), { recursive: true });
  await writeFile(target, `${JSON.stringify(spec, null, 2)}\n`);
  console.log(`Saved ${specUrl} to ${target}`);
  return 0;
}

process.exitCode = await main();
