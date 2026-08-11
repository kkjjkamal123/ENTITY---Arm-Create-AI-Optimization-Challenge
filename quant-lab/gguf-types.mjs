import {openSync, readSync, fstatSync} from 'fs';
const T={0:'F32',1:'F16',2:'Q4_0',3:'Q4_1',6:'Q5_0',7:'Q5_1',8:'Q8_0',9:'Q8_1',10:'Q2_K',11:'Q3_K',12:'Q4_K',13:'Q5_K',14:'Q6_K',15:'Q8_K',30:'BF16'};
const fd=openSync(process.argv[2],'r');
const buf=Buffer.alloc(64*1024*1024); readSync(fd,buf,0,buf.length,0);
let o=0;
const u32=()=>{const v=buf.readUInt32LE(o);o+=4;return v;};
const u64=()=>{const v=Number(buf.readBigUInt64LE(o));o+=8;return v;};
const str=()=>{const n=u64();const s=buf.toString('utf8',o,o+n);o+=n;return s;};
if(buf.toString('utf8',0,4)!=='GGUF')throw new Error('not gguf');
o=4; const ver=u32(); const nTensors=u64(); const nKV=u64();
const readVal=(t)=>{switch(t){
 case 0:o+=1;break; case 1:o+=1;break; case 2:o+=2;break; case 3:o+=2;break;
 case 4:o+=4;break; case 5:o+=4;break; case 6:o+=4;break; case 7:o+=1;break;
 case 8:str();break; case 9:{const et=u32();const n=u64();for(let i=0;i<n;i++)readVal(et);break;}
 case 10:o+=8;break; case 11:o+=8;break; case 12:o+=8;break;
 default:throw new Error('kv type '+t);}};
for(let i=0;i<nKV;i++){ str(); const t=u32(); readVal(t); }
const counts={}; const notable=[];
for(let i=0;i<nTensors;i++){
  const name=str(); const nd=u32(); const dims=[]; for(let d=0;d<nd;d++)dims.push(u64());
  const type=u32(); u64();
  const tn=T[type]||('type'+type);
  counts[tn]=(counts[tn]||0)+1;
  if(/token_embd|output\.weight|output_norm/.test(name)) notable.push(`${name} -> ${tn} [${dims.join('x')}]`);
}
console.log('gguf v'+ver+'  tensors='+nTensors);
console.log('type counts:', counts);
console.log('notable:'); notable.forEach(n=>console.log('  '+n));
const kleidi=Object.keys(counts).every(k=>['Q4_0','Q8_0','F32'].includes(k));
console.log('ALL WEIGHTS KLEIDIAI-ELIGIBLE (Q4_0/Q8_0, F32 norms):', kleidi);
