// Only for the loopback integration environment. No external model requests.
import http from 'node:http';
const facts = [
  {kind:'ENTITY',name:'支付服务',description:'支付服务处理订单支付。'},
  {kind:'ENTITY',name:'库存服务',description:'库存服务校验并预留库存。'},
  {kind:'RELATION',source:'支付服务',target:'库存服务',predicate:'依赖',direction:'DIRECTED',description:'支付服务依赖库存服务。'}
];
let mode='normal';
const stats={requests:0,streams:0,closed:0,active:0};
http.createServer(async(req,res)=>{
  let body='';for await(const chunk of req)body+=chunk;
  const input=body?JSON.parse(body):{};
  if(req.url==='/__test/control') {if(input.mode)mode=input.mode;res.setHeader('Content-Type','application/json');res.end(JSON.stringify({mode,...stats}));return;}
  if(req.url.endsWith('/models')){res.end(JSON.stringify({data:[{id:'knowledge-fixture'}]}));return;}
  if(req.url.endsWith('/embeddings')){const items=Array.isArray(input.input)?input.input:[input.input];res.setHeader('Content-Type','application/json');res.end(JSON.stringify({data:items.map((_,index)=>({index,embedding:[1,.2,.1]})),usage:{total_tokens:items.length*10}}));return;}
  stats.requests++;
  const messages=input.messages||[],system=messages.filter(m=>m.role==='system').map(m=>m.content).join('\n');
  const continuation=messages.some(m=>m.role==='user'&&typeof m.content==='string'&&m.content.includes('上一个回答因输出长度'));
  let content='',calls,finish='stop';
  if(system.includes('抽取有原文'))content=JSON.stringify({facts});
  else if(system.includes('来源贡献生成'))content='支付服务依赖库存服务。';
  else if(system.includes('提取关键词'))content=JSON.stringify({entities:'支付服务 库存服务',topics:'支付依赖库存'});
  else if(mode==='empty')content='';
  else if(input.tools?.length&&messages.slice(messages.findLastIndex(m=>m.role==='user')).filter(m=>m.role==='tool').length<(mode==='cached'?4:2)) {
    calls=['search_knowledge','search_graph'].map((name,index)=>({id:`fixture_${index}`,type:'function',function:{name,arguments:JSON.stringify({query:'支付服务依赖库存服务',topK:5})}}));finish='tool_calls';
  } else if(input.tools?.length)content='资料已齐备。';
  else {
    content=continuation?'续写结束。':'支付服务依赖库存服务，支付前需要校验库存。(来源#1: 支付流程.txt)';
    if(mode==='length'&&!continuation)finish='length';
    if(mode==='unknown')finish='new_reason';
  }
  if(!input.stream){res.setHeader('Content-Type','application/json');res.end(JSON.stringify({choices:[{index:0,message:{role:'assistant',content,...(calls?{tool_calls:calls}:{})},finish_reason:finish}],usage:{prompt_tokens:20,completion_tokens:20}}));return;}
  stats.streams++;stats.active++;
  res.on('close',()=>{stats.active--;stats.closed++;});
  res.writeHead(200,{'Content-Type':'text/event-stream','Cache-Control':'no-cache'});
  const emit=value=>res.write(`data: ${JSON.stringify(value)}\n\n`);
  emit({choices:[{index:0,delta:calls?{tool_calls:calls.map((call,index)=>({index,...call}))}:{content},finish_reason:null}]});
  if(mode==='partial'&&!input.tools?.length){res.end();return;}
  if(mode==='reconnect'&&!input.tools?.length){const timer=setTimeout(()=>{emit({choices:[{delta:{content:'连接恢复后完成。'},finish_reason:'stop'}]});res.end('data: [DONE]\n\n');},2000);res.on('close',()=>clearTimeout(timer));return;}
  if(mode==='cancel'&&!input.tools?.length){const timer=setTimeout(()=>{emit({choices:[{delta:{content:'迟到的正文'},finish_reason:'stop'}]});res.end('data: [DONE]\n\n');},60000);res.on('close',()=>clearTimeout(timer));return;}
  emit({choices:[{index:0,delta:{},finish_reason:finish}],usage:{prompt_tokens:20,completion_tokens:20}});
  res.end('data: [DONE]\n\n');
}).listen(19135,'127.0.0.1',()=>process.stdout.write('LLM reliability fixture on 19135\n'));
