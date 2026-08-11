const repos = [
  'bartowski/Qwen2.5-0.5B-Instruct-GGUF','bartowski/Qwen2.5-1.5B-Instruct-GGUF',
  'bartowski/Qwen2.5-3B-Instruct-GGUF','bartowski/Qwen2.5-7B-Instruct-GGUF',
  'bartowski/Llama-3.2-1B-Instruct-GGUF','bartowski/Llama-3.2-3B-Instruct-GGUF',
  'bartowski/gemma-2-2b-it-GGUF','bartowski/Phi-3.5-mini-instruct-GGUF',
  'bartowski/SmolLM2-1.7B-Instruct-GGUF','bartowski/SmolLM2-360M-Instruct-GGUF',
  'bartowski/DeepSeek-R1-Distill-Qwen-1.5B-GGUF','bartowski/Mistral-7B-Instruct-v0.3-GGUF',
  'bartowski/Qwen2.5-Coder-1.5B-Instruct-GGUF','bartowski/TinyLlama-1.1B-Chat-v1.0-GGUF',
];
const want = /-(Q4_0|Q8_0|Q4_K_M)\.gguf$/;
for (const r of repos) {
  try {
    const res = await fetch(`https://huggingface.co/api/models/${r}/tree/main`, {headers:{'User-Agent':'cat'}});
    if (!res.ok) { console.log(`## ${r}  HTTP ${res.status}`); continue; }
    const files = await res.json();
    const hits = files.filter(f => want.test(f.path)).sort((a,b)=>a.size-b.size);
    if (!hits.length) { console.log(`## ${r}  (no matching quants)`); continue; }
    console.log(`## ${r}`);
    for (const f of hits) console.log(`   ${f.path}\t${f.size}`);
  } catch(e) { console.log(`## ${r}  ERR ${e.message}`); }
  await new Promise(s=>setTimeout(s,250));
}
