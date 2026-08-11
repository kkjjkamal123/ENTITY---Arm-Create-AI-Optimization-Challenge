import {openSync, readSync} from 'fs';
const T={0:'F32',1:'F16',2:'Q4_0',3:'Q4_1',6:'Q5_0',7:'Q5_1',8:'Q8_0',9:'Q8_1',10:'Q2_K',11:'Q3_K',12:'Q4_K',13:'Q5_K',14:'Q6_K',15:'Q8_K',30:'BF16'};
const BPW={F32:32,F16:16,Q4_0:4.5,Q4_1:5,Q8_0:8.5,Q6_K:6.5625,Q4_K:4.5,Q3_K:3.4375};
const fd=openSync(process.argv[2],'r');
const buf=Buffer.alloc(64*1024*1024); readSync(fd,buf,0,buf.length,0);
let o=4; const u32=()=>{const v=buf.readUInt32LE(o);o+=4;return v;};
const u64=()=>{const v=Number(buf.readBigUInt64LE(o));o+=8;return v;};
const str=()=>{const n=u64();const s=buf.toString('utf8',o,o+n);o+=n;return s;};
u32(); const nT=u64(); const nKV=u64();
const rv=(t)=>{switch(t){case 0:case 1:case 7:o+=1;break;case 2:case 3:o+=2;break;
 case 4:case 5:case 6:o+=4;break;case 8:str();break;
 case 9:{const et=u32();const n=u64();for(let i=0;i<n;i++)rv(et);break;}
 case 10:case 11:case 12:o+=8;break;default:throw new Error('kv '+t);}};
for(let i=0;i<nKV;i++){str();rv(u32());}
let totalP=0, badP=0; const bad=[];
for(let i=0;i<nT;i++){
  const name=str(); const nd=u32(); const dims=[]; for(let d=0;d<nd;d++)dims.push(u64());
  const tn=T[u32()]||'?'; u64();
  const p=dims.reduce((a,b)=>a*b,1);
  if(tn!=='F32'){ totalP+=p; if(tn!=='Q4_0'&&tn!=='Q8_0'){ badP+=p; bad.push({name,tn,p,dims}); } }
}
console.log(process.argv[2].split('/').pop());
console.log('  weight params (non-F32):', (totalP/1e6).toFixed(1)+'M');
console.log('  NOT KleidiAI-eligible  :', (badP/1e6).toFixed(1)+'M  = '+(100*badP/totalP).toFixed(1)+'% of weights');
bad.forEach(b=>console.log(`    ${b.name}  ${b.tn}  [${b.dims.join('x')}]  ${(b.p/1e6).toFixed(1)}M`));
