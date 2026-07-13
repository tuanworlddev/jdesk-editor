// Single import surface for the typed, code-generated command bindings (proven first-consumer of
// JDesk's codegen). UI code calls e.g. `commands.doc.open({ relPath })` and gets a typed result.
export { commands } from './generated/commands';
export type * from './generated/types';
export { on, emit, JDeskError } from 'jdesk-client';
