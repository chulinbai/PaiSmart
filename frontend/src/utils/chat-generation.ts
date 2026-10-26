const terminalStates = new Set(['COMPLETED', 'INCOMPLETE', 'REJECTED', 'FAILED', 'CANCELLED', 'RECOVERABLE']);

export function isTerminalGeneration(status?: string) {
  return terminalStates.has(status || '');
}

export function generationDisplayStatus(status?: string): Api.Chat.Message['status'] {
  if (status === 'FAILED' || status === 'REJECTED') return 'error';
  if (isTerminalGeneration(status)) return 'finished';
  if (status === 'STREAMING' || status === 'FINALIZING') return 'loading';
  return 'pending';
}

export function applyTerminalSnapshot(message: Api.Chat.Message, payload: Record<string, any>) {
  if (message.terminalToken || isTerminalGeneration(message.generationStatus)) return false;
  let status = payload.status;
  if (status === 'finished') status = 'COMPLETED';
  if (status === 'failed') status = 'FAILED';
  if (!isTerminalGeneration(status)) return false;
  message.generationStatus = status;
  message.status = generationDisplayStatus(status);
  message.terminalToken = payload.token;
  message.lastSequence = Number(payload.lastSequence || 0);
  message.persistenceStatus = 'SAVED';
  if (typeof payload.content === 'string') message.content = payload.content;
  if (payload.referenceMappings) message.referenceMappings = payload.referenceMappings;
  message.finishReason = payload.finishReason;
  message.truncated = status === 'INCOMPLETE' || Boolean(payload.truncated);
  return true;
}

export function applyGenerationChunk(message: Api.Chat.Message, payload: Record<string, any>) {
  if (message.terminalToken || isTerminalGeneration(message.generationStatus) || message.generationStatus === 'FINALIZING') return false;
  const sequence = Number(payload.sequence || 0);
  if (sequence && sequence <= (message.lastSequence || 0)) return false;
  message.lastSequence = sequence || message.lastSequence;
  message.status = 'loading';
  message.content += payload.chunk;
  return true;
}

export function normalizeHistoryMessage(message: Api.Chat.Message): Api.Chat.Message {
  if (message.role !== 'assistant') return message;
  const status = String(message.status || 'COMPLETED');
  if (isTerminalGeneration(status)) {
    return { ...message, generationStatus: status as Api.Chat.GenerationStatus, status: generationDisplayStatus(status) };
  }
  return message;
}
