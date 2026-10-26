// Local deterministic model fixture for the isolated integration environment. Never a production provider.
import http from 'node:http';
const facts = [
  { kind: 'ENTITY', name: '支付服务', description: '支付服务处理订单支付。' },
  { kind: 'ENTITY', name: '库存服务', description: '库存服务校验并预留库存。' },
  { kind: 'RELATION', source: '支付服务', target: '库存服务', predicate: '依赖', direction: 'DIRECTED', description: '支付服务依赖库存服务；支付之前需要校验库存。' }
];
http.createServer(async (req, res) => {
  try {
    let body = ''; for await (const chunk of req) body += chunk;
    const input = body ? JSON.parse(body) : {};
    if (req.url.endsWith('/models')) { res.end(JSON.stringify({ data: [{ id: 'knowledge-fixture' }] })); return; }
    if (req.url.endsWith('/embeddings')) {
      const items = Array.isArray(input.input) ? input.input : [input.input];
      res.setHeader('Content-Type', 'application/json');
      res.end(JSON.stringify({ data: items.map((_, index) => ({ index, embedding: [1, 0.2, 0.1] })), usage: { total_tokens: items.length * 10 } })); return;
    }
    const messages = input.messages || [];
    const system = messages.filter(m => m.role === 'system').map(m => m.content).join('\n');
    let content; let toolCalls;
    if (system.includes('抽取有原文')) content = JSON.stringify({ facts });
    else if (system.includes('聚合') || system.includes('来源贡献生成')) content = '支付服务依赖库存服务。支付之前校验并预留库存。';
    else if (system.includes('提取关键词')) content = JSON.stringify({ entities: '支付服务 库存服务', topics: '支付依赖库存' });
    else if (input.tools?.length && !messages.some(m => m.role === 'tool')) {
      content = '';
      toolCalls = ['search_knowledge', 'search_graph'].map((name, index) => ({ id: `fixture_${index}`, type: 'function', function: { name, arguments: JSON.stringify({ query: '支付服务依赖库存服务', topK: 5 }) } }));
    } else if (input.tools?.length) content = '资料已齐备。';
    else content = '支付服务依赖库存服务，支付之前需要校验并预留库存。(来源#1: 支付流程.txt)';
    const choice = { index: 0, finish_reason: toolCalls ? 'tool_calls' : 'stop' };
    if (input.stream) {
      res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache' });
      const delta = toolCalls ? { role: 'assistant', tool_calls: toolCalls.map((call, index) => ({ index, ...call })) } : { role: 'assistant', content };
      res.write(`data: ${JSON.stringify({ choices: [{ index: 0, delta, finish_reason: null }] })}\n\n`);
      res.write(`data: ${JSON.stringify({ choices: [{ ...choice, delta: {} }], usage: { prompt_tokens: 20, completion_tokens: 20 } })}\n\n`);
      res.end('data: [DONE]\n\n');
    } else {
      res.setHeader('Content-Type', 'application/json');
      res.end(JSON.stringify({ choices: [{ ...choice, message: { role: 'assistant', content, ...(toolCalls ? { tool_calls: toolCalls } : {}) } }], usage: { prompt_tokens: 20, completion_tokens: 20 } }));
    }
  } catch (error) { res.statusCode = 500; res.end(JSON.stringify({ error: error.message })); }
}).listen(19135, '127.0.0.1', () => process.stdout.write('Knowledge test model fixture listening on 19135\n'));
