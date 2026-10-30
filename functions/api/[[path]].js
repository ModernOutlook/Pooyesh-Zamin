import worker from "../../src/index.js";

// Keep the existing Workers API as the single source of truth.
// Pages serves static files from /public and forwards /api/* requests here.
export async function onRequest(context) {
  return worker.fetch(context.request, context.env);
}
